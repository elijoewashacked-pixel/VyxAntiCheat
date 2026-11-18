package com.vyxac;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Central place where every check reports. Handles VL, decay, alerts, logging and the mitigation decision. */
public final class Violations {
    private final VyxAC pl;
    private final ConcurrentLinkedQueue<String> logQueue = new ConcurrentLinkedQueue<>();
    private final Map<UUID, Boolean> alertPref = new HashMap<>();
    private volatile long lastWebhook;

    public Violations(VyxAC pl) {
        this.pl = pl;
    }

    // ------------------------------------------------------------------ flagging

    public boolean flag(PlayerData pd, CheckInfo ci, String info) {
        return flag(pd, ci, info, 1.0);
    }

    /**
     * Registers one flag. Returns true when the caller should mitigate (cancel the move / hit).
     * Nothing is recorded during join grace, server lag or for bypassed players.
     */
    public boolean flag(PlayerData pd, CheckInfo ci, String info, double weight) {
        Settings s = pl.settings();
        if (!s.enabled || !ci.enabled) return false;
        long now = System.currentTimeMillis();
        if (pd.bypassed(now, s)) return false;
        if (now - pd.joinMs < s.joinGraceMs) return false;
        if (s.lagProtection && pl.ticks().unstable()) return false;

        double add = ci.vlPerFlag * Math.max(0.1, Math.min(weight, 5.0));
        double v = pd.vl.merge(ci.name, add, Double::sum);
        pd.flagsTotal++;
        pd.lastFlagMs = now;
        pd.history.addLast(new PlayerData.FlagRecord(now, ci.name, v, info, ci.punishable));
        while (pd.history.size() > 200) pd.history.removeFirst();

        if (v >= ci.alertVl) alert(pd, ci, info, v, now);
        logLine(pd, ci, info, v);
        if (ci.punishable) pl.punish().evaluate(pd);
        boolean mit = s.mitigation && v >= ci.mitigateVl;
        pl.diagnostics().onFlag(pd, ci, v, mit);
        return mit;
    }

    /** Called once per second: every VL decays a little so old, isolated flags disappear. */
    public void decayAll() {
        double perSec = pl.settings().decayPerMinute / 60.0;
        for (PlayerData pd : pl.allData()) {
            Iterator<Map.Entry<String, Double>> it = pd.vl.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Double> e = it.next();
                double nv = e.getValue() - perSec;
                if (nv <= 0.001) it.remove();
                else e.setValue(nv);
            }
            if (pd.aiConfidence > 0 && System.currentTimeMillis() - pd.aiConfAt > 120_000L) pd.aiConfidence = 0;
        }
    }

    // ------------------------------------------------------------------ alerts

    public boolean alertsEnabled(UUID id) {
        return alertPref.getOrDefault(id, pl.settings().alertsDefaultOn);
    }

    public void setAlerts(UUID id, boolean on) {
        alertPref.put(id, on);
    }

    private void alert(PlayerData pd, CheckInfo ci, String info, double vl, long now) {
        Settings s = pl.settings();
        Long last = pd.lastAlert.get(ci.name);
        if (last != null && now - last < s.alertCooldownMs) return;
        pd.lastAlert.put(ci.name, now);

        String msg = Settings.color(s.alertFormat
                .replace("%player%", pd.name)
                .replace("%check%", ci.name)
                .replace("%info%", info)
                .replace("%vl%", String.format("%.1f", vl))
                .replace("%punish_vl%", ci.punishable ? String.format("%.0f", ci.punishVl) : "-")
                .replace("%ping%", Integer.toString(pd.ping))
                .replace("%tps%", String.format("%.1f", pl.ticks().tps())));

        if (s.alertConsole) Bukkit.getConsoleSender().sendMessage(msg);

        String hover = Settings.color("&b" + ci.name + " &7(" + ci.category + ")\n&7" + ci.description
                + "\n&7Info: &f" + info + "\n&7Total VL: &f" + String.format("%.1f", pd.totalVl())
                + "\n&eClick to teleport");
        for (Player st : Bukkit.getOnlinePlayers()) {
            if (!st.hasPermission("vyxac.alerts") || !alertsEnabled(st.getUniqueId())) continue;
            if (s.alertHover || s.alertButtons) {
                TextComponent tc = new TextComponent(TextComponent.fromLegacyText(msg));
                if (s.alertHover) tc.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(hover)));
                if (s.alertButtons) {
                    tc.addExtra(new TextComponent(" "));
                    tc.addExtra(button("&c[Punish]", "&7Run the punishment ladder for " + pd.name, "/vyxac punish " + pd.name));
                    tc.addExtra(new TextComponent(" "));
                    tc.addExtra(button("&e[Kick]", "&7Kick " + pd.name + " from the server", "/vyxac kick " + pd.name));
                    tc.addExtra(new TextComponent(" "));
                    tc.addExtra(button("&b[Teleport]", "&7Teleport to " + pd.name, "/vyxac tp " + pd.name));
                } else {
                    tc.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/vyxac tp " + pd.name));
                }
                st.spigot().sendMessage(tc);
            } else {
                st.sendMessage(msg);
            }
            if (!s.alertSound.isEmpty()) {
                try {
                    st.playSound(st.getLocation(), s.alertSound, 0.6f, 1.2f);
                } catch (Throwable ignored) {
                    // invalid sound name: ignore
                }
            }
        }
        webhook(ChatColor.stripColor(msg));
    }

    private static TextComponent button(String label, String hover, String cmd) {
        TextComponent b = new TextComponent(TextComponent.fromLegacyText(Settings.color(label)));
        b.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(Settings.color(hover))));
        b.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd));
        return b;
    }

    public boolean webhookConfigured() {
        String u = pl.settings().webhook;
        return u != null && (u.startsWith("https://discord.com/api/webhooks/") || u.startsWith("https://discordapp.com/api/webhooks/"));
    }

    /** like webhook() but ignores the 2 second rate limit (used by /vyxac test) */
    public void webhookNow(String text) {
        lastWebhook = 0;
        webhook(text);
    }

    /** Sends an alert that looks real but records nothing (no VL, no history, no webhook). */
    public void testAlert(CheckInfo ci, String info, String who) {
        Settings s = pl.settings();
        String msg = Settings.color("&e[TEST] " + s.alertFormat
                .replace("%player%", who)
                .replace("%check%", ci.name)
                .replace("%info%", info)
                .replace("%vl%", "0.0")
                .replace("%punish_vl%", ci.punishable ? String.format("%.0f", ci.punishVl) : "-")
                .replace("%ping%", "0")
                .replace("%tps%", String.format("%.1f", pl.ticks().tps())));
        Bukkit.getConsoleSender().sendMessage(msg);
        for (Player st : Bukkit.getOnlinePlayers()) {
            if (st.hasPermission("vyxac.alerts") && alertsEnabled(st.getUniqueId())) st.sendMessage(msg);
        }
    }

    /** Sends a clickable message to every staff member that has alerts enabled. */
    public void toStaff(TextComponent tc, String plain) {
        for (Player st : Bukkit.getOnlinePlayers()) {
            if (st.hasPermission("vyxac.alerts") && alertsEnabled(st.getUniqueId())) st.spigot().sendMessage(tc);
        }
        if (pl.settings().alertConsole) Bukkit.getConsoleSender().sendMessage(plain);
    }

    // ------------------------------------------------------------------ webhook and log

    public void webhook(String text) {
        final String url = pl.settings().webhook;
        if (url == null || url.isEmpty()) return;
        if (!(url.startsWith("https://discord.com/api/webhooks/") || url.startsWith("https://discordapp.com/api/webhooks/"))) return;
        long now = System.currentTimeMillis();
        if (now - lastWebhook < 2000L) return; // rate limit: never spam Discord
        lastWebhook = now;
        final String body = "{\"content\":\"" + jsonEscape(text) + "\"}";
        Bukkit.getScheduler().runTaskAsynchronously(pl, () -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(4000);
                c.setReadTimeout(4000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("User-Agent", "VyxAC");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
                c.getResponseCode();
                c.disconnect();
            } catch (Throwable ignored) {
                // webhook problems must never affect the server
            }
        });
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> { }
                default -> {
                    if (ch >= 0x20) sb.append(ch);
                }
            }
        }
        return sb.toString();
    }

    private void logLine(PlayerData pd, CheckInfo ci, String info, double vl) {
        if (!pl.settings().logToFile) return;
        String t = new SimpleDateFormat("HH:mm:ss").format(new Date());
        logQueue.add("[" + t + "] " + pd.name + " " + ci.name + " vl=" + String.format("%.1f", vl)
                + " ping=" + pd.ping + " tps=" + String.format("%.1f", pl.ticks().tps()) + " | " + info);
    }

    public void log(String line) {
        if (!pl.settings().logToFile) return;
        logQueue.add("[" + new SimpleDateFormat("HH:mm:ss").format(new Date()) + "] " + line);
    }

    public void startFlusher() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(pl, this::flushLog, 100L, 100L);
    }

    public void flushLog() {
        if (logQueue.isEmpty()) return;
        List<String> lines = new ArrayList<>();
        String l;
        while ((l = logQueue.poll()) != null) lines.add(l);
        try {
            Path dir = pl.getDataFolder().toPath().resolve("logs");
            Files.createDirectories(dir);
            Path f = dir.resolve(new SimpleDateFormat("yyyy-MM-dd").format(new Date()) + ".log");
            Files.write(f, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable t) {
            pl.getLogger().warning("Could not write log: " + t.getMessage());
        }
    }
}
