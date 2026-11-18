package com.vyxac;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Decides when evidence is strong enough to punish. A single noisy check never causes a punishment:
 * several different checks must have reached their own punish-VL inside a time window, and the server and
 * the player's connection must be healthy. Default mode is REVIEW (staff decides with one click).
 */
public final class PunishManager {
    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9_.]{1,32}$");

    private final VyxAC pl;

    public PunishManager(VyxAC pl) {
        this.pl = pl;
    }

    /** Summary of the evidence collected against one player. */
    private static final class Evidence {
        int distinct, flags;
        String top = "";
        double topVl;
        boolean ai;
        boolean enough;
    }

    private Evidence gather(PlayerData pd, long now) {
        Settings s = pl.settings();
        Evidence ev = new Evidence();
        long window = s.punishWindowSec * 1000L;
        Map<String, Integer> counts = new HashMap<>();
        Iterator<PlayerData.FlagRecord> it = pd.history.descendingIterator();
        while (it.hasNext()) {
            PlayerData.FlagRecord r = it.next();
            if (now - r.time() > window) break;
            if (r.punishable()) counts.merge(r.check(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            CheckInfo ci = pl.checks().find(e.getKey());
            if (ci == null || !ci.enabled || !ci.punishable) continue;
            double vl = pd.vl.getOrDefault(ci.name, 0.0);
            if (vl < ci.punishVl) continue; // this check alone has not reached its own threshold
            ev.distinct++;
            ev.flags += e.getValue();
            if (vl > ev.topVl) {
                ev.topVl = vl;
                ev.top = ci.name;
            }
        }
        if (ev.distinct >= 1 && pl.settings().aiEnabled && pd.aiConfidence >= s.punishAiConf && now - pd.aiConfAt < 60_000L) {
            ev.ai = true;
            ev.distinct++;
        }
        ev.enough = ev.distinct >= s.minDistinct && ev.flags >= s.minFlags;
        return ev;
    }

    /** true when the player and server are in a state where punishing would be safe */
    private boolean healthy(PlayerData pd, long now) {
        Settings s = pl.settings();
        if (pd.player == null || !pd.player.isOnline()) return false;
        if (pd.bypassed(now, s)) return false;
        if (now - pd.joinMs < s.punishGraceMs) return false;
        if (now - pd.lastPunishMs < s.punishCooldownMs) return false;
        if (pd.ping > s.punishMaxPing) return false;
        if (pl.ticks().unstable()) return false;
        return true;
    }

    /** Called after every flag of a punishable check. */
    public void evaluate(PlayerData pd) {
        Settings s = pl.settings();
        if (s.punishMode.equals("ALERT_ONLY")) return;
        long now = System.currentTimeMillis();
        if (!healthy(pd, now)) return;
        Evidence ev = gather(pd, now);
        if (!ev.enough) return;

        if (s.punishMode.equals("AUTO")) {
            if (pd.punishScheduled) return;
            pd.punishScheduled = true;
            final PlayerData target = pd;
            Bukkit.getScheduler().runTaskLater(pl, () -> {
                target.punishScheduled = false;
                long t = System.currentTimeMillis();
                if (!healthy(target, t)) return;
                Evidence again = gather(target, t); // evidence must still hold after the delay
                if (again.enough) punish(target, again.top, false);
            }, Math.max(1, s.autoDelayTicks));
        } else { // REVIEW
            if (now - pd.reviewPromptAt < s.reviewCooldownMs) return;
            pd.reviewPromptAt = now;
            pl.diagnostics().onReview();
            review(pd, ev);
        }
    }

    private void review(PlayerData pd, Evidence ev) {
        String plain = Settings.color(pl.settings().prefix + "&f" + pd.name + " &7looks suspicious: &c" + ev.top
                + " &7(VL " + String.format("%.1f", ev.topVl) + ", " + ev.distinct + " checks" + (ev.ai ? " + AI" : "")
                + ", ping " + pd.ping + ")");
        TextComponent line = new TextComponent(TextComponent.fromLegacyText(plain + " "));
        line.addExtra(button("&a[Punish]", "&7Run the punishment ladder for " + pd.name, "/vyxac punish " + pd.name));
        line.addExtra(new TextComponent(" "));
        line.addExtra(button("&6[Kick]", "&7Kick " + pd.name + " from the server", "/vyxac kick " + pd.name));
        line.addExtra(new TextComponent(" "));
        line.addExtra(button("&e[Dismiss]", "&7Clear all violations of " + pd.name, "/vyxac dismiss " + pd.name));
        line.addExtra(new TextComponent(" "));
        line.addExtra(button("&b[TP]", "&7Teleport to " + pd.name, "/vyxac tp " + pd.name));
        pl.vio().toStaff(line, ChatColor.stripColor(plain));
    }

    private TextComponent button(String label, String hover, String cmd) {
        TextComponent b = new TextComponent(TextComponent.fromLegacyText(Settings.color(label)));
        b.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(Settings.color(hover))));
        b.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd));
        return b;
    }

    /** Runs the next step of the punishment ladder. Safe to call from commands (main thread). */
    public boolean punish(PlayerData pd, String check, boolean manual) {
        Settings s = pl.settings();
        if (!SAFE_NAME.matcher(pd.name).matches()) return false;
        if (s.ladder.isEmpty()) {
            if (pd.player != null) pd.player.kickPlayer(Settings.color("&c[VyxAC] Unfair advantage detected"));
            return true;
        }
        long now = System.currentTimeMillis();
        int step = Math.min(pd.strikes, s.ladder.size() - 1);
        String cmd = Settings.color(s.ladder.get(step)
                .replace("%player%", pd.name)
                .replace("%check%", check == null || check.isEmpty() ? "VyxAC" : check)
                .replace("%vl%", String.format("%.0f", pd.totalVl())));
        pd.strikes++;
        pl.diagnostics().onPunish();
        pd.lastPunishMs = now;
        String line = "PUNISH " + pd.name + " step=" + (step + 1) + (manual ? " (manual)" : " (" + s.punishMode + ")") + " cmd=" + cmd;
        pl.vio().log(line);
        pd.clearViolations();
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        pl.getLogger().info(ChatColor.stripColor(line));
        return true;
    }

    public void dismiss(PlayerData pd) {
        pl.diagnostics().onDismiss();
        pd.clearViolations();
        pd.reviewPromptAt = 0;
    }

    public static Player online(String name) {
        return Bukkit.getPlayerExact(name);
    }
}
