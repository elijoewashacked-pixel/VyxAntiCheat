package com.vyxac.hacks;

import com.vyxac.PlayerData;
import com.vyxac.VyxAC;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Runs the checks: queue, probes, DoubleCheck, Bedrock skipping, results, commands, Discord and the VyxAC / AI integration. */
public final class HackChecker {
    public enum Reason { MANUAL, JOIN, ANTICHEAT, WEB, VYXAC }

    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9_.]{1,32}$");
    private static boolean noFloodgate, noGeyser;

    private static final class Ref {
        final String modId;
        final ModDef.Probe probe;

        Ref(String modId, ModDef.Probe probe) {
            this.modId = modId;
            this.probe = probe;
        }
    }

    private static final class Job {
        final HackConfig cfg;
        final CommandSender starter;
        final String starterName;
        final Player player;
        final Reason reason;
        final List<ModDef> mods;
        final ScanResult scan;

        Job(HackConfig cfg, CommandSender starter, String starterName, Player player, Reason reason, List<ModDef> mods) {
            this.cfg = cfg;
            this.starter = starter;
            this.starterName = starterName;
            this.player = player;
            this.reason = reason;
            this.mods = mods;
            this.scan = new ScanResult(System.currentTimeMillis(), player.getUniqueId(), player.getName(), reason.name().toLowerCase(Locale.ROOT), starterName);
        }
    }

    private final VyxAC pl;
    private volatile HackConfig cfg;
    private final HackText text;
    private final ScanStore store;
    private SignPackets signs;
    private String unavailable = "not started";
    private final Set<UUID> running = new HashSet<>();
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private final Map<UUID, Long> lastAuto = new HashMap<>();
    private final Set<UUID> alertsOff = new HashSet<>();
    private int active;
    private WebEditor web;
    private Updater updater;
    private AnticheatHooks hooks;

    public HackChecker(VyxAC pl) {
        this.pl = pl;
        this.text = new HackText(pl);
        this.store = new ScanStore(pl);
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        cfg = HackConfig.load(pl);
        text.load(cfg.lang);
        store.open();
        unavailable = computeUnavailable();
        if (cfg.enabled && unavailable == null) {
            try {
                signs = new SignPackets(pl);
            } catch (Throwable t) {
                signs = null;
                unavailable = "the packet layer could not start: " + t;
                pl.getLogger().warning("CheckHacks: " + unavailable);
            }
        } else if (!cfg.enabled) {
            unavailable = "disabled in checkhacks.yml";
        }
        web = new WebEditor(pl, this);
        updater = new Updater(pl, this);
        hooks = new AnticheatHooks(pl, this);
        web.start();
        updater.check();
        Bukkit.getScheduler().runTaskLater(pl, () -> hooks.register(cfg), 60L);
        pl.getLogger().info("CheckHacks: " + (signs != null ? "ready (" + countUsable() + " mods)" : "not active: " + unavailable));
    }

    public void reload() {
        cfg = HackConfig.load(pl);
        text.load(cfg.lang);
        web.restart();
        hooks.register(cfg);
    }

    public void stop() {
        if (signs != null) signs.shutdown();
        if (web != null) web.stop();
        store.close();
    }

    private String computeUnavailable() {
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) return "ProtocolLib is not installed";
        try {
            Class.forName("net.kyori.adventure.text.Component");
            Player.class.getMethod("sendSignChange", Location.class, List.class, DyeColor.class, boolean.class);
        } catch (Throwable t) {
            return "this server is not Paper (or a Paper fork): the Adventure sign API is missing";
        }
        return null;
    }

    public HackConfig cfg() { return cfg; }
    public HackText text() { return text; }
    public ScanStore store() { return store; }
    public WebEditor web() { return web; }
    public Updater updater() { return updater; }
    public boolean available() { return cfg.enabled && signs != null; }
    public String unavailableReason() { return unavailable; }
    public void cancelPlayer(UUID id) { if (signs != null) signs.cancel(id); }

    private int countUsable() {
        int n = 0;
        for (ModDef m : cfg.mods.values()) if (usable(m)) n++;
        return n;
    }

    private static boolean usable(ModDef m) {
        return m.enabled && !m.probes.isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    public boolean toggleAlerts(Player p) {
        if (!alertsOff.remove(p.getUniqueId())) {
            alertsOff.add(p.getUniqueId());
            return false;
        }
        return true;
    }

    public boolean isBedrock(Player p) {
        String n = p.getName();
        for (String pre : cfg.bedrockPrefixes) if (pre != null && !pre.isEmpty() && n.startsWith(pre)) return true;
        if (!noFloodgate) {
            try {
                Class<?> c = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                Object api = c.getMethod("getInstance").invoke(null);
                if ((Boolean) c.getMethod("isFloodgatePlayer", UUID.class).invoke(api, p.getUniqueId())) return true;
            } catch (ClassNotFoundException e) {
                noFloodgate = true;
            } catch (Throwable ignored) {
                // API present but not ready
            }
        }
        if (!noGeyser) {
            try {
                Class<?> c = Class.forName("org.geysermc.geyser.api.GeyserApi");
                Object api = c.getMethod("api").invoke(null);
                if ((Boolean) c.getMethod("isBedrockPlayer", UUID.class).invoke(api, p.getUniqueId())) return true;
            } catch (ClassNotFoundException e) {
                noGeyser = true;
            } catch (Throwable ignored) {
                // API present but not ready
            }
        }
        return false;
    }

    /** mod ids, categories or "all" -> mods that can actually be checked */
    public List<ModDef> select(List<String> tokens) {
        HackConfig c = cfg;
        LinkedHashMap<String, ModDef> out = new LinkedHashMap<>();
        List<String> list = tokens.isEmpty() ? c.listNormal : tokens;
        for (String tok : list) {
            String k = tok.toLowerCase(Locale.ROOT).trim();
            if (k.equals("all") || k.equals("*")) {
                for (ModDef m : c.mods.values()) if (usable(m)) out.put(m.id, m);
            } else if (c.mods.containsKey(k)) {
                ModDef m = c.mods.get(k);
                if (usable(m)) out.put(m.id, m);
            } else {
                for (ModDef m : c.mods.values()) if (m.category.equals(k) && usable(m)) out.put(m.id, m);
            }
        }
        return new ArrayList<>(out.values());
    }

    private void tell(CommandSender s, String msg) {
        if (s != null) s.sendMessage(msg);
    }

    private String reasonText(String key) {
        return HackText.strip(text.msg("reason-" + key));
    }

    // ------------------------------------------------------------------ starting checks

    /** automatic checks: join, anticheat flags, VyxAC flags */
    public void trigger(Player p, Reason reason, String starterName) {
        if (p == null || !p.isOnline() || !available()) return;
        HackConfig c = cfg;
        List<String> list = reason == Reason.JOIN ? c.listJoin : (reason == Reason.ANTICHEAT || reason == Reason.VYXAC ? c.listAnticheat : c.listNormal);
        check(null, starterName, p, reason, select(list));
    }

    /** called for every VyxAC flag: a high total violation level starts a check */
    public void onVyxacFlag(PlayerData pd, double totalVl) {
        HackConfig c = cfg;
        if (c == null || !c.vyxacHook || totalVl < c.vyxacMinVl) return;
        Player p = Bukkit.getPlayer(pd.id);
        if (p != null) trigger(p, Reason.VYXAC, "VyxAC");
    }

    public boolean check(CommandSender starter, String starterName, Player target, Reason reason, List<ModDef> mods) {
        HackConfig c = cfg;
        if (!c.enabled) {
            tell(starter, text.prefixed("disabled"));
            return false;
        }
        if (signs == null) {
            tell(starter, text.prefixed("unavailable", "reason", unavailable));
            return false;
        }
        if (mods.isEmpty()) {
            tell(starter, text.prefixed("unavailable", "reason", "no mod with a probe is enabled (checkhacks.yml > mods)"));
            return false;
        }
        UUID id = target.getUniqueId();
        boolean auto = reason != Reason.MANUAL && reason != Reason.WEB;
        long now = System.currentTimeMillis();
        if (auto) {
            Long last = lastAuto.get(id);
            if (last != null && now - last < c.cooldownSec * 1000L) return false; // no spam from automatic checks
        }
        if (running.contains(id)) {
            tell(starter, text.prefixed("busy", "player", target.getName()));
            return false;
        }
        Job j = new Job(c, starter, starterName, target, reason, mods);
        if (isBedrock(target)) {
            for (ModDef m : mods) j.scan.results.put(m.id, new ScanResult.Entry(m.name, ProbeEval.Result.SKIPPED));
            tell(starter, text.prefixed("bedrock-skipped", "player", target.getName()));
            store.save(j.scan);
            return false;
        }
        if (auto) lastAuto.put(id, now);
        running.add(id);
        queue.add(j);
        tell(starter, text.prefixed("check-started", "player", target.getName(), "count", Integer.toString(mods.size())));
        pump();
        return true;
    }

    private void pump() {
        while (active < cfg.maxConcurrent && !queue.isEmpty()) {
            Job j = queue.poll();
            if (!j.player.isOnline()) {
                running.remove(j.player.getUniqueId());
                continue;
            }
            active++;
            run(j);
        }
    }

    // ------------------------------------------------------------------ the scan itself

    private static String seed() {
        return Integer.toString(ThreadLocalRandom.current().nextInt(1_000_000, Integer.MAX_VALUE), 36);
    }

    /** one pass over the given probes. cb gets mod id -> result, or null when the player left / the packets failed */
    private void pass(Job j, List<Ref> refs, Consumer<Map<String, ProbeEval.Result>> cb) {
        List<ProbeEval.ProbeRef> probes = new ArrayList<>();
        for (int i = 0; i < refs.size(); i++) probes.add(new ProbeEval.ProbeRef(i, refs.get(i).probe.mode, refs.get(i).probe.key, refs.get(i).probe.key2));
        final List<ProbeEval.Sign> plan = ProbeEval.plan(probes, seed());
        signs.probe(j.player, plan, j.cfg.betweenTicks, j.cfg.timeoutTicks, j.cfg.signPosition, answers -> {
            if (answers == null) {
                cb.accept(null);
                return;
            }
            Map<String, List<ProbeEval.Result>> byMod = new LinkedHashMap<>();
            for (int i = 0; i < plan.size(); i++) {
                ProbeEval.Sign s = plan.get(i);
                ProbeEval.Result[] r = ProbeEval.evaluate(s, answers[i]);
                for (int k = 0; k < r.length; k++) byMod.computeIfAbsent(refs.get(s.probeIds.get(k)).modId, x -> new ArrayList<>()).add(r[k]);
            }
            Map<String, ProbeEval.Result> out = new LinkedHashMap<>();
            for (Map.Entry<String, List<ProbeEval.Result>> e : byMod.entrySet()) out.put(e.getKey(), ProbeEval.combine(e.getValue()));
            cb.accept(out);
        });
    }

    private void run(Job j) {
        List<Ref> refs = new ArrayList<>();
        for (ModDef m : j.mods) for (ModDef.Probe p : m.probes) refs.add(new Ref(m.id, p));
        pass(j, refs, first -> {
            if (first == null) {
                abort(j);
                return;
            }
            Map<String, ProbeEval.Result> fin = new LinkedHashMap<>(first);
            Set<String> bad = new HashSet<>();
            for (Map.Entry<String, ProbeEval.Result> e : first.entrySet()) {
                if (e.getValue() == ProbeEval.Result.DETECTED || e.getValue() == ProbeEval.Result.PROTECTED) bad.add(e.getKey());
            }
            if (!j.cfg.doublecheck || bad.isEmpty()) {
                complete(j, fin, false);
                return;
            }
            // DoubleCheck: every DETECTED / PROTECTED result must be confirmed by a second scan
            List<Ref> again = new ArrayList<>();
            for (Ref r : refs) if (bad.contains(r.modId)) again.add(r);
            pass(j, again, second -> {
                if (second != null) {
                    for (String id : bad) fin.put(id, ProbeEval.merge(first.get(id), second.getOrDefault(id, ProbeEval.Result.NOT_DETECTED)));
                }
                complete(j, fin, second != null);
            });
        });
    }

    private void abort(Job j) {
        active--;
        running.remove(j.player.getUniqueId());
        pump();
    }

    private void complete(Job j, Map<String, ProbeEval.Result> results, boolean doubleChecked) {
        for (ModDef m : j.mods) j.scan.results.put(m.id, new ScanResult.Entry(m.name, results.getOrDefault(m.id, ProbeEval.Result.NOT_DETECTED)));
        j.scan.doubleChecked = doubleChecked;
        active--;
        running.remove(j.player.getUniqueId());
        store.save(j.scan);
        notifyResult(j);
        runCommands(j);
        discord(j);
        integrate(j);
        pump();
    }

    // ------------------------------------------------------------------ results

    private String resultText(ProbeEval.Result r) {
        return text.msg("result-" + r.name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    private void notifyResult(Job j) {
        ScanResult r = j.scan;
        List<String> lines = new ArrayList<>();
        lines.add(text.prefixed("result-header", "player", r.name, "reason", reasonText(r.reason), "checker", r.starter));
        for (ScanResult.Entry e : r.results.values()) {
            if (e.result == ProbeEval.Result.NOT_DETECTED && !j.cfg.showClean) continue;
            lines.add(text.msg("result-line", "mod", e.modName, "result", resultText(e.result)));
        }
        if (!r.flagged()) lines.add(text.msg("result-clean", "player", r.name));
        Set<UUID> sent = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.hasPermission("checkhacks.alerts") || alertsOff.contains(p.getUniqueId())) continue;
            sent.add(p.getUniqueId());
            for (String l : lines) p.sendMessage(HackText.papi(p, l));
        }
        // whoever started the check always gets the result, even without the alerts permission
        if (j.starter instanceof Player sp && !sent.contains(sp.getUniqueId())) for (String l : lines) sp.sendMessage(l);
        if (!(j.starter instanceof Player)) for (String l : lines) Bukkit.getConsoleSender().sendMessage(l);
    }

    private void dispatch(String raw, String player, String hack) {
        if (!SAFE_NAME.matcher(player).matches()) return;
        String cmd = raw.replace("%player%", player).replace("%hack%", hack).replace("&hack&", hack).replace("{hack}", hack).trim();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        if (cmd.isEmpty()) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
    }

    private void runCommands(Job j) {
        ScanResult r = j.scan;
        List<String> detected = r.names(ProbeEval.Result.DETECTED), prot = r.names(ProbeEval.Result.PROTECTED);
        if (!detected.isEmpty()) for (String c : j.cfg.onDetected) dispatch(c, r.name, String.join(", ", detected));
        if (!prot.isEmpty()) for (String c : j.cfg.onProtected) dispatch(c, r.name, String.join(", ", prot));
        if (detected.isEmpty() && prot.isEmpty()) for (String c : j.cfg.onClean) dispatch(c, r.name, "");
        for (HackConfig.Rule rule : j.cfg.rules) {
            for (Map.Entry<String, ScanResult.Entry> e : r.results.entrySet()) {
                boolean modOk = rule.mod.equals("any") || rule.mod.equals("*") || rule.mod.equals(e.getKey());
                boolean resOk = rule.result.equals("ANY") || rule.result.equals(e.getValue().result.name());
                if (modOk && resOk) for (String c : rule.commands) dispatch(c, r.name, e.getValue().modName);
            }
        }
    }

    private void discord(Job j) {
        ScanResult r = j.scan;
        if (j.cfg.hackWebhook.isEmpty() || (j.cfg.discordOnlyFlagged && !r.flagged())) return;
        StringBuilder results = new StringBuilder();
        for (ScanResult.Entry e : r.results.values()) results.append("- ").append(e.modName).append(": ").append(e.result.name()).append('\n');
        String body = j.cfg.hackTemplate.replace("&name&", r.name).replace("&checker&", r.starter).replace("&reason&", reasonText(r.reason))
                .replace("&hacks&", String.join(", ", r.names(ProbeEval.Result.DETECTED))).replace("&results&", results.toString().trim())
                .replace("&lang&", localeOf(j.player));
        Discord.send(pl, j.cfg, j.cfg.hackWebhook, body);
    }

    /** a confirmed hack client is real evidence for VyxAC and a ground-truth label for the AI */
    private void integrate(Job j) {
        PlayerData pd = pl.dataOrNull(j.player.getUniqueId());
        if (pd == null) return;
        for (Map.Entry<String, ScanResult.Entry> e : j.scan.results.entrySet()) {
            if (e.getValue().result != ProbeEval.Result.DETECTED) continue;
            ModDef m = j.cfg.mods.get(e.getKey());
            if (m == null || !m.category.equals("hack")) continue;
            pl.vio().flag(pd, pl.checks().get("HackClient"), m.name + " detected by the sign translation check", 1.0);
            pl.ai().label(pd, true, "hack client " + m.name);
        }
    }

    // ------------------------------------------------------------------ language check and probing

    @SuppressWarnings("deprecation")
    private static String localeOf(Player p) {
        try {
            String l = p.getLocale();
            return l == null ? "?" : l;
        } catch (Throwable t) {
            return "?";
        }
    }

    public void checkLanguage(Player target, CommandSender starter) {
        HackConfig c = cfg;
        String locale = localeOf(target);
        String code = locale.toLowerCase(Locale.ROOT).split("[_-]")[0];
        String name = c.langNames.getOrDefault(code, code);
        String line = text.prefixed("lang-line", "player", target.getName(), "language", name, "code", locale);
        boolean highlight = c.langHighlight.contains(code);
        if (starter != null || highlight) {
            Set<UUID> sent = new HashSet<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("checkhacks.alerts") && !alertsOff.contains(p.getUniqueId())) {
                    p.sendMessage(line);
                    sent.add(p.getUniqueId());
                }
            }
            if (starter instanceof Player sp && !sent.contains(sp.getUniqueId())) sp.sendMessage(line);
            if (!(starter instanceof Player)) Bukkit.getConsoleSender().sendMessage(line);
        }
        if (!c.langWebhook.isEmpty()) {
            Discord.send(pl, c, c.langWebhook, c.langTemplate.replace("&name&", target.getName()).replace("&lang&", name + " (" + locale + ")")
                    .replace("&checker&", starter == null ? "auto" : starter.getName()).replace("&reason&", "language check").replace("&hacks&", "").replace("&results&", ""));
        }
    }

    /** raw test of one key on a player: used to verify entries of the mod table with a real client */
    public void probe(CommandSender s, Player target, String mode, String key) {
        if (signs == null) {
            tell(s, text.prefixed("unavailable", "reason", unavailable));
            return;
        }
        final List<ProbeEval.Sign> plan = ProbeEval.plan(List.of(new ProbeEval.ProbeRef(0, mode, key, "")), seed());
        signs.probe(target, plan, 1, cfg.timeoutTicks, cfg.signPosition, answers -> {
            if (answers == null || answers[0] == null) {
                tell(s, text.prefixed("probe-result", "key", key, "answer", "(no answer)", "verdict", "PROTECTED"));
                return;
            }
            ProbeEval.Result r = ProbeEval.evaluate(plan.get(0), answers[0])[0];
            boolean controlOk = answers[0][3] != null && answers[0][3].trim().equals(plan.get(0).keys[3]);
            tell(s, text.prefixed("probe-result", "key", key, "answer", String.valueOf(answers[0][0]) + (controlOk ? "" : " [control line was changed]"), "verdict", r.name()));
        });
    }

    /** used by the web editor (called on the main thread) */
    public String webCheck(String playerName, List<String> tokens) {
        Player p = Bukkit.getPlayerExact(playerName);
        if (p == null) return "player is not online";
        if (!available()) return unavailable;
        List<ModDef> mods = select(tokens);
        if (mods.isEmpty()) return "no mods selected";
        return check(null, "web editor", p, Reason.WEB, mods) ? null : "could not start (already running, or Bedrock player)";
    }
}
