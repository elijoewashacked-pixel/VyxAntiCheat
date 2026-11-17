package com.vyxac;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class VyxCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBS = Arrays.asList("help", "reload", "alerts", "status", "profile", "history", "clear",
            "exempt", "punish", "dismiss", "check", "configure", "customize", "special", "ai", "diagnostics", "diagnose", "tp");

    /** feature name -> config path (used by /vyxac special) */
    private static final Map<String, String> FEATURES = new LinkedHashMap<>();

    static {
        FEATURES.put("mode", "punishments.mode");
        FEATURES.put("mitigation", "features.mitigation");
        FEATURES.put("mitigate-movement", "features.mitigate-movement");
        FEATURES.put("lag-protection", "features.lag-protection");
        FEATURES.put("ai", "features.ai");
        FEATURES.put("ai-sensitivity", "ai.sensitivity");
        FEATURES.put("logging", "features.log-to-file");
        FEATURES.put("require-swing", "general.require-swing");
        FEATURES.put("bedrock-exempt", "general.exempt-bedrock");
        FEATURES.put("console-alerts", "alerts.console");
        FEATURES.put("webhook", "alerts.discord-webhook");
        FEATURES.put("flood-kick", "checks.PacketFlood.params.kick-at");
    }

    private final VyxAC pl;
    private final CustomizeGui gui;

    public VyxCommand(VyxAC pl, CustomizeGui gui) {
        this.pl = pl;
        this.gui = gui;
    }

    private void msg(CommandSender s, String text) {
        s.sendMessage(Settings.color(pl.settings().prefix + text));
    }

    private PlayerData target(CommandSender s, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) {
            msg(s, "&cPlayer &f" + name + " &cis not online.");
            return null;
        }
        return pl.data(p);
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "help" : a[0].toLowerCase(Locale.ROOT);
        boolean admin = s.hasPermission("vyxac.admin");
        if (!admin && !(sub.equals("alerts") && s.hasPermission("vyxac.alerts"))) {
            msg(s, "&cYou do not have permission.");
            return true;
        }
        try {
            switch (sub) {
                case "help" -> help(s);
                case "reload" -> {
                    pl.loadAll();
                    msg(s, "&aConfiguration reloaded (" + pl.checks().all().size() + " checks, mode " + pl.settings().punishMode + ").");
                }
                case "alerts" -> alerts(s, a);
                case "status" -> status(s);
                case "profile" -> profile(s, a);
                case "history" -> history(s, a);
                case "clear" -> clear(s, a);
                case "exempt" -> exempt(s, a);
                case "punish" -> {
                    if (a.length < 2) { msg(s, "&7Usage: /vyxac punish <player>"); break; }
                    PlayerData pd = target(s, a[1]);
                    if (pd == null) break;
                    String top = topCheck(pd);
                    pl.punish().punish(pd, top, true);
                    msg(s, "&aPunishment ladder executed for &f" + pd.name + "&a.");
                }
                case "dismiss" -> {
                    if (a.length < 2) { msg(s, "&7Usage: /vyxac dismiss <player>"); break; }
                    PlayerData pd = target(s, a[1]);
                    if (pd == null) break;
                    pl.punish().dismiss(pd);
                    msg(s, "&aCleared all violations of &f" + pd.name + "&a.");
                }
                case "check" -> check(s, a);
                case "configure" -> configure(s, a);
                case "customize" -> {
                    if (s instanceof Player p) gui.open(p);
                    else msg(s, "&cOnly players can open the GUI. Use /vyxac configure instead.");
                }
                case "special" -> special(s, a);
                case "ai" -> ai(s, a);
                case "diagnostics", "diagnose" -> diagnostics(s, a);
                case "tp" -> {
                    if (!(s instanceof Player p)) { msg(s, "&cPlayers only."); break; }
                    if (a.length < 2) { msg(s, "&7Usage: /vyxac tp <player>"); break; }
                    Player t = Bukkit.getPlayerExact(a[1]);
                    if (t == null) { msg(s, "&cPlayer is not online."); break; }
                    p.teleport(t);
                    msg(s, "&aTeleported to &f" + t.getName() + "&a.");
                }
                default -> help(s);
            }
        } catch (Throwable t) {
            msg(s, "&cError: " + t.getMessage());
            pl.getLogger().warning("Command error: " + t);
        }
        return true;
    }

    // ------------------------------------------------------------------ subcommands

    private void help(CommandSender s) {
        String[] lines = {
                "&b/vyxac status &7- server health, AI cost, counts",
                "&b/vyxac alerts [on|off] &7- your alerts",
                "&b/vyxac profile <player> &7- violations, AI confidence, strikes",
                "&b/vyxac history <player> [n] &7- latest flags",
                "&b/vyxac clear <player|all> &7- reset violations",
                "&b/vyxac exempt <player> [seconds] &7- temporarily ignore a player (0 = remove)",
                "&b/vyxac punish|dismiss <player> &7- act on a review prompt",
                "&b/vyxac check <name> [on|off] &7- enable / disable one check",
                "&b/vyxac configure [path] [value] &7- read / change any config value",
                "&b/vyxac customize &7- GUI to toggle checks and features",
                "&b/vyxac special [feature] [value] &7- mode, mitigation, lag protection, AI, webhook ...",
                "&b/vyxac ai [status|reset <player>] &7- mini AI",
                "&b/vyxac diagnostics [now|last] &7- daily report: noisy checks, suspected cheaters, tuning tips",
                "&b/vyxac reload &7- reload config.yml",
        };
        msg(s, "&fVyxAC &7commands:");
        for (String l : lines) s.sendMessage(Settings.color(l));
    }

    private void alerts(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { msg(s, "&cPlayers only."); return; }
        boolean now = a.length > 1 ? a[1].equalsIgnoreCase("on") : !pl.vio().alertsEnabled(p.getUniqueId());
        pl.vio().setAlerts(p.getUniqueId(), now);
        msg(s, "Alerts " + (now ? "&aenabled" : "&cdisabled") + "&7.");
    }

    private void status(CommandSender s) {
        Settings st = pl.settings();
        int on = 0;
        for (CheckInfo c : pl.checks().all()) if (c.enabled) on++;
        msg(s, "&fStatus");
        s.sendMessage(Settings.color("&7TPS: &f" + String.format("%.1f", pl.ticks().tps()) + " &7(lag guard "
                + (pl.ticks().unstable() ? "&cactive" : "&aidle") + "&7)"));
        s.sendMessage(Settings.color("&7Checks enabled: &f" + on + "/" + pl.checks().all().size() + "&7, tracked players: &f" + pl.allData().size()));
        s.sendMessage(Settings.color("&7Punishment mode: &f" + st.punishMode + "&7, mitigation: &f" + st.mitigation + "&7, lag protection: &f" + st.lagProtection));
        s.sendMessage(Settings.color("&7Mini AI: " + (st.aiEnabled ? "&aon" : "&coff") + " &8" + pl.ai().status()));
    }

    private String topCheck(PlayerData pd) {
        String best = "";
        double v = 0;
        for (Map.Entry<String, Double> e : pd.vl.entrySet()) {
            if (e.getValue() > v) {
                v = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    private void profile(CommandSender s, String[] a) {
        if (a.length < 2) { msg(s, "&7Usage: /vyxac profile <player>"); return; }
        PlayerData pd = target(s, a[1]);
        if (pd == null) return;
        msg(s, "&f" + pd.name + " &7- ping &f" + pd.ping + "&7, flags &f" + pd.flagsTotal + "&7, strikes &f" + pd.strikes
                + "&7, AI &f" + String.format("%.0f%%", pd.aiConfidence * 100));
        if (pd.vl.isEmpty()) {
            s.sendMessage(Settings.color("&aNo violations."));
            return;
        }
        List<Map.Entry<String, Double>> list = new ArrayList<>(pd.vl.entrySet());
        list.sort(Comparator.comparingDouble((Map.Entry<String, Double> e) -> e.getValue()).reversed());
        for (Map.Entry<String, Double> e : list) {
            CheckInfo ci = pl.checks().find(e.getKey());
            String extra = ci != null && ci.punishable ? " &8(punish at " + (int) ci.punishVl + ")" : " &8(never punishes)";
            s.sendMessage(Settings.color("&7- &c" + e.getKey() + " &f" + String.format("%.1f", e.getValue()) + extra));
        }
    }

    private void history(CommandSender s, String[] a) {
        if (a.length < 2) { msg(s, "&7Usage: /vyxac history <player> [n]"); return; }
        PlayerData pd = target(s, a[1]);
        if (pd == null) return;
        int n = 10;
        if (a.length > 2) {
            try {
                n = Math.max(1, Math.min(50, Integer.parseInt(a[2])));
            } catch (NumberFormatException ignored) {
                // keep default
            }
        }
        msg(s, "&fLast flags of " + pd.name + ":");
        Iterator<PlayerData.FlagRecord> it = pd.history.descendingIterator();
        long now = System.currentTimeMillis();
        while (it.hasNext() && n-- > 0) {
            PlayerData.FlagRecord r = it.next();
            s.sendMessage(Settings.color("&8" + (now - r.time()) / 1000 + "s ago &c" + r.check() + " &7vl " + String.format("%.1f", r.vl()) + " &8- &7" + r.info()));
        }
    }

    private void clear(CommandSender s, String[] a) {
        if (a.length < 2) { msg(s, "&7Usage: /vyxac clear <player|all>"); return; }
        if (a[1].equalsIgnoreCase("all")) {
            for (PlayerData pd : pl.allData()) pl.punish().dismiss(pd);
            msg(s, "&aCleared all violations.");
            return;
        }
        PlayerData pd = target(s, a[1]);
        if (pd == null) return;
        pl.punish().dismiss(pd);
        msg(s, "&aCleared violations of &f" + pd.name + "&a.");
    }

    private void exempt(CommandSender s, String[] a) {
        if (a.length < 2) { msg(s, "&7Usage: /vyxac exempt <player> [seconds]"); return; }
        PlayerData pd = target(s, a[1]);
        if (pd == null) return;
        long sec = 300;
        if (a.length > 2) {
            try {
                sec = Long.parseLong(a[2]);
            } catch (NumberFormatException e) {
                msg(s, "&cInvalid number.");
                return;
            }
        }
        pd.exemptUntil = sec <= 0 ? 0 : System.currentTimeMillis() + sec * 1000L;
        pd.resetMotion();
        msg(s, sec <= 0 ? "&e" + pd.name + " &7is checked again." : "&e" + pd.name + " &7is exempt for &f" + sec + "s&7.");
    }

    private void check(CommandSender s, String[] a) {
        if (a.length < 2) {
            StringBuilder sb = new StringBuilder();
            for (CheckInfo c : pl.checks().all()) sb.append(c.enabled ? "&a" : "&c").append(c.name).append("&7, ");
            msg(s, "&fChecks: " + sb);
            return;
        }
        CheckInfo ci = pl.checks().find(a[1]);
        if (ci == null) { msg(s, "&cUnknown check. Use /vyxac check to list them."); return; }
        boolean val = a.length > 2 ? a[2].equalsIgnoreCase("on") || a[2].equalsIgnoreCase("true") : !ci.enabled;
        pl.setConfigValue("checks." + ci.name + ".enabled", val);
        msg(s, "&f" + ci.name + " &7is now " + (val ? "&aenabled" : "&cdisabled") + "&7. &8" + ci.description);
    }

    private static Object parse(String raw, Object existing) {
        if (raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("false")) return Boolean.parseBoolean(raw);
        try {
            if (existing instanceof Double || existing instanceof Float) return Double.parseDouble(raw);
            if (raw.matches("-?\\d+")) {
                long l = Long.parseLong(raw);
                return (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) ? (Object) (int) l : (Object) l;
            }
            if (raw.matches("-?\\d*\\.\\d+")) return Double.parseDouble(raw);
        } catch (NumberFormatException ignored) {
            // fall through to string
        }
        return raw;
    }

    private void configure(CommandSender s, String[] a) {
        FileConfiguration c = pl.getConfig();
        if (a.length < 2) {
            msg(s, "&fSections: &7general, lag, violations, alerts, punishments, features, ai, checks");
            msg(s, "&7Examples: &f/vyxac configure punishments.mode AUTO &7| &f/vyxac configure checks.Reach.params.tolerance 0.5");
            msg(s, "&7Use TAB to complete paths. List values (punishments.ladder) are edited in config.yml.");
            return;
        }
        String path = a[1];
        if (!c.contains(path)) { msg(s, "&cUnknown path: &f" + path); return; }
        if (c.isConfigurationSection(path)) {
            msg(s, "&f" + path + " &7contains: &f" + String.join(", ", c.getConfigurationSection(path).getKeys(false)));
            return;
        }
        Object cur = c.get(path);
        if (a.length == 2) {
            msg(s, "&f" + path + " &7= &b" + cur);
            return;
        }
        if (cur instanceof List) { msg(s, "&cLists cannot be changed here. Edit config.yml and run /vyxac reload."); return; }
        String raw = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
        Object val = parse(raw, cur);
        if (cur instanceof Boolean && !(val instanceof Boolean)) { msg(s, "&cThat value is true/false."); return; }
        if ((cur instanceof Number) && !(val instanceof Number)) { msg(s, "&cThat value is a number."); return; }
        if (path.equals("punishments.mode")) {
            String m = raw.toUpperCase(Locale.ROOT);
            if (!m.equals("ALERT_ONLY") && !m.equals("REVIEW") && !m.equals("AUTO")) { msg(s, "&cMode must be ALERT_ONLY, REVIEW or AUTO."); return; }
            val = m;
        }
        pl.setConfigValue(path, val);
        msg(s, "&f" + path + " &7set to &b" + val + "&7 (was &8" + cur + "&7).");
    }

    private void special(CommandSender s, String[] a) {
        FileConfiguration c = pl.getConfig();
        if (a.length < 2) {
            msg(s, "&fSpecial features &7(/vyxac special <feature> [value]):");
            for (Map.Entry<String, String> e : FEATURES.entrySet()) {
                s.sendMessage(Settings.color("&7- &b" + e.getKey() + " &8= &f" + c.get(e.getValue())));
            }
            return;
        }
        String path = FEATURES.get(a[1].toLowerCase(Locale.ROOT));
        if (path == null) { msg(s, "&cUnknown feature. Use /vyxac special to list them."); return; }
        Object cur = c.get(path);
        if (a.length == 2) {
            if (cur instanceof Boolean b) {
                pl.setConfigValue(path, !b);
                msg(s, "&f" + a[1] + " &7is now " + (!b ? "&aon" : "&coff") + "&7.");
            } else {
                msg(s, "&f" + a[1] + " &7= &b" + cur);
            }
            return;
        }
        String[] rest = new String[]{"configure", path, String.join(" ", Arrays.copyOfRange(a, 2, a.length))};
        configure(s, rest);
    }

    private void ai(CommandSender s, String[] a) {
        if (a.length >= 3 && a[1].equalsIgnoreCase("reset")) {
            PlayerData pd = target(s, a[2]);
            if (pd == null) return;
            pl.ai().resetPlayer(pd);
            msg(s, "&aAI statistics of &f" + pd.name + " &areset.");
            return;
        }
        msg(s, "&fMini AI &7(" + (pl.settings().aiEnabled ? "&aon" : "&coff") + "&7): &8" + pl.ai().status());
        List<PlayerData> list = new ArrayList<>(pl.allData());
        list.sort(Comparator.comparingDouble((PlayerData p) -> p.aiConfidence).reversed());
        int shown = 0;
        for (PlayerData pd : list) {
            if (pd.aiConfidence < 0.3 || shown++ >= 8) break;
            s.sendMessage(Settings.color("&7- &f" + pd.name + " &7confidence &c" + String.format("%.0f%%", pd.aiConfidence * 100)));
        }
        if (shown == 0) s.sendMessage(Settings.color("&7Nobody is above 30% confidence."));
    }

    private void diagnostics(CommandSender s, String[] a) {
        boolean last = a.length > 1 && a[1].equalsIgnoreCase("last");
        List<String> lines = last ? pl.diagnostics().lastReport() : pl.diagnostics().current();
        if (lines.isEmpty()) {
            msg(s, "&7No completed daily report yet. Use &b/vyxac diagnostics now &7for today so far.");
            return;
        }
        for (String l : lines.size() > 45 ? lines.subList(0, 45) : lines) s.sendMessage(Settings.color("&7" + l));
        msg(s, "&8Reports are saved in plugins/VyxAC/diagnostics/");
    }

    // ------------------------------------------------------------------ tab completion

    private static List<String> filter(List<String> all, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String x : all) if (x.toLowerCase(Locale.ROOT).startsWith(p)) out.add(x);
        return out;
    }

    private static List<String> players() {
        List<String> l = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) l.add(p.getName());
        return l;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        if (!s.hasPermission("vyxac.admin")) return a.length == 1 ? filter(List.of("alerts"), a[0]) : new ArrayList<>();
        if (a.length == 1) return filter(SUBS, a[0]);
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (a.length == 2) {
            switch (sub) {
                case "profile", "history", "exempt", "punish", "dismiss", "tp" -> {
                    return filter(players(), a[1]);
                }
                case "clear" -> {
                    List<String> l = players();
                    l.add("all");
                    return filter(l, a[1]);
                }
                case "alerts" -> {
                    return filter(List.of("on", "off"), a[1]);
                }
                case "check" -> {
                    List<String> l = new ArrayList<>();
                    for (CheckInfo c : pl.checks().all()) l.add(c.name);
                    return filter(l, a[1]);
                }
                case "special" -> {
                    return filter(new ArrayList<>(FEATURES.keySet()), a[1]);
                }
                case "ai" -> {
                    return filter(List.of("status", "reset"), a[1]);
                }
                case "diagnostics", "diagnose" -> {
                    return filter(List.of("now", "last"), a[1]);
                }
                case "configure" -> {
                    List<String> l = new ArrayList<>();
                    FileConfiguration c = pl.getConfig();
                    for (String k : c.getKeys(true)) if (!c.isConfigurationSection(k)) l.add(k);
                    return filter(l, a[1]);
                }
                default -> {
                    return new ArrayList<>();
                }
            }
        }
        if (a.length == 3) {
            if (sub.equals("check")) return filter(List.of("on", "off"), a[2]);
            if (sub.equals("ai") && a[1].equalsIgnoreCase("reset")) return filter(players(), a[2]);
            if (sub.equals("special") && a[1].equalsIgnoreCase("mode")) return filter(List.of("ALERT_ONLY", "REVIEW", "AUTO"), a[2]);
            if (sub.equals("special")) return filter(List.of("true", "false"), a[2]);
            if (sub.equals("configure")) {
                if (a[1].equals("punishments.mode")) return filter(List.of("ALERT_ONLY", "REVIEW", "AUTO"), a[2]);
                Object cur = pl.getConfig().get(a[1]);
                if (cur instanceof Boolean) return filter(List.of("true", "false"), a[2]);
            }
        }
        return new ArrayList<>();
    }
}
