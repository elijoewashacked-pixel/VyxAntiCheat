package com.vyxac;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * /vyxac test: checks that the pieces VyxAC depends on really work, WITHOUT punishing or flagging anyone.
 * Alerts, Discord webhook and the punishment ladder are exercised as a dry run.
 */
public final class SelfTest {
    private static final String OK = "&a[OK]", WARN = "&e[WARN]", INFO = "&b[INFO]";

    private final VyxAC pl;

    public SelfTest(VyxAC pl) {
        this.pl = pl;
    }

    private void line(CommandSender s, String tag, String text) {
        s.sendMessage(Settings.color(tag + " &7" + text));
    }

    public void run(CommandSender s) {
        Settings st = pl.settings();
        s.sendMessage(Settings.color("&fVyxAC self-test &8(nobody is punished or flagged)"));

        int on = 0;
        List<String> off = new ArrayList<>();
        for (CheckInfo c : pl.checks().all()) {
            if (c.enabled) on++;
            else off.add(c.name);
        }
        line(s, OK, "Config loaded: " + pl.checks().all().size() + " checks, " + on + " enabled, punishment mode " + st.punishMode + ".");
        if (st.punishMode.equals("AUTO")) line(s, WARN, "AUTO mode: players can be punished without a staff review.");
        if (st.punishMode.equals("ALERT_ONLY")) line(s, INFO, "ALERT_ONLY: nobody is ever punished, alerts only.");
        if (!off.isEmpty()) line(s, INFO, "Disabled checks: " + String.join(", ", off));

        line(pl.ticks().unstable() ? WARN : OK, s, String.format("Server TPS %.1f (lag guard %s).", pl.ticks().tps(), pl.ticks().unstable() ? "ACTIVE: flags are being ignored" : "idle"));
        line(s, INFO, "Cancel flagged hits/actions: " + (st.mitigation ? "on" : "off") + ", movement setbacks: " + (st.mitigateMovement ? "on" : "off") + ".");

        // alert delivery
        pl.vio().testAlert(pl.checks().get("Reach"), "simulated flag: nothing was recorded", s.getName());
        line(s, OK, "A test alert was sent to the console and to every staff member with alerts on.");
        if (s instanceof Player p) {
            if (!p.hasPermission("vyxac.alerts")) line(s, WARN, "You do not have the vyxac.alerts permission, so you will not see real alerts.");
            else if (!pl.vio().alertsEnabled(p.getUniqueId())) line(s, WARN, "Your alerts are switched off: run /vyxac alerts on.");
        }

        // discord
        if (pl.vio().webhookConfigured()) {
            pl.vio().webhookNow("VyxAC test message from " + s.getName() + ": if you can read this, the webhook works.");
            line(s, OK, "Discord webhook: test message sent. Check your channel; if nothing arrives the URL is wrong or was deleted.");
        } else {
            line(s, INFO, "Discord webhook is not configured (/vyxac special webhook <url>).");
        }

        // ladder dry run
        if (st.ladder.isEmpty()) {
            line(s, WARN, "The punishment ladder is empty: punishing only kicks the player.");
        } else {
            for (int i = 0; i < st.ladder.size(); i++) {
                line(s, INFO, "Strike " + (i + 1) + " would run: &f" + Settings.color(st.ladder.get(i).replace("%player%", "TestPlayer").replace("%check%", "Reach").replace("%vl%", "12")) + " &8(not executed)");
            }
        }

        line(s, INFO, "Mini AI: " + (st.aiEnabled ? pl.ai().status() : "off"));
        line(s, INFO, "Reports: " + pl.reports().openCount() + " open. Staff in staff mode: " + pl.staff().staffModeNames().size() + ".");
        s.sendMessage(Settings.color("&7More: &b/vyxac test alert&7, &btest webhook&7, &btest flag <check>&7, &btest punish <player>"));
    }

    // argument order helper so the ternary above reads naturally
    private void line(String tag, CommandSender s, String text) {
        line(s, tag, text);
    }

    public void alert(CommandSender s) {
        pl.vio().testAlert(pl.checks().get("KillAura"), "simulated flag: nothing was recorded", s.getName());
        line(s, OK, "Test alert sent. If you did not see it: you need vyxac.alerts and /vyxac alerts on.");
    }

    public void webhook(CommandSender s) {
        if (!pl.vio().webhookConfigured()) {
            line(s, WARN, "No webhook configured. Use /vyxac special webhook <url>.");
            return;
        }
        pl.vio().webhookNow("VyxAC webhook test from " + s.getName());
        line(s, OK, "Webhook test sent. Check your Discord channel.");
    }

    public void flag(CommandSender s, String check) {
        CheckInfo ci = pl.checks().find(check);
        if (ci == null) {
            line(s, WARN, "Unknown check. Use /vyxac check to list them.");
            return;
        }
        pl.vio().testAlert(ci, "simulated flag: nothing was recorded", s.getName());
        line(s, OK, "Simulated a " + ci.name + " alert" + (ci.enabled ? "." : " (note: this check is currently disabled)."));
    }

    public void punish(CommandSender s, String player) {
        Player p = Bukkit.getPlayerExact(player);
        if (p == null) {
            line(s, WARN, "Player is not online.");
            return;
        }
        PlayerData pd = pl.data(p);
        List<String> ladder = pl.settings().ladder;
        if (ladder.isEmpty()) {
            line(s, INFO, "The ladder is empty: the player would just be kicked.");
            return;
        }
        int step = Math.min(pd.strikes, ladder.size() - 1);
        String cmd = Settings.color(ladder.get(step).replace("%player%", p.getName()).replace("%check%", "Reach").replace("%vl%", String.format("%.0f", pd.totalVl())));
        line(s, INFO, p.getName() + " has " + pd.strikes + " strike(s). The next punishment would run: &f" + cmd + " &8(NOT executed)");
    }
}
