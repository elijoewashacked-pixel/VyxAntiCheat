package com.vyxac;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Once-per-second checks built from counters that the listeners maintain:
 * PacketFlood (events per second) and PingSpoof (approximation, see description in the check registry).
 * Real packet-level validation would need ProtocolLib/PacketEvents; these checks only use what Bukkit exposes.
 */
public final class PacketChecks {
    private final VyxAC pl;

    public PacketChecks(VyxAC pl) {
        this.pl = pl;
    }

    public void secondTick() {
        Settings s = pl.settings();
        long now = System.currentTimeMillis();
        CheckInfo flood = pl.checks().get("PacketFlood");
        CheckInfo spoof = pl.checks().get("PingSpoof");
        CheckInfo fly = pl.checks().get("Fly");
        boolean lag = s.lagProtection && pl.ticks().unstable();

        for (PlayerData pd : pl.allData()) {
            Player p = pd.player;
            if (p == null || !p.isOnline()) continue;
            pd.ping = Compat.ping(p);
            int moves = pd.evMoves, clicks = pd.evSwings + pd.evInteracts;
            pd.evMoves = 0;
            pd.evSwings = 0;
            pd.evInteracts = 0;
            if (pd.bypassed(now, s) || lag) {
                pd.highMoveSecs = pd.highClickSecs = 0;
                pd.lastPingSample = pd.ping;
                continue;
            }

            // ---- PacketFlood: a normal client sends about 20 movement packets and a few clicks per second
            if (flood.enabled) {
                double maxMoves = flood.p("max-moves"), maxClicks = flood.p("max-clicks");
                pd.highMoveSecs = moves > maxMoves ? pd.highMoveSecs + 1 : 0;
                pd.highClickSecs = clicks > maxClicks ? pd.highClickSecs + 1 : 0;
                if (pd.highMoveSecs >= 2 || pd.highClickSecs >= 2) {
                    pl.vio().flag(pd, flood, "moves/s " + moves + ", clicks/s " + clicks, 1.0);
                }
                double kickAt = flood.p("kick-at");
                if (kickAt > 0 && (moves > kickAt || clicks > kickAt) && flood.punishable) {
                    pl.vio().log("FLOOD-KICK " + pd.name + " moves=" + moves + " clicks=" + clicks);
                    p.kickPlayer(Settings.color("&c[VyxAC] Packet flood"));
                    continue;
                }
            }

            // ---- Fly (static hover): movement events never fire for a player that hovers perfectly still
            if (fly.enabled) hoverPoll(pd, p, now);

            // ---- PingSpoof (approximate): real networks do not swing by 300ms+ back and forth again and again
            if (spoof.enabled && pd.lastPingSample > 0) {
                int d = Math.abs(pd.ping - pd.lastPingSample);
                if (d >= spoof.p("delta")) pd.pingDither++;
                else if (pd.pingDither > 0 && now % 5 == 0) pd.pingDither--;
                if (pd.pingDither >= spoof.p("buffer")) {
                    pd.pingDither = 0;
                    pl.vio().flag(pd, spoof, "ping swings of " + d + "ms repeatedly (now " + pd.ping + "ms)", 1.0);
                }
            }
            pd.lastPingSample = pd.ping;
        }
    }
    private void hoverPoll(PlayerData pd, Player p, long now) {
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || p.getAllowFlight() || p.isFlying() || p.isInsideVehicle()
                || p.isGliding() || p.isDead() || p.isSleeping() || p.isRiptiding() || pd.exotic || pd.levitating || pd.slowFalling) {
            pd.sampleStill = 0;
            return;
        }
        Location l = p.getLocation();
        World w = l.getWorld();
        if (w == null) return;
        boolean still = pd.sampleY != 0 && Math.abs(l.getY() - pd.sampleY) < 0.0001;
        pd.sampleY = l.getY();
        if (!still || now - pd.lastTeleport < 5000 || now - pd.lastVelocity < 4000 || now - pd.lastSticky < 4000
                || now - pd.lastFlight < 4000 || now - pd.lastVehicle < 4000 || now - pd.lastRespawn < 5000) {
            pd.sampleStill = 0;
            return;
        }
        Phys.Support sup = Phys.support(w, l.getX(), l.getY(), l.getZ(), 0.3);
        if (sup != null || Phys.env(w, l.getX(), l.getY(), l.getZ()) != 0) { // supported, unknown chunk or special block
            pd.sampleStill = 0;
            return;
        }
        if (++pd.sampleStill >= 3) {
            pd.sampleStill = 0;
            if (!Phys.entitySupport(p)) {
                pl.vio().flag(pd, pl.checks().get("Fly"), "hovering motionless in mid-air for 3 seconds", 2.0);
            }
        }
    }
}
