package com.vyxac;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /vac report, /vyxac reports, /vyxac staff ..., /vyxac test ... */
public final class ExtraCommands {
    private static final List<String> STAFF_SUBS = Arrays.asList("mode", "vanish", "freeze", "unfreeze", "frozen", "inspect",
            "players", "list", "chat", "note", "notes", "rtp");

    private final VyxAC pl;
    private final StaffGui gui;
    private final SelfTest test;

    public ExtraCommands(VyxAC pl, StaffGui gui, SelfTest test) {
        this.pl = pl;
        this.gui = gui;
        this.test = test;
    }

    private void msg(CommandSender s, String t) {
        s.sendMessage(Settings.color(pl.settings().prefix + t));
    }

    // ===================================================================================== /vac report

    public void report(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) {
            msg(s, "&cOnly players can send reports.");
            return;
        }
        if (a.length >= 2 && a[1].equalsIgnoreCase("list")) {
            List<Report> l = pl.reports().byReporter(p.getUniqueId());
            if (l.isEmpty()) {
                msg(s, "&7You have not sent any reports.");
                return;
            }
            msg(s, "&fYour latest reports:");
            for (int i = 0; i < Math.min(8, l.size()); i++) {
                Report r = l.get(i);
                s.sendMessage(Settings.color("&7#" + r.id + " &f" + r.targetName + " &8- &7" + r.status.toLowerCase(Locale.ROOT)));
            }
            return;
        }
        if (a.length < 3) {
            msg(s, "&7Usage: &f/vac report <player> <reason> &7or &f/vac report list");
            return;
        }
        String err = pl.reports().submit(p, a[1], String.join(" ", Arrays.copyOfRange(a, 2, a.length)));
        if (err != null) s.sendMessage(err);
    }

    // ===================================================================================== /vyxac reports

    public void reports(CommandSender s, String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("view") && a.length >= 3) {
            int id;
            try {
                id = Integer.parseInt(a[2].replace("#", ""));
            } catch (NumberFormatException e) {
                msg(s, "&7Usage: /vyxac reports view <id>");
                return;
            }
            Report r = pl.reports().get(id);
            if (r == null) {
                msg(s, "&cNo report with id " + id + ".");
                return;
            }
            if (s instanceof Player p) {
                gui.openReport(p, id);
            } else {
                msg(s, "#" + r.id + " " + r.reporterName + " -> " + r.targetName + " [" + r.status + "]: " + r.reason + " | " + r.snapshot);
            }
            return;
        }
        if (s instanceof Player p && !sub.equals("list")) {
            gui.openReports(p, sub.equals("closed"), "", 0);
            return;
        }
        List<Report> l = sub.equals("closed") ? pl.reports().closed(20) : pl.reports().open();
        if (l.isEmpty()) {
            msg(s, "&7No " + (sub.equals("closed") ? "closed" : "open") + " reports.");
            return;
        }
        msg(s, "&f" + (sub.equals("closed") ? "Closed" : "Open") + " reports:");
        for (int i = 0; i < Math.min(15, l.size()); i++) {
            Report r = l.get(i);
            s.sendMessage(Settings.color("&e#" + r.id + " &7" + r.reporterName + " &8-> &c" + r.targetName + " &8[" + r.status + "] &f" + r.reason));
        }
    }

    // ===================================================================================== /vyxac staff

    private Player online(CommandSender s, String name) {
        Player t = Bukkit.getPlayerExact(name);
        if (t == null) msg(s, "&cPlayer &f" + name + " &cis not online.");
        return t;
    }

    public void staff(CommandSender s, String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "mode";
        StaffManager st = pl.staff();
        Player me = s instanceof Player pp ? pp : null;
        boolean needsPlayer = !(sub.equals("list") || sub.equals("frozen") || sub.equals("notes") || sub.equals("note")
                || (sub.equals("chat") && a.length > 2));
        if (needsPlayer && me == null) {
            msg(s, "&cThat is for players in game.");
            return;
        }
        switch (sub) {
            case "mode", "toggle" -> st.toggle(me);
            case "on" -> {
                if (!st.enter(me)) msg(s, "&7You are already in staff mode.");
            }
            case "off" -> {
                if (!st.exit(me, false)) msg(s, "&7You are not in staff mode.");
            }
            case "vanish", "v" -> st.toggleVanish(me);
            case "rtp" -> st.randomTeleport(me);
            case "players" -> gui.openPlayers(me, 0, false);
            case "list" -> {
                List<String> l = st.staffModeNames();
                msg(s, "&fStaff in staff mode: &7" + (l.isEmpty() ? "nobody" : String.join(", ", l)));
            }
            case "frozen" -> {
                List<String> l = st.frozenNames();
                msg(s, "&fFrozen players: &7" + (l.isEmpty() ? "nobody" : String.join(", ", l)));
            }
            case "freeze" -> {
                if (a.length < 3) {
                    msg(s, "&7Usage: /vyxac staff freeze <player>");
                    return;
                }
                Player t = online(s, a[2]);
                if (t == null) return;
                if (st.isFrozen(t.getUniqueId())) msg(s, "&7Already frozen. Use /vyxac staff unfreeze " + t.getName());
                else st.toggleFreeze(t, me);
            }
            case "unfreeze" -> {
                if (a.length < 3) {
                    msg(s, "&7Usage: /vyxac staff unfreeze <player>");
                    return;
                }
                Player t = online(s, a[2]);
                if (t == null) return;
                if (!st.isFrozen(t.getUniqueId())) msg(s, "&7That player is not frozen.");
                else st.toggleFreeze(t, me);
            }
            case "inspect" -> {
                if (a.length < 3) {
                    msg(s, "&7Usage: /vyxac staff inspect <player>");
                    return;
                }
                Player t = online(s, a[2]);
                if (t != null) gui.openInspect(me, t.getUniqueId());
            }
            case "chat", "c" -> {
                if (a.length > 2) st.staffChat(s.getName(), String.join(" ", Arrays.copyOfRange(a, 2, a.length)));
                else st.toggleChat(me);
            }
            case "note" -> {
                if (a.length < 4) {
                    msg(s, "&7Usage: /vyxac staff note <player> <text>");
                    return;
                }
                OfflinePlayer op = resolve(a[2]);
                if (op == null) {
                    msg(s, "&cUnknown player.");
                    return;
                }
                st.addNote(op.getUniqueId(), op.getName() == null ? a[2] : op.getName(), s.getName(), String.join(" ", Arrays.copyOfRange(a, 3, a.length)));
                msg(s, "&aNote saved.");
            }
            case "notes" -> {
                if (a.length < 3) {
                    msg(s, "&7Usage: /vyxac staff notes <player>");
                    return;
                }
                OfflinePlayer op = resolve(a[2]);
                if (op == null) {
                    msg(s, "&cUnknown player.");
                    return;
                }
                List<String[]> notes = st.notes(op.getUniqueId());
                msg(s, "&fNotes for " + (op.getName() == null ? a[2] : op.getName()) + " &7(" + notes.size() + ")");
                for (String[] n : notes) s.sendMessage(Settings.color("&b" + n[1] + "&7: &f" + n[2]));
            }
            default -> {
                msg(s, "&fStaff commands:");
                for (String l : new String[]{
                        "&b/vyxac staff &7- staff mode: vanish, flight, hotbar tools",
                        "&b/vyxac staff vanish &7- toggle vanish only",
                        "&b/vyxac staff freeze|unfreeze <player> &7- freeze a suspect",
                        "&b/vyxac staff inspect <player> &7- violations, AI, notes",
                        "&b/vyxac staff players &7- online player list",
                        "&b/vyxac staff chat [message] &7- staff-only chat",
                        "&b/vyxac staff note|notes <player> [text] &7- private notes",
                        "&b/vyxac staff rtp &7- teleport to a random player",
                        "&b/vyxac reports &7- open the report tickets"}) {
                    s.sendMessage(Settings.color(l));
                }
            }
        }
    }

    private OfflinePlayer resolve(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) return p;
        OfflinePlayer op = Bukkit.getOfflinePlayer(name);
        return op.hasPlayedBefore() ? op : null;
    }

    // ===================================================================================== /vyxac preset

    /** One command to make Reach / Hitbox / Backtrack stricter or more relaxed. */
    public void preset(CommandSender s, String[] a) {
        String name = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        double reachTol, reachAvg, hitExpand, backExtra;
        int reachBuf, hitBuf, hitAngle, rewind;
        switch (name) {
            case "strict" -> {
                reachTol = 0.2; reachBuf = 1; reachAvg = 2.9;
                hitExpand = 0.1; hitBuf = 1; hitAngle = 8;
                backExtra = 80; rewind = 80;
            }
            case "normal", "balanced" -> {
                reachTol = 0.3; reachBuf = 1; reachAvg = 3.0;
                hitExpand = 0.15; hitBuf = 2; hitAngle = 10;
                backExtra = 120; rewind = 100;
            }
            case "relaxed", "lenient" -> {
                reachTol = 0.55; reachBuf = 2; reachAvg = 3.15;
                hitExpand = 0.4; hitBuf = 3; hitAngle = 20;
                backExtra = 250; rewind = 150;
            }
            default -> {
                msg(s, "&7Usage: &f/vyxac preset <strict|normal|relaxed>");
                msg(s, "&7strict: flags the most (more false flags with laggy players)");
                msg(s, "&7normal: the default");
                msg(s, "&7relaxed: flags less, safest for high-ping servers");
                return;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checks.Reach.params.tolerance", reachTol);
        m.put("checks.Reach.params.buffer", reachBuf);
        m.put("checks.Reach.params.avg-limit", reachAvg);
        m.put("checks.Hitbox.params.expand", hitExpand);
        m.put("checks.Hitbox.params.buffer", hitBuf);
        m.put("checks.Hitbox.params.min-angle", hitAngle);
        m.put("checks.Backtrack.params.extra-ms", backExtra);
        m.put("general.rewind-extra-ms", rewind);
        pl.setConfigValues(m);
        msg(s, "&aCombat preset &f" + name + " &aapplied: Reach tolerance " + reachTol + ", Hitbox expand " + hitExpand + ", lag window ping+" + rewind + "ms.");
    }

    // ===================================================================================== /vyxac test

    public void test(CommandSender s, String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "alert" -> test.alert(s);
            case "webhook" -> test.webhook(s);
            case "flag" -> {
                if (a.length < 3) msg(s, "&7Usage: /vyxac test flag <check>");
                else test.flag(s, a[2]);
            }
            case "punish" -> {
                if (a.length < 3) msg(s, "&7Usage: /vyxac test punish <player>");
                else test.punish(s, a[2]);
            }
            default -> test.run(s);
        }
    }

    // ===================================================================================== tab completion

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

    public List<String> tab(CommandSender s, String[] a) {
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "report" -> {
                if (a.length == 2) {
                    List<String> l = players();
                    l.add("list");
                    return filter(l, a[1]);
                }
            }
            case "reports" -> {
                if (a.length == 2) return filter(Arrays.asList("view", "closed", "list"), a[1]);
                if (a.length == 3 && a[1].equalsIgnoreCase("view")) {
                    List<String> ids = new ArrayList<>();
                    for (Report r : pl.reports().open()) ids.add(Integer.toString(r.id));
                    return filter(ids, a[2]);
                }
            }
            case "staff" -> {
                if (a.length == 2) return filter(STAFF_SUBS, a[1]);
                if (a.length == 3 && Arrays.asList("freeze", "unfreeze", "inspect", "note", "notes").contains(a[1].toLowerCase(Locale.ROOT))) {
                    return filter(players(), a[2]);
                }
            }
            case "test" -> {
                if (a.length == 2) return filter(Arrays.asList("alert", "webhook", "flag", "punish"), a[1]);
                if (a.length == 3 && a[1].equalsIgnoreCase("flag")) {
                    List<String> l = new ArrayList<>();
                    for (CheckInfo c : pl.checks().all()) l.add(c.name);
                    return filter(l, a[2]);
                }
                if (a.length == 3 && a[1].equalsIgnoreCase("punish")) return filter(players(), a[2]);
            }
            default -> {
                return new ArrayList<>();
            }
        }
        return new ArrayList<>();
    }

}
