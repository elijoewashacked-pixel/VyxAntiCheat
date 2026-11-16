package com.vyxac;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Daily diagnostics. Collects cheap counters all day and, when the date changes (or on request), turns them into a report:
 * which checks flagged many DIFFERENT players (probably false positives, since cheaters are rare), which checks are
 * dominated by one or two players (probably real cheaters), server health, AI cost, and concrete commands to tune things.
 * Statistical analysis only: no extra CPU beyond a few map updates per flag.
 */
public final class Diagnostics {
    private static final int CAP = 5000;

    private static final class CheckStat {
        long flags, mitigations;
        double maxVl;
        final Map<UUID, Integer> players = new HashMap<>();
    }

    private final VyxAC pl;
    private final Map<String, CheckStat> stats = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<UUID, Integer> playerFlags = new HashMap<>();
    private final Map<UUID, Set<String>> playerChecks = new HashMap<>();
    private final Map<UUID, Double> aiPeak = new HashMap<>();
    private final Set<UUID> seen = new HashSet<>();
    private long totalFlags, mitigations, punishments, reviews, dismissals, secs, unstableSecs;
    private double tpsMin = 20.0, tpsSum;
    private int peakOnline;
    private String day = LocalDate.now().toString();
    private List<String> last = new ArrayList<>();
    private boolean enabled = true, notifyStaff = true, console = true;

    public Diagnostics(VyxAC pl) {
        this.pl = pl;
    }

    public void reload(FileConfiguration c) {
        enabled = c.getBoolean("diagnostics.enabled", true);
        notifyStaff = c.getBoolean("diagnostics.notify-staff", true);
        console = c.getBoolean("diagnostics.console", true);
    }

    // ------------------------------------------------------------------ recording (cheap)

    public void onFlag(PlayerData pd, CheckInfo ci, double vl, boolean mitigated) {
        if (!enabled) return;
        totalFlags++;
        if (mitigated) mitigations++;
        CheckStat st = stats.computeIfAbsent(ci.name, k -> new CheckStat());
        st.flags++;
        if (mitigated) st.mitigations++;
        if (vl > st.maxVl) st.maxVl = vl;
        if (st.players.size() < CAP || st.players.containsKey(pd.id)) st.players.merge(pd.id, 1, Integer::sum);
        if (names.size() < CAP) names.put(pd.id, pd.name);
        if (playerFlags.size() < CAP || playerFlags.containsKey(pd.id)) {
            playerFlags.merge(pd.id, 1, Integer::sum);
            playerChecks.computeIfAbsent(pd.id, k -> new LinkedHashSet<>()).add(ci.name);
        }
    }

    public void onPunish() { punishments++; }
    public void onReview() { reviews++; }
    public void onDismiss() { dismissals++; }

    /** called once per second from the main ticker */
    public void secondTick() {
        if (!enabled) return;
        String today = LocalDate.now().toString();
        if (!today.equals(day)) {
            finish(false);
            day = today;
        }
        secs++;
        double tps = pl.ticks().tps();
        tpsSum += tps;
        if (tps < tpsMin) tpsMin = tps;
        if (pl.ticks().unstable()) unstableSecs++;
        int online = Bukkit.getOnlinePlayers().size();
        if (online > peakOnline) peakOnline = online;
        for (PlayerData pd : pl.allData()) {
            if (seen.size() < CAP) seen.add(pd.id);
            if (pd.aiConfidence > 0) aiPeak.merge(pd.id, pd.aiConfidence, Math::max);
        }
    }

    // ------------------------------------------------------------------ report

    public List<String> current() {
        return build(true);
    }

    public List<String> lastReport() {
        return last;
    }

    private String nameOf(UUID id) {
        String n = names.get(id);
        return n == null ? id.toString().substring(0, 8) : n;
    }

    private List<String> build(boolean partial) {
        List<String> out = new ArrayList<>();
        out.add("=== VyxAC diagnostics " + day + (partial ? " (so far)" : "") + " ===");
        int seenCount = Math.max(seen.size(), playerFlags.size());
        out.add(String.format("Players seen %d (peak online %d) | flags %d | cancelled moves/hits %d | punishments %d | review prompts %d | dismissed %d",
                seenCount, peakOnline, totalFlags, mitigations, punishments, reviews, dismissals));
        double avg = secs == 0 ? 20.0 : tpsSum / secs;
        out.add(String.format("Server: lowest TPS %.1f, average %.1f, lag guard active %ds (%.1f%% of the time)",
                tpsMin, avg, unstableSecs, secs == 0 ? 0.0 : unstableSecs * 100.0 / secs));
        if (pl.settings().aiEnabled) out.add("Mini AI: " + pl.ai().status());

        if (totalFlags == 0) {
            out.add("No flags in this period. Nothing to tune.");
            return out;
        }

        List<Map.Entry<String, CheckStat>> list = new ArrayList<>(stats.entrySet());
        list.sort((x, y) -> Long.compare(y.getValue().flags, x.getValue().flags));
        List<String> noisy = new ArrayList<>(), real = new ArrayList<>(), tips = new ArrayList<>();
        out.add("-- Checks (flags / different players / highest VL) --");
        int shown = 0;
        for (Map.Entry<String, CheckStat> e : list) {
            CheckStat st = e.getValue();
            int distinct = st.players.size();
            int top = 0;
            UUID topId = null;
            for (Map.Entry<UUID, Integer> pe : st.players.entrySet()) {
                if (pe.getValue() > top) {
                    top = pe.getValue();
                    topId = pe.getKey();
                }
            }
            double share = st.flags == 0 ? 0 : top / (double) st.flags;
            String verdict;
            if (distinct >= 3 && distinct >= 0.2 * Math.max(1, seenCount) && share <= 0.5) {
                verdict = "LOOKS NOISY: many different players";
                noisy.add(e.getKey());
                tips.add("/vyxac configure checks." + e.getKey() + ".alert-vl " + Math.max(3, (int) Math.ceil(st.maxVl)) + "   (quieter alerts)  or  /vyxac check " + e.getKey() + " off  (also see checks." + e.getKey() + ".params.*)");
            } else if (share >= 0.7 && st.flags >= 5) {
                verdict = "probably a real cheater: " + nameOf(topId);
                real.add(nameOf(topId) + " (" + e.getKey() + ")");
            } else {
                verdict = "low volume / mixed";
            }
            if (shown++ < 15) {
                out.add(String.format("%s: %d flags, %d players, max VL %.1f - %s", e.getKey(), st.flags, distinct, st.maxVl, verdict));
            }
        }

        out.add("-- Players with the most flags --");
        List<Map.Entry<UUID, Integer>> pf = new ArrayList<>(playerFlags.entrySet());
        pf.sort((x, y) -> Integer.compare(y.getValue(), x.getValue()));
        for (int i = 0; i < Math.min(5, pf.size()); i++) {
            UUID id = pf.get(i).getKey();
            out.add(String.format("%s: %d flags from %s", nameOf(id), pf.get(i).getValue(), String.join(", ", playerChecks.getOrDefault(id, new LinkedHashSet<>()))));
        }

        List<String> aiLines = new ArrayList<>();
        for (Map.Entry<UUID, Double> e : aiPeak.entrySet()) {
            if (e.getValue() >= 0.6) aiLines.add(String.format("%s: AI confidence peaked at %.0f%%", nameOf(e.getKey()), e.getValue() * 100));
        }
        if (!aiLines.isEmpty()) {
            out.add("-- Mini AI --");
            for (int i = 0; i < Math.min(5, aiLines.size()); i++) out.add(aiLines.get(i));
        }

        out.add("-- Summary --");
        if (!real.isEmpty()) out.add("Review these players first: " + String.join(", ", real.subList(0, Math.min(5, real.size()))) + "  (/vyxac profile <name>)");
        if (!noisy.isEmpty()) {
            out.add("Likely false positives: " + String.join(", ", noisy) + ". Real cheaters are rare, so a check that flags many different players is usually too strict.");
            for (int i = 0; i < Math.min(4, tips.size()); i++) out.add("Tip: " + tips.get(i));
        }
        if (unstableSecs > 600) out.add("The server was laggy for a long time; lag guard ignored flags during that time. Fix the lag first.");
        if (real.isEmpty() && noisy.isEmpty()) out.add("Nothing stands out. Flags are spread thinly, which is normal.");
        return out;
    }

    /** Builds the report, saves it and tells staff. Counters are reset afterwards. */
    public void finish(boolean sync) {
        if (!enabled) return;
        List<String> lines = build(sync);
        last = lines;
        write(lines, sync);
        if (console) {
            for (String l : lines.size() > 12 ? lines.subList(0, 12) : lines) pl.getLogger().info(ChatColor.stripColor(l));
            pl.getLogger().info("Full diagnostics: plugins/VyxAC/diagnostics/" + day + ".txt");
        }
        if (notifyStaff && !sync) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("vyxac.admin")) {
                    p.sendMessage(Settings.color(pl.settings().prefix + "Daily diagnostics for &f" + day + " &7are ready: &b/vyxac diagnostics last"));
                }
            }
        }
        reset();
    }

    private void write(List<String> lines, boolean sync) {
        final String name = day + ".txt";
        final List<String> copy = new ArrayList<>(lines);
        copy.add("");
        Runnable r = () -> {
            try {
                Path dir = pl.getDataFolder().toPath().resolve("diagnostics");
                Files.createDirectories(dir);
                Files.write(dir.resolve(name), copy, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Throwable t) {
                pl.getLogger().warning("Could not write diagnostics: " + t.getMessage());
            }
        };
        if (sync) r.run();
        else Bukkit.getScheduler().runTaskAsynchronously(pl, r);
    }

    private void reset() {
        stats.clear();
        names.clear();
        playerFlags.clear();
        playerChecks.clear();
        aiPeak.clear();
        seen.clear();
        totalFlags = mitigations = punishments = reviews = dismissals = secs = unstableSecs = 0;
        tpsMin = 20.0;
        tpsSum = 0;
        peakOnline = 0;
    }
}
