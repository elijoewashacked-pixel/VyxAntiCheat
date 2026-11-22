package com.vyxac.hacks;

import com.vyxac.VyxAC;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * When another anticheat flags a player, that player is checked right away. The hook listens to the other plugin's Bukkit
 * event through reflection, so nothing has to be installed and a wrong class name can never break the server.
 * Anticheats without a Bukkit event (Grim) can run  checkhacks %player% -ac  as one of their punishment commands.
 */
public final class AnticheatHooks {
    private final VyxAC pl;
    private final HackChecker checker;
    private final Set<String> done = new HashSet<>();
    private final Listener holder = new Listener() { };

    public AnticheatHooks(VyxAC pl, HackChecker checker) {
        this.pl = pl;
        this.checker = checker;
    }

    public void register(HackConfig c) {
        for (HackConfig.Hook h : c.hooks) {
            if (!h.enabled || h.event.isEmpty() || done.contains(h.key)) continue;
            Plugin other = Bukkit.getPluginManager().getPlugin(h.plugin);
            if (other == null) continue; // that anticheat is not installed
            done.add(h.key); // never retried, so a wrong class name cannot spam the console
            try {
                Class<?> ec = Class.forName(h.event, true, other.getClass().getClassLoader());
                if (!Event.class.isAssignableFrom(ec)) {
                    pl.getLogger().warning("CheckHacks: " + h.event + " is not a Bukkit event, cannot hook " + h.plugin);
                    continue;
                }
                final Class<? extends Event> evc = ec.asSubclass(Event.class);
                final Method getter = ec.getMethod(h.getter);
                final String name = h.plugin;
                Bukkit.getPluginManager().registerEvent(evc, holder, EventPriority.MONITOR, (l, ev) -> {
                    try {
                        if (!evc.isInstance(ev)) return;
                        Object po = getter.invoke(ev);
                        if (po instanceof Player p) checker.trigger(p, HackChecker.Reason.ANTICHEAT, name);
                    } catch (Throwable ignored) {
                        // one broken event must never affect the other plugin
                    }
                }, pl, true);
                pl.getLogger().info("CheckHacks: hooked into " + h.plugin + " flags (" + h.event + ")");
            } catch (Throwable t) {
                pl.getLogger().warning("CheckHacks: could not hook " + h.plugin + ": " + t + " (edit detect-flag.anticheats." + h.key + " in checkhacks.yml)");
            }
        }
    }
}
