package com.vyxac.hacks;

import com.vyxac.VyxAC;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Automatic checks on join, the language check, and the update notice. */
public final class HackListener implements Listener {
    private final VyxAC pl;

    public HackListener(VyxAC pl) {
        this.pl = pl;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        final HackChecker h = pl.hacks();
        if (h == null) return;
        final Player p = e.getPlayer();
        final HackConfig c = h.cfg();
        final boolean returning = p.hasPlayedBefore();
        if (c.autoJoin && !(c.autoJoinFirstOnly && returning)) {
            Bukkit.getScheduler().runTaskLater(pl, () -> h.trigger(p, HackChecker.Reason.JOIN, "join"), c.joinDelaySec * 20L);
        }
        if (c.langJoin && !(c.langFirstOnly && returning)) {
            Bukkit.getScheduler().runTaskLater(pl, () -> {
                if (p.isOnline()) h.checkLanguage(p, null);
            }, 60L);
        }
        if (h.updater().hasUpdate() && (p.hasPermission("checkhacks.update") || p.hasPermission("checkhacks.admin"))) {
            Bukkit.getScheduler().runTaskLater(pl, () -> {
                if (!p.isOnline()) return;
                TextComponent tc = new TextComponent(TextComponent.fromLegacyText(h.text().prefixed("update-available", "version", h.updater().latest())));
                tc.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/checkhacksupdate"));
                tc.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("/checkhacksupdate")));
                p.spigot().sendMessage(tc);
            }, 50L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        HackChecker h = pl.hacks();
        if (h != null) h.cancelPlayer(e.getPlayer().getUniqueId());
    }
}
