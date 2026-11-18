package com.vyxac;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** In-game ticket system: players report, staff claim / reply / dismiss. Stored in plugins/VyxAC/reports.yml. */
public final class ReportManager {
    private final VyxAC pl;
    private final File file;
    private final Map<Integer, Report> reports = new LinkedHashMap<>();
    private final Map<UUID, Long> lastReport = new HashMap<>();
    private int nextId = 1;
    private boolean enabled = true, notifyStaff = true, discord = true, showStaff = true;
    private long cooldownMs = 60_000L;
    private int maxOpen = 3, keepDays = 14;
    private String mSubmitted = "", mDismissed = "", mActioned = "", mReply = "";

    public ReportManager(VyxAC pl) {
        this.pl = pl;
        this.file = new File(pl.getDataFolder(), "reports.yml");
    }

    public void reload(FileConfiguration c) {
        enabled = c.getBoolean("reports.enabled", true);
        cooldownMs = c.getLong("reports.cooldown-seconds", 60) * 1000L;
        maxOpen = Math.max(1, c.getInt("reports.max-open-per-player", 3));
        notifyStaff = c.getBoolean("reports.notify-staff", true);
        discord = c.getBoolean("reports.discord", true);
        showStaff = c.getBoolean("reports.show-staff-name", true);
        keepDays = c.getInt("reports.keep-closed-days", 14);
        mSubmitted = c.getString("reports.messages.submitted", "&aReport #%id% against &f%target% &asent. Staff will review it. Thank you!");
        mDismissed = c.getString("reports.messages.dismissed", "&7Your report #%id% against &f%target% &7was reviewed: no violation was found. %note%");
        mActioned = c.getString("reports.messages.actioned", "&aYour report #%id% against &f%target% &awas reviewed and action was taken. %note%");
        mReply = c.getString("reports.messages.reply", "&b[Reports] &f%staff% &7replied on #%id%: &f%message%");
    }

    // ------------------------------------------------------------------ storage

    public void load() {
        reports.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        nextId = y.getInt("next-id", 1);
        ConfigurationSection sec = y.getConfigurationSection("reports");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(key);
                if (s == null) continue;
                Report r = new Report();
                try {
                    r.id = Integer.parseInt(key);
                    r.reporterId = UUID.fromString(s.getString("reporter-id", ""));
                    r.targetId = UUID.fromString(s.getString("target-id", ""));
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                r.reporterName = s.getString("reporter", "?");
                r.targetName = s.getString("target", "?");
                r.reason = s.getString("reason", "");
                r.snapshot = s.getString("snapshot", "");
                r.status = s.getString("status", Report.OPEN);
                r.claimedBy = s.getString("claimed-by", "");
                r.closedBy = s.getString("closed-by", "");
                r.resolution = s.getString("resolution", "");
                r.createdAt = s.getLong("created", 0);
                r.closedAt = s.getLong("closed", 0);
                r.messages.addAll(s.getStringList("messages"));
                r.unread.addAll(s.getStringList("unread"));
                reports.put(r.id, r);
                if (r.id >= nextId) nextId = r.id + 1;
            }
        }
        prune();
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-id", nextId);
        for (Report r : reports.values()) {
            String b = "reports." + r.id + ".";
            y.set(b + "reporter-id", r.reporterId.toString());
            y.set(b + "target-id", r.targetId.toString());
            y.set(b + "reporter", r.reporterName);
            y.set(b + "target", r.targetName);
            y.set(b + "reason", r.reason);
            y.set(b + "snapshot", r.snapshot);
            y.set(b + "status", r.status);
            y.set(b + "claimed-by", r.claimedBy);
            y.set(b + "closed-by", r.closedBy);
            y.set(b + "resolution", r.resolution);
            y.set(b + "created", r.createdAt);
            y.set(b + "closed", r.closedAt);
            y.set(b + "messages", r.messages);
            y.set(b + "unread", r.unread);
        }
        try {
            file.getParentFile().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            pl.getLogger().warning("Could not save reports: " + ex.getMessage());
        }
    }

    private void prune() {
        long limit = System.currentTimeMillis() - keepDays * 86_400_000L;
        reports.values().removeIf(r -> !r.isOpen() && r.closedAt != 0 && r.closedAt < limit && r.unread.isEmpty());
    }

    // ------------------------------------------------------------------ queries

    public Report get(int id) {
        return reports.get(id);
    }

    public List<Report> open() {
        List<Report> l = new ArrayList<>();
        for (Report r : reports.values()) if (r.isOpen()) l.add(r);
        l.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return l;
    }

    public List<Report> closed(int limit) {
        List<Report> l = new ArrayList<>();
        for (Report r : reports.values()) if (!r.isOpen()) l.add(r);
        l.sort((a, b) -> Long.compare(b.closedAt, a.closedAt));
        return l.size() > limit ? new ArrayList<>(l.subList(0, limit)) : l;
    }

    public List<Report> byReporter(UUID id) {
        List<Report> l = new ArrayList<>();
        for (Report r : reports.values()) if (r.reporterId.equals(id)) l.add(r);
        l.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return l;
    }

    public int openCount() {
        int n = 0;
        for (Report r : reports.values()) if (r.isOpen()) n++;
        return n;
    }

    public int openAgainst(UUID target) {
        int n = 0;
        for (Report r : reports.values()) if (r.isOpen() && r.targetId.equals(target)) n++;
        return n;
    }

    // ------------------------------------------------------------------ creating

    private String snapshot(UUID target) {
        PlayerData pd = pl.dataOrNull(target);
        if (pd == null) return "target offline: no live data";
        List<Map.Entry<String, Double>> l = new ArrayList<>(pd.vl.entrySet());
        l.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, l.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(l.get(i).getKey()).append(' ').append(String.format("%.1f", l.get(i).getValue()));
        }
        return "VyxAC: VL " + String.format("%.1f", pd.totalVl()) + (sb.length() > 0 ? " (" + sb + ")" : "")
                + ", AI " + String.format("%.0f%%", pd.aiConfidence * 100) + ", ping " + pd.ping;
    }

    /** @return null on success, otherwise a coloured error message for the reporter */
    public String submit(Player reporter, String targetName, String rawReason) {
        if (!enabled) return Settings.color("&cReports are disabled.");
        long now = System.currentTimeMillis();
        Long last = lastReport.get(reporter.getUniqueId());
        if (last != null && now - last < cooldownMs) {
            return Settings.color("&cPlease wait " + ((cooldownMs - (now - last)) / 1000 + 1) + "s before sending another report.");
        }
        String reason = ChatColor.stripColor(rawReason).replaceAll("[&\u00a7]", "").trim();
        if (reason.length() < 4) return Settings.color("&cPlease give a reason (at least 4 characters).");
        if (reason.length() > 120) reason = reason.substring(0, 120);

        UUID tid;
        String tname;
        Player online = Bukkit.getPlayerExact(targetName);
        if (online != null) {
            tid = online.getUniqueId();
            tname = online.getName();
        } else {
            OfflinePlayer op = Bukkit.getOfflinePlayer(targetName);
            if (!op.hasPlayedBefore()) return Settings.color("&cI do not know a player called &f" + targetName + "&c.");
            tid = op.getUniqueId();
            tname = op.getName() == null ? targetName : op.getName();
        }
        if (tid.equals(reporter.getUniqueId())) return Settings.color("&cYou cannot report yourself.");
        int mine = 0;
        for (Report r : reports.values()) {
            if (!r.isOpen() || !r.reporterId.equals(reporter.getUniqueId())) continue;
            mine++;
            if (r.targetId.equals(tid)) return Settings.color("&cYou already have an open report about &f" + tname + "&c (#" + r.id + ").");
        }
        if (mine >= maxOpen) return Settings.color("&cYou already have " + mine + " open reports. Wait until staff handle them.");

        Report r = new Report();
        r.id = nextId++;
        r.reporterId = reporter.getUniqueId();
        r.reporterName = reporter.getName();
        r.targetId = tid;
        r.targetName = tname;
        r.reason = reason;
        r.createdAt = now;
        r.snapshot = snapshot(tid);
        reports.put(r.id, r);
        lastReport.put(reporter.getUniqueId(), now);
        save();

        reporter.sendMessage(Settings.color(fill(mSubmitted, r, "", "", "")));
        announce(r);
        return null;
    }

    private void announce(Report r) {
        String plain = Settings.color(pl.settings().prefix + "&eNew report &f#" + r.id + " &7" + r.reporterName + " &8-> &c" + r.targetName + "&7: &f" + r.reason + " ");
        if (notifyStaff) {
            for (Player st : Bukkit.getOnlinePlayers()) {
                if (!st.hasPermission("vyxac.staff") && !st.hasPermission("vyxac.admin")) continue;
                TextComponent tc = new TextComponent(TextComponent.fromLegacyText(plain));
                TextComponent open = new TextComponent(TextComponent.fromLegacyText(Settings.color("&a[Open]")));
                open.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/vyxac reports view " + r.id));
                open.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(Settings.color("&7Open this ticket"))));
                tc.addExtra(open);
                st.spigot().sendMessage(tc);
            }
            Bukkit.getConsoleSender().sendMessage(plain);
        }
        if (discord) pl.vio().webhook(ChatColor.stripColor(plain).trim() + " | " + r.snapshot);
        pl.staff().refreshKits();
    }

    // ------------------------------------------------------------------ staff actions

    public void claim(Report r, String staff) {
        r.status = Report.CLAIMED;
        r.claimedBy = staff;
        save();
    }

    public void unclaim(Report r) {
        r.status = Report.OPEN;
        r.claimedBy = "";
        save();
    }

    public void reopen(Report r) {
        r.status = Report.OPEN;
        r.claimedBy = "";
        r.closedBy = "";
        r.closedAt = 0;
        r.resolution = "";
        save();
        pl.staff().refreshKits();
    }

    private String fill(String t, Report r, String staff, String message, String note) {
        return t.replace("%id%", Integer.toString(r.id)).replace("%target%", r.targetName).replace("%staff%", staff)
                .replace("%message%", message).replace("%note%", note.isEmpty() ? "" : "&7Note: &f" + note);
    }

    private void tell(Report r, String text) {
        Player p = Bukkit.getPlayer(r.reporterId);
        if (p != null && p.isOnline()) p.sendMessage(Settings.color(text));
        else r.unread.add(text);
    }

    public void respond(Report r, String staff, String text) {
        String clean = text.replaceAll("[&\u00a7]", "");
        r.messages.add(staff + "|" + System.currentTimeMillis() + "|" + clean);
        tell(r, fill(mReply, r, showStaff ? staff : "Staff", clean, ""));
        save();
    }

    public void close(Report r, String staff, boolean actioned, String note) {
        String clean = note == null ? "" : note.replaceAll("[&\u00a7]", "");
        r.status = actioned ? Report.ACTIONED : Report.DISMISSED;
        r.closedBy = staff;
        r.closedAt = System.currentTimeMillis();
        r.resolution = clean;
        if (!clean.isEmpty()) r.messages.add(staff + "|" + r.closedAt + "|" + clean);
        tell(r, fill(actioned ? mActioned : mDismissed, r, showStaff ? staff : "Staff", "", clean));
        save();
        pl.staff().refreshKits();
    }

    /** called when a player joins: deliver answers that arrived while they were offline */
    public void deliverPending(Player p) {
        boolean any = false;
        for (Report r : reports.values()) {
            if (!r.reporterId.equals(p.getUniqueId()) || r.unread.isEmpty()) continue;
            for (String m : r.unread) p.sendMessage(Settings.color(m));
            r.unread.clear();
            any = true;
        }
        if (any) save();
    }
}
