package com.vyxac.hacks;

import com.vyxac.VyxAC;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** /checkhacks, /chalerts, /cheditor, /checkhacksupdate, /checklang */
public final class HackCommands implements CommandExecutor, TabCompleter {
    private final VyxAC pl;

    public HackCommands(VyxAC pl) {
        this.pl = pl;
    }

    private static boolean can(CommandSender s, String perm) {
        return s.hasPermission(perm) || s.hasPermission("checkhacks.admin");
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        HackChecker h = pl.hacks();
        if (h == null) {
            s.sendMessage(HackText.color("<red>CheckHacks did not start. See the console."));
            return true;
        }
        HackText t = h.text();
        try {
            switch (cmd.getName().toLowerCase(Locale.ROOT)) {
                case "checkhacks" -> {
                    if (!can(s, "checkhacks.use")) s.sendMessage(t.prefixed("no-permission"));
                    else checkhacks(s, a, h, t);
                }
                case "chalerts" -> {
                    if (!(s instanceof Player p)) s.sendMessage("Players only.");
                    else if (!can(s, "checkhacks.alerts")) s.sendMessage(t.prefixed("no-permission"));
                    else s.sendMessage(t.prefixed(h.toggleAlerts(p) ? "alerts-on" : "alerts-off"));
                }
                case "cheditor" -> {
                    if (!can(s, "checkhacks.editor")) s.sendMessage(t.prefixed("no-permission"));
                    else editor(s, h, t);
                }
                case "checkhacksupdate" -> {
                    if (!can(s, "checkhacks.update")) s.sendMessage(t.prefixed("no-permission"));
                    else h.updater().download(s);
                }
                case "checklang" -> {
                    if (!can(s, "checkhacks.use")) s.sendMessage(t.prefixed("no-permission"));
                    else if (a.length < 1) s.sendMessage(t.prefixed("usage"));
                    else {
                        Player target = Bukkit.getPlayerExact(a[0]);
                        if (target == null) s.sendMessage(t.prefixed("player-not-found", "player", a[0]));
                        else h.checkLanguage(target, s);
                    }
                }
                default -> { }
            }
        } catch (Throwable ex) {
            s.sendMessage(HackText.color("<red>Error: " + ex.getMessage()));
            pl.getLogger().warning("CheckHacks command error: " + ex);
        }
        return true;
    }

    private List<String> listFor(HackChecker h, HackChecker.Reason r) {
        HackConfig c = h.cfg();
        return r == HackChecker.Reason.JOIN ? c.listJoin : (r == HackChecker.Reason.ANTICHEAT ? c.listAnticheat : c.listNormal);
    }

    private void checkhacks(CommandSender s, String[] a, HackChecker h, HackText t) {
        if (a.length == 0) {
            s.sendMessage(t.prefixed("usage"));
            return;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                s.sendMessage(t.prefixed("list-header"));
                for (ModDef m : h.cfg().mods.values()) {
                    boolean on = m.enabled && !m.probes.isEmpty();
                    s.sendMessage(HackText.color((on ? "<green>" : "<red>") + m.id + " <gray>- <white>" + m.name + " <dark_gray>(" + m.category + ", "
                            + m.probes.size() + " probe" + (m.probes.size() == 1 ? "" : "s") + (m.verified ? ", verified" : ", not verified yet") + ")"));
                }
            }
            case "status" -> {
                HackConfig c = h.cfg();
                s.sendMessage(HackText.color("<gray>CheckHacks: " + (h.available() ? "<green>ready" : "<red>not active: <white>" + h.unavailableReason())));
                s.sendMessage(HackText.color("<gray>Mods with a probe: <white>" + h.select(List.of("all")).size() + " <gray>| DoubleCheck: <white>" + c.doublecheck
                        + " <gray>| storage: <white>" + (h.store().sql() ? "SQLite" : "memory only")));
                s.sendMessage(HackText.color("<gray>Web editor: <white>" + (h.web().running() ? "running on port " + c.webPort : "off")
                        + " <gray>| updater: <white>" + (c.updaterEnabled ? (h.updater().hasUpdate() ? "update " + h.updater().latest() + " available" : "up to date") : "off")));
            }
            case "reload" -> {
                if (!can(s, "checkhacks.admin")) {
                    s.sendMessage(t.prefixed("no-permission"));
                    return;
                }
                h.reload();
                s.sendMessage(t.prefixed("reloaded"));
            }
            case "probe" -> {
                if (!can(s, "checkhacks.admin")) {
                    s.sendMessage(t.prefixed("no-permission"));
                    return;
                }
                if (a.length < 4) {
                    s.sendMessage(HackText.color("<gray>Usage: <white>/checkhacks probe <player> <KEYBIND|TRANSLATE> <key>"));
                    return;
                }
                Player target = Bukkit.getPlayerExact(a[1]);
                if (target == null) {
                    s.sendMessage(t.prefixed("player-not-found", "player", a[1]));
                    return;
                }
                String mode = a[2].equalsIgnoreCase("TRANSLATE") ? "TRANSLATE" : "KEYBIND";
                h.probe(s, target, mode, a[3]);
            }
            case "history" -> {
                if (a.length < 2) {
                    s.sendMessage(t.prefixed("usage"));
                    return;
                }
                List<ScanResult> l = h.store().player(a[1], 10);
                if (l.isEmpty()) {
                    s.sendMessage(t.prefixed("no-history"));
                    return;
                }
                SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm");
                for (ScanResult r : l) {
                    String bad = String.join(", ", r.names(ProbeEval.Result.DETECTED));
                    String prot = String.join(", ", r.names(ProbeEval.Result.PROTECTED));
                    s.sendMessage(HackText.color("<dark_gray>" + f.format(new Date(r.ts)) + " <gray>" + r.reason + " <dark_gray>by " + r.starter + " <gray>- "
                            + (bad.isEmpty() ? "" : "<red>" + bad + " ") + (prot.isEmpty() ? "" : "<yellow>protected: " + prot + " ") + (bad.isEmpty() && prot.isEmpty() ? "<green>clean" : "")));
                }
            }
            default -> {
                Player target = Bukkit.getPlayerExact(a[0]);
                if (target == null) {
                    s.sendMessage(t.prefixed("player-not-found", "player", a[0]));
                    return;
                }
                List<String> tokens = new ArrayList<>();
                HackChecker.Reason reason = HackChecker.Reason.MANUAL;
                for (int i = 1; i < a.length; i++) {
                    if (a[i].equalsIgnoreCase("-join")) reason = HackChecker.Reason.JOIN;
                    else if (a[i].equalsIgnoreCase("-ac")) reason = HackChecker.Reason.ANTICHEAT;
                    else tokens.add(a[i]);
                }
                List<ModDef> mods = h.select(tokens.isEmpty() ? listFor(h, reason) : tokens);
                h.check(s, s.getName(), target, reason, mods);
            }
        }
    }

    private void editor(CommandSender s, HackChecker h, HackText t) {
        if (!h.cfg().webEnabled || !h.web().running()) {
            s.sendMessage(t.prefixed("editor-disabled"));
            return;
        }
        String url = h.web().urlFor(h.web().newToken());
        String msg = t.prefixed("editor-link", "minutes", Integer.toString(h.cfg().tokenMinutes), "url", url);
        if (s instanceof Player p) {
            TextComponent tc = new TextComponent(TextComponent.fromLegacyText(msg));
            tc.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
            tc.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("Open the web editor")));
            p.spigot().sendMessage(tc);
        } else {
            s.sendMessage(msg);
        }
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
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        HackChecker h = pl.hacks();
        if (h == null) return new ArrayList<>();
        if (name.equals("checklang")) return a.length == 1 ? filter(players(), a[0]) : new ArrayList<>();
        if (!name.equals("checkhacks")) return new ArrayList<>();
        if (a.length == 1) {
            List<String> l = players();
            l.addAll(Arrays.asList("list", "status", "history", "probe", "reload"));
            return filter(l, a[0]);
        }
        String first = a[0].toLowerCase(Locale.ROOT);
        if (first.equals("history")) return a.length == 2 ? filter(players(), a[1]) : new ArrayList<>();
        if (first.equals("probe")) {
            if (a.length == 2) return filter(players(), a[1]);
            if (a.length == 3) return filter(Arrays.asList("KEYBIND", "TRANSLATE"), a[2]);
            return new ArrayList<>();
        }
        List<String> l = new ArrayList<>(Arrays.asList("all", "-join", "-ac"));
        for (ModDef m : h.cfg().mods.values()) {
            l.add(m.id);
            if (!l.contains(m.category)) l.add(m.category);
        }
        return filter(l, a[a.length - 1]);
    }
}
