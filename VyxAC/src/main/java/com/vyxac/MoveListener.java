package com.vyxac;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.util.Vector;

/**
 * All movement based checks. Design rules to avoid false positives:
 *  - every check is skipped during grace periods (teleport, knockback, respawn, vehicle, flight, server lag ...)
 *  - checks work on accumulated evidence (debt / buffers), never on one single event
 *  - whenever the world is unknown (unloaded chunk) the player is treated as legitimately supported
 */
public final class MoveListener implements Listener {
    /** Values computed once per move event and shared by all movement checks. */
    private static final class Ev {
        Player p;
        PlayerData pd;
        Location from, to;
        World w;
        long now, dt;
        double dx, dy, dz, h;
        MoveCtx prev;
        int env;
        boolean supported, glide, velGrace;
    }

    private final VyxAC pl;
    private final Ev ev = new Ev();
    private final CheckInfo SPEED, FLY, SPIDER, LONGJUMP, BHOP, NOFALL, JESUS, STEP, NOSLOW, TIMER, BLINK, PHASE,
            LADDER, ELYTRA, GROUND, ROT, BAD, VEL, ANTIKB, BARI, INVMOVE;

    public MoveListener(VyxAC pl) {
        this.pl = pl;
        CheckRegistry r = pl.checks();
        SPEED = r.get("Speed");
        FLY = r.get("Fly");
        SPIDER = r.get("Spider");
        LONGJUMP = r.get("LongJump");
        BHOP = r.get("BunnyHop");
        NOFALL = r.get("NoFall");
        JESUS = r.get("Jesus");
        STEP = r.get("Step");
        NOSLOW = r.get("NoSlow");
        TIMER = r.get("Timer");
        BLINK = r.get("Blink");
        PHASE = r.get("Phase");
        LADDER = r.get("FastLadder");
        ELYTRA = r.get("ElytraFly");
        GROUND = r.get("GroundSpoof");
        ROT = r.get("RotationSpoof");
        BAD = r.get("BadPackets");
        VEL = r.get("Velocity");
        ANTIKB = r.get("AntiKnockback");
        BARI = r.get("Baritone");
        INVMOVE = r.get("InventoryMove");
    }

    private static boolean finite(double d) {
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    private void refreshAttrs(PlayerData pd, Player p, long now) {
        if (now - pd.attrAt < 500) return;
        pd.attrAt = now;
        pd.jumpStrength = Compat.attrValue(p, 0.42, "GENERIC_JUMP_STRENGTH", "JUMP_STRENGTH");
        pd.stepHeight = Compat.attrValue(p, 0.6, "GENERIC_STEP_HEIGHT", "STEP_HEIGHT");
        pd.reachEntity = Compat.attrValue(p, 3.0, "PLAYER_ENTITY_INTERACTION_RANGE", "ENTITY_INTERACTION_RANGE");
        pd.reachBlock = Compat.attrValue(p, 4.5, "PLAYER_BLOCK_INTERACTION_RANGE", "BLOCK_INTERACTION_RANGE");
        pd.scale = Compat.attrValue(p, 1.0, "GENERIC_SCALE", "SCALE");
        pd.gravity = Compat.attrValue(p, 0.08, "GENERIC_GRAVITY", "GRAVITY");
        pd.safeFall = Compat.attrValue(p, 3.0, "GENERIC_SAFE_FALL_DISTANCE", "SAFE_FALL_DISTANCE");
        pd.speedLvl = Compat.level(p, "SPEED");
        pd.jumpLvl = Compat.level(p, "JUMP", "JUMP_BOOST");
        pd.resLvl = Compat.level(p, "DAMAGE_RESISTANCE", "RESISTANCE");
        pd.levitating = Compat.level(p, "LEVITATION") > 0;
        pd.slowFalling = Compat.level(p, "SLOW_FALLING") > 0;
        // modified gravity / scale (custom servers) breaks every vanilla assumption: skip movement checks
        pd.exotic = Math.abs(pd.scale - 1.0) > 0.01 || Math.abs(pd.gravity - 0.08) > 0.001;
    }

    /** Horizontal speed budget basis: the attribute already contains Speed/Slowness and (usually) sprinting. */
    private double effSpeed(Player p) {
        double base = Compat.attrValue(p, 0.1, "GENERIC_MOVEMENT_SPEED", "MOVEMENT_SPEED");
        // unsure whether the sprint modifier is already included on every version: stay on the lenient side
        return p.isSprinting() ? base * 1.15 : base * 1.3;
    }

    // ===================================================================================== main move handler

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Settings s = pl.settings();
        if (!s.enabled) return;
        Player p = e.getPlayer();
        Location from = e.getFrom(), to = e.getTo();
        if (to == null) return;
        PlayerData pd = pl.data(p);
        long now = System.currentTimeMillis();
        pd.evMoves++;
        if (pd.bypassed(now, s)) {
            pd.resetMotion();
            return;
        }
        World w = to.getWorld();
        if (w == null || from.getWorld() != w) {
            pd.lastWorld = now;
            pd.resetMotion();
            return;
        }
        double tx = to.getX(), ty = to.getY(), tz = to.getZ();
        if (!finite(tx) || !finite(ty) || !finite(tz) || !Float.isFinite(to.getYaw()) || !Float.isFinite(to.getPitch())) {
            pl.vio().flag(pd, BAD, "non-finite position or rotation", 3.0);
            e.setCancelled(true);
            return;
        }

        refreshAttrs(pd, p, now);
        boolean cancel = false;

        double dx = tx - from.getX(), dy = ty - from.getY(), dz = tz - from.getZ();
        double h = Math.hypot(dx, dz);
        float yawD = Geo.wrap(to.getYaw() - from.getYaw());
        float pitchD = to.getPitch() - from.getPitch();
        long dt = pd.lastMoveMs == 0 ? 50 : now - pd.lastMoveMs;

        // ---- rotation based checks (BadPackets / RotationSpoof) and camera bookkeeping
        if (Math.abs(to.getPitch()) > 90.5f) {
            cancel |= pl.vio().flag(pd, BAD, "pitch " + String.format("%.1f", to.getPitch()), 2.0);
        }
        if (ROT.enabled) {
            if (Math.abs(yawD) > 5f && Math.abs(yawD - pd.lastDYaw) < 0.001f) {
                if (++pd.constRot >= (int) ROT.p("const-steps")) {
                    pd.constRot = 0;
                    cancel |= pl.vio().flag(pd, ROT, String.format("constant rotation of %.1f deg per packet", yawD), 1.0);
                }
            } else {
                pd.constRot = 0;
            }
        }
        pd.lastDYaw = yawD;
        if (now - pd.lastAttackMs < 1500) pl.ai().onRotation(pd, yawD, pitchD);
        double rot = Math.abs(yawD) + Math.abs(pitchD);
        if (rot >= 8) {
            pd.lastBigRot = now;
            pd.lastBigRotDeg = rot;
        }

        // ---- environment
        int env = Phys.env(w, tx, ty, tz);
        Phys.Support sup = Phys.support(w, tx, ty, tz, 0.03);
        boolean supported = sup != null; // UNKNOWN (unloaded chunk) counts as supported
        if ((env & MoveCtx.ICE) != 0) pd.lastIce = now;
        if ((env & MoveCtx.BOUNCE) != 0) pd.lastBounce = now;
        boolean glide = p.isGliding();
        if (glide) pd.lastGlide = now;
        boolean using = p.isHandRaised();
        if (using && !pd.wasUsing) pd.usingSince = now;
        pd.wasUsing = using;

        MoveCtx prev = pd.prev;
        boolean discontinuity = prev == null || dt > 2500
                || Math.abs(prev.x - from.getX()) > 1.0 || Math.abs(prev.z - from.getZ()) > 1.0 || Math.abs(prev.y - from.getY()) > 1.5;
        boolean lagged = s.lagProtection && pl.ticks().unstable();
        GameMode gm = p.getGameMode();
        boolean skip = gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || p.isDead() || p.isSleeping()
                || p.getAllowFlight() || p.isFlying() || p.isInsideVehicle() || pd.exotic || p.isRiptiding();
        boolean velGrace = now - pd.lastVelocity < s.velGraceMs || now - pd.lastGlide < 2500 || now - pd.lastLaunch < 6000;
        // a connection stall while moving (silence >= 0.8s after a moving event) is followed by a burst of queued packets
        // that looks exactly like speed/timer: suspend the checks for a moment. Standing still does NOT earn this.
        if (dt >= 800 && pd.lastMoveMs != 0 && pd.lastH >= 0.15) pd.ts("gapAt", now);
        boolean gapped = now - pd.ts("gapAt") < 2500;
        boolean grace = discontinuity || lagged || gapped
                || now - pd.lastTeleport < s.tpGraceMs || now - pd.lastRespawn < 3000 || now - pd.lastWorld < 3000
                || now - pd.lastVehicle < 1500 || now - pd.lastFlight < 2000 || now - pd.lastSticky < 2500
                || now - pd.lastRiptide < 4000;

        // ---- timing checks (Timer / Blink) work for everybody that is not lagging
        if (!lagged && now - pd.lastTeleport >= s.tpGraceMs && !discontinuity) {
            cancel |= timerBlink(pd, now, dt, gapped);
        } else {
            pd.timerBal = 0;
            pd.gapMs = 0;
            pd.burstN = 0;
        }

        // ---- air / ground bookkeeping
        boolean ground = supported || (env & MoveCtx.SPECIAL) != 0;
        pd.completedRun = null;
        if (ground) {
            if (pd.air != null) {
                pd.air.endY = ty;
                pd.air.endMs = now;
                pd.completedRun = pd.air;
                pd.air = null;
            }
            pd.airTicks = 0;
            pd.groundTicks++;
            pd.groundY = ty;
            pd.hasGroundY = true;
            pd.hoverTicks = 0;
        } else {
            if (pd.air == null) {
                PlayerData.AirRun a = new PlayerData.AirRun();
                a.startY = from.getY();
                a.maxY = Math.max(from.getY(), ty);
                a.startMs = pd.lastMoveMs == 0 ? now : pd.lastMoveMs;
                pd.air = a;
            }
            pd.air.events++;
            pd.air.hDist += h;
            pd.air.maxY = Math.max(pd.air.maxY, ty);
            pd.airTicks++;
            pd.groundTicks = 0;
        }
        if (pd.air != null && (skip || grace || velGrace || glide || now - pd.lastBounce < 6000 || pd.levitating || pd.slowFalling)) {
            pd.air.tainted = true;
        }

        if (skip || grace) {
            pd.speedDebt = 0;
            pd.hoverTicks = 0;
            pd.completedRun = null;
        } else {
            Ev v = ev;
            v.p = p;
            v.pd = pd;
            v.from = from;
            v.to = to;
            v.w = w;
            v.now = now;
            v.dt = dt;
            v.dx = dx;
            v.dy = dy;
            v.dz = dz;
            v.h = h;
            v.prev = prev;
            v.env = env;
            v.supported = supported;
            v.glide = glide;
            v.velGrace = velGrace;
            try {
                cancel |= speed(v);
                cancel |= fly(v);
                cancel |= step(v);
                cancel |= noSlow(v, using);
                cancel |= ladder(v);
                cancel |= jesus(v);
                cancel |= phase(v);
                cancel |= elytra(v);
                cancel |= groundSpoof(v);
                cancel |= baritone(v);
                cancel |= invMove(v);
                cancel |= runChecks(v);
            } catch (Throwable t) {
                // a bug in one check must never break the server: log once per minute at most
                if (now - pd.ts("err") > 60_000L) {
                    pd.ts("err", now);
                    pl.getLogger().warning("Movement check error: " + t);
                }
            }
        }

        // movement setbacks are opt-in, only on the ground and never right after a launch: a setback in mid-air freezes people
        if (cancel && s.mitigateMovement && supported && !velGrace) {
            if (now - pd.ts("mitWin") > 5000) {
                pd.ts("mitWin", now);
                pd.setBuf("mitN", 0);
            }
            if (pd.bump("mitN", 1) <= 3) { // never rubber-band a player endlessly: after 3 setbacks the flags still count, the moves are allowed
                pd.lastMitigate = now;
                pd.speedDebt = 0;
                e.setCancelled(true); // the server moves the player back to the previous position
                return;
            }
        }
        pd.prev = new MoveCtx(now, tx, ty, tz, to.getYaw(), to.getPitch(), env, supported);
        pd.lastMoveMs = now;
        pd.lastDy = dy;
        pd.lastTicks = (int) Math.max(1, Math.min(6, Math.round(dt / 50.0)));
        pd.lastH = h;
        pd.recordPos(now, tx, ty, tz);
    }

    // ===================================================================================== timing

    private boolean timerBlink(PlayerData pd, long now, long dt, boolean gapped) {
        boolean cancel = false;
        // ---- Timer: clock balance. Every event "costs" 50ms, real time that passes pays it back.
        if (gapped) {
            pd.timerBal = 0;
        } else if (TIMER.enabled) {
            pd.timerBal += 50.0 - Math.min(dt, 20_000L);
            double bank = TIMER.p("bank-ms");
            if (pd.timerBal < -bank) pd.timerBal = -bank;
            if (pd.timerBal > TIMER.p("tolerance-ms")) {
                double over = pd.timerBal;
                pd.timerBal = 0;
                cancel |= pl.vio().flag(pd, TIMER, String.format("clock %.0fms ahead", over), Math.min(3.0, 1.0 + over / 1000.0));
            }
        }
        // ---- Blink: a gap in movement followed by a burst of packets at the same tick
        if (BLINK.enabled) {
            if (dt >= BLINK.p("min-gap-ms")) {
                pd.gapMs = dt;
                pd.gapPing = pd.ping;
                pd.burstN = 0;
            } else if (pd.gapMs > 0) {
                if (dt <= 25) pd.burstN++;
                else if (dt > 120) {
                    pd.gapMs = 0;
                    pd.burstN = 0;
                }
                if (pd.burstN >= (int) BLINK.p("burst")) {
                    boolean stable = Math.abs(pd.ping - pd.gapPing) <= BLINK.p("stable-ping-delta") && pd.ping < 250;
                    long gap = pd.gapMs;
                    pd.gapMs = 0;
                    pd.burstN = 0;
                    if (now - pd.ts("blinkAt") > 60_000L) pd.setBuf("blink", 0);
                    pd.ts("blinkAt", now);
                    if (stable && pd.bump("blink", 1) >= BLINK.p("buffer")) {
                        pd.setBuf("blink", 0);
                        pl.vio().flag(pd, BLINK, "burst after " + gap + "ms pause, ping stable at " + pd.ping + "ms", 1.0);
                    }
                }
            }
        }
        return cancel;
    }

    // ===================================================================================== horizontal / vertical

    private boolean speed(Ev v) {
        if (!SPEED.enabled) return false;
        PlayerData pd = v.pd;
        if (v.velGrace || v.glide || (v.env & (MoveCtx.WATER | MoveCtx.LAVA | MoveCtx.CLIMB | MoveCtx.BUBBLE | MoveCtx.WEB | MoveCtx.HONEY)) != 0
                || v.p.isSwimming()) {
            pd.speedDebt = 0;
            return false;
        }
        double eff = effSpeed(v.p);
        double per = eff * 2.16 * SPEED.p("leniency");
        if (pd.airTicks > 0 || pd.groundTicks <= 2) per += 0.21; // sprint-jump boost
        if ((v.env & MoveCtx.ICE) != 0 || v.now - pd.lastIce < 1500) per *= SPEED.p("ice-multiplier");
        double ticks = Math.max(v.dt, 1) / 50.0;
        pd.speedDebt += v.h - per * ticks;
        double bank = per * 6; // small credit for packet batching; real stalls are handled by the gap rule
        if (pd.speedDebt < -bank) pd.speedDebt = -bank;
        double tol = SPEED.p("tolerance");
        if (pd.speedDebt > tol) {
            double over = pd.speedDebt;
            pd.speedDebt = tol * 0.4;
            return pl.vio().flag(pd, SPEED, String.format("%.2f blocks over limit (%.2f b/tick, limit %.2f)", over, v.h / ticks, per),
                    Math.min(3.0, 1.0 + over / tol));
        }
        return false;
    }

    private boolean fly(Ev v) {
        PlayerData pd = v.pd;
        if (!FLY.enabled && !SPIDER.enabled) return false;
        if (v.supported || (v.env & MoveCtx.SPECIAL) != 0 || pd.levitating || v.glide) {
            pd.hoverTicks = 0;
            return false;
        }
        if (v.velGrace || v.now - pd.lastVelocity < pl.settings().velGraceMs + 1500 || v.now - pd.lastBounce < 6000) return false;
        boolean cancel = false;
        double x = v.to.getX(), y = v.to.getY(), z = v.to.getZ();

        // rising higher than a jump can possibly reach AND not following normal gravity.
        // A player launched by a wind charge, explosion, slime or an unknown plugin still follows vanilla gravity
        // (dy[t+1] = (dy[t] - 0.08) * 0.98); a fly hack does not.
        boolean ballistic = Math.abs(v.dy - predictDy(pd.lastDy, pd.lastTicks, v.dt)) <= 0.07 + 0.03 * (Math.max(1, Math.min(6, Math.round(v.dt / 50.0))) - 1);
        if (pd.hasGroundY) {
            double rise = y - pd.groundY;
            double apex = Compat.jumpApex(pd.jumpLvl, pd.jumpStrength);
            double lim = apex + FLY.p("tolerance");
            if (rise > lim && !ballistic) {
                if (pd.bump("flyrise", 1) >= FLY.p("rise-buffer")) {
                    pd.setBuf("flyrise", 0);
                    if (!Phys.entitySupport(v.p)) {
                        boolean wall = Phys.wallNear(v.w, x, y, z);
                        CheckInfo ci = wall ? SPIDER : FLY;
                        cancel |= pl.vio().flag(pd, ci, String.format("rose %.2f blocks (jump limit %.2f) without following gravity", rise, apex), Math.min(3.0, 1.0 + (rise - lim)));
                    }
                }
            } else {
                pd.bump("flyrise", -1);
            }
        }
        // hovering: (nearly) no vertical movement for many events while unsupported
        if (FLY.enabled && !pd.slowFalling) {
            if (Math.abs(v.dy) < 0.03 && pd.airTicks > 3) pd.hoverTicks++;
            else pd.hoverTicks = Math.max(0, pd.hoverTicks - 2);
            if (pd.hoverTicks >= (int) FLY.p("hover-limit")) {
                pd.hoverTicks = 0;
                if (!Phys.entitySupport(v.p)) {
                    cancel |= pl.vio().flag(pd, FLY, "hovering without support", 1.0);
                }
            }
        }
        return cancel;
    }

    /**
     * Vertical displacement vanilla gravity would produce over the ticks covered by one move event.
     * The previous event may itself span several ticks, so its last-tick velocity is estimated first.
     */
    private static double predictDy(double lastDy, int lastTicks, long dtMs) {
        double avg = lastDy / Math.max(1, lastTicks);
        double vy = avg - (Math.max(1, lastTicks) - 1) / 2.0 * (0.0784 + 0.02 * avg);
        int n = (int) Math.max(1, Math.min(6, Math.round(dtMs / 50.0)));
        double sum = 0;
        for (int i = 0; i < n; i++) {
            vy = (vy - 0.08) * 0.98;
            sum += vy;
        }
        return sum;
    }

    private boolean step(Ev v) {
        if (!STEP.enabled) return false;
        PlayerData pd = v.pd;
        MoveCtx prev = v.prev;
        if (prev == null || !prev.supported || v.glide) return false;
        if ((v.env & MoveCtx.SPECIAL) != 0 || (prev.env & MoveCtx.SPECIAL) != 0) return false;
        if (v.velGrace || v.now - pd.lastBounce < 6000 || pd.levitating) return false;
        double limit = Math.max(pd.stepHeight, pd.jumpStrength + 0.1 * pd.jumpLvl) + STEP.p("tolerance") + 0.05;
        if (v.dy > limit) {
            return pl.vio().flag(pd, STEP, String.format("stepped up %.2f blocks (limit %.2f)", v.dy, limit), Math.min(3.0, 1.0 + (v.dy - limit)));
        }
        return false;
    }

    private boolean noSlow(Ev v, boolean using) {
        if (!NOSLOW.enabled) return false;
        PlayerData pd = v.pd;
        if (!using) {
            pd.bump("noslow", -1);
            return false;
        }
        if (v.now - pd.usingSince < NOSLOW.p("min-use-ms")) return false;
        if (!v.supported || pd.groundTicks < 3 || v.velGrace || (v.env & (MoveCtx.SPECIAL | MoveCtx.ICE)) != 0
                || v.now - pd.lastIce < 1500 || v.p.isInsideVehicle()) {
            return false;
        }
        if (v.h > NOSLOW.p("max-speed")) {
            if (pd.bump("noslow", 1) >= NOSLOW.p("buffer")) {
                pd.setBuf("noslow", 0);
                return pl.vio().flag(pd, NOSLOW, String.format("%.2f b/tick while using an item", v.h), 1.0);
            }
        } else {
            pd.bump("noslow", -0.5);
        }
        return false;
    }

    private boolean ladder(Ev v) {
        if (!LADDER.enabled) return false;
        PlayerData pd = v.pd;
        if ((v.env & MoveCtx.CLIMB) == 0 || (v.env & (MoveCtx.WATER | MoveCtx.LAVA | MoveCtx.WEB)) != 0 || v.velGrace) {
            pd.bump("ladder", -1);
            return false;
        }
        if (v.dy > LADDER.p("max-up")) {
            if (pd.bump("ladder", 1) >= LADDER.p("buffer")) {
                pd.setBuf("ladder", 0);
                return pl.vio().flag(pd, LADDER, String.format("climbing %.2f blocks per move", v.dy), 1.0);
            }
        } else {
            pd.bump("ladder", -0.5);
        }
        return false;
    }

    private boolean jesus(Ev v) {
        if (!JESUS.enabled) return false;
        PlayerData pd = v.pd;
        if (v.supported || v.glide) {
            pd.bump("jesus", -1);
            return false;
        }
        double x = v.to.getX(), y = v.to.getY(), z = v.to.getZ();
        Block below = Phys.at(v.w, Phys.floor(x), Phys.floor(y - 0.1), Phys.floor(z));
        Block body = Phys.at(v.w, Phys.floor(x), Phys.floor(y + 0.1), Phys.floor(z));
        boolean onLiquid = below != null && below.isLiquid() && body != null && !body.isLiquid();
        if (onLiquid && Math.abs(v.dy) < 0.02 && v.h > 0.05) {
            if (pd.bump("jesus", 1) >= JESUS.p("buffer")) {
                pd.setBuf("jesus", 0);
                if (!Phys.entitySupport(v.p)) {
                    return pl.vio().flag(pd, JESUS, "walking on " + below.getType().name().toLowerCase(), 1.0);
                }
            }
        } else {
            pd.bump("jesus", -1);
        }
        return false;
    }

    private static boolean occluding(World w, double x, double y, double z) {
        Block b = Phys.at(w, Phys.floor(x), Phys.floor(y), Phys.floor(z));
        return b != null && b.getType().isOccluding();
    }

    private boolean phase(Ev v) {
        if (!PHASE.enabled) return false;
        PlayerData pd = v.pd;
        double ady = Math.abs(v.dy);
        if (v.velGrace) return false; // launches and knockback move players fast and close to walls
        if ((v.h < 0.01 && ady < 0.01) || v.h > 1.2 || ady > 1.2) return false; // long chords could cut corners
        if (v.now - pd.lastSticky < 2500 || pd.levitating) return false;
        MoveCtx prev = v.prev;
        if (prev != null && occluding(v.w, prev.x, prev.y + 0.4, prev.z) && occluding(v.w, prev.x, prev.y + 1.4, prev.z)) {
            return false; // was already stuck inside before this move (falling sand, pistons ...)
        }
        int steps = (int) Math.ceil(Math.max(v.h, ady) / 0.12) + 1;
        double fx = v.from.getX(), fy = v.from.getY(), fz = v.from.getZ();
        boolean hit = false;
        for (int i = 1; i <= steps && !hit; i++) {
            double t = i / (double) steps;
            double x = fx + v.dx * t, y = fy + v.dy * t, z = fz + v.dz * t;
            hit = occluding(v.w, x, y + 0.4, z) && occluding(v.w, x, y + 1.4, z);
        }
        if (hit) {
            if (pd.bump("phase", 1) >= PHASE.p("buffer")) {
                pd.setBuf("phase", 0);
                return pl.vio().flag(pd, PHASE, "moved into solid blocks", 2.0);
            }
        } else {
            pd.bump("phase", -0.5);
        }
        return false;
    }

    private boolean elytra(Ev v) {
        if (!ELYTRA.enabled) return false;
        PlayerData pd = v.pd;
        if (!v.glide) {
            pd.bump("elytra", -1);
            return false;
        }
        // rockets, wind charges, explosions and knockback all legitimately push an elytra pilot far beyond normal speed
        boolean boost = v.now - pd.lastFirework < 4000 || v.now - pd.lastLaunch < 6000 || v.now - pd.lastVelocity < pl.settings().velGraceMs;
        double ticks = Math.max(v.dt, 1) / 50.0;
        double speed = v.h / ticks;
        if (!boost && speed > ELYTRA.p("max-speed")) {
            return pl.vio().flag(pd, ELYTRA, String.format("gliding at %.2f b/tick without a firework", speed), 1.0);
        }
        if (!boost && v.h < 0.6 && Math.abs(v.dy) < 0.03) {
            if (pd.bump("elytra", 1) >= ELYTRA.p("hover-limit")) {
                pd.setBuf("elytra", 0);
                return pl.vio().flag(pd, ELYTRA, "hovering while gliding", 1.0);
            }
        } else {
            pd.bump("elytra", -1);
        }
        return false;
    }

    private boolean groundSpoof(Ev v) {
        if (!GROUND.enabled) return false;
        PlayerData pd = v.pd;
        MoveCtx prev = v.prev;
        if (prev == null || v.glide || v.velGrace) return false;
        boolean claim = v.p.isOnGround(); // flag of the previous movement packet
        if (!claim || prev.supported || (prev.env & MoveCtx.SPECIAL) != 0 || (v.env & MoveCtx.SPECIAL) != 0) {
            pd.bump("ground", -1);
            return false;
        }
        // lenient second opinion with a larger tolerance around the previous position
        Phys.Support lenient = Phys.support(v.w, prev.x, prev.y, prev.z, 0.4);
        if (lenient != null) {
            pd.bump("ground", -1);
            return false;
        }
        if (pd.bump("ground", 1) >= GROUND.p("buffer")) {
            pd.setBuf("ground", 0);
            if (!Phys.entitySupport(v.p)) {
                return pl.vio().flag(pd, GROUND, "claims to be on the ground in mid-air", 1.0);
            }
        }
        return false;
    }

    private boolean baritone(Ev v) {
        if (!BARI.enabled) return false;
        PlayerData pd = v.pd;
        if (!v.p.isSprinting() || !v.supported || pd.groundTicks < 4 || v.h < 0.2 || v.velGrace
                || (v.env & (MoveCtx.SPECIAL | MoveCtx.ICE)) != 0 || v.now - pd.lastIce < 1500) {
            pd.bump("bari", -1);
            return false;
        }
        double heading = Math.toDegrees(Math.atan2(-v.dx, v.dz));
        double off = Math.abs(Geo.wrap((float) (heading - v.to.getYaw())));
        if (off > BARI.p("min-offset-deg")) {
            if (pd.bump("bari", 1) >= BARI.p("buffer")) {
                pd.setBuf("bari", 0);
                return pl.vio().flag(pd, BARI, String.format("sprinting %.0f degrees away from where the camera points", off), 1.0);
            }
        } else {
            pd.bump("bari", -1);
        }
        return false;
    }

    private boolean invMove(Ev v) {
        if (!INVMOVE.enabled) return false;
        PlayerData pd = v.pd;
        if (pd.containerOpenSince == 0 || v.now - pd.containerOpenSince < INVMOVE.p("min-open-ms")) {
            pd.bump("invmove", -1);
            return false;
        }
        if (v.h > INVMOVE.p("min-speed") && v.supported && pd.groundTicks >= 2 && !v.velGrace
                && (v.env & (MoveCtx.SPECIAL | MoveCtx.ICE)) == 0 && v.now - pd.lastIce >= 1500) {
            if (pd.bump("invmove", 1) >= INVMOVE.p("buffer")) {
                pd.setBuf("invmove", 0);
                return pl.vio().flag(pd, INVMOVE, String.format("moving at %.2f b/tick with a container open", v.h), 1.0);
            }
        } else {
            pd.bump("invmove", -1);
        }
        return false;
    }

    /** LongJump, BunnyHop and NoFall are evaluated once per finished airborne phase. */
    private boolean runChecks(Ev v) {
        PlayerData pd = v.pd;
        PlayerData.AirRun r = pd.completedRun;
        if (r == null || r.tainted) {
            if (r != null) pd.hopRuns = 0;
            return false;
        }
        boolean cancel = false;
        double ticks = r.ticks();
        double eff = effSpeed(v.p);
        // lenient basis: assume the sprint bonus is not yet part of the attribute
        double effL = Compat.attrValue(v.p, 0.1, "GENERIC_MOVEMENT_SPEED", "MOVEMENT_SPEED") * 1.3;
        boolean droppedDown = r.startY - r.endY > 0.5; // jumping DOWN from a ledge is not a long jump
        if (LONGJUMP.enabled && r.events >= 3 && !droppedDown) {
            double allowed = effL * 2.16 * LONGJUMP.p("leniency") * ticks + LONGJUMP.p("slack");
            if (r.hDist > allowed) {
                if (pd.bump("lj", 1) >= LONGJUMP.p("buffer")) {
                    pd.setBuf("lj", 0);
                    cancel |= pl.vio().flag(pd, LONGJUMP, String.format("jumped %.1f blocks in %.0f ticks (limit %.1f)", r.hDist, ticks, allowed),
                            Math.min(3.0, 1.0 + (r.hDist - allowed) / 2.0));
                }
            } else {
                pd.bump("lj", -0.5);
            }
        }
        if (BHOP.enabled && r.events >= 4 && !droppedDown) {
            double avg = r.hDist / ticks;
            if (avg > effL * 2.16 * BHOP.p("leniency")) {
                if (++pd.hopRuns >= (int) BHOP.p("runs")) {
                    pd.hopRuns = 0;
                    cancel |= pl.vio().flag(pd, BHOP, String.format("%.2f b/tick average over consecutive hops", avg), 1.0);
                }
            } else {
                pd.hopRuns = 0;
            }
        }
        if (NOFALL.enabled) scheduleNoFall(v, r);
        return cancel;
    }

    private void scheduleNoFall(Ev v, PlayerData.AirRun r) {
        PlayerData pd = v.pd;
        Player p = v.p;
        double need = NOFALL.p("min-drop") + Math.max(0, pd.safeFall - 3.0);
        double drop = r.drop();
        GameMode gm = p.getGameMode();
        if (drop < need || v.env != 0 || pd.resLvl >= 5 || p.isInvulnerable() || p.isInsideVehicle()
                || (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE)
                || v.now - pd.lastDamage < 700 || v.now - pd.lastVehicle < 3000) {
            return;
        }
        final long landMs = v.now;
        final double x = v.to.getX(), y = v.to.getY(), z = v.to.getZ();
        final World w = v.w;
        final double fall = drop;
        final double needF = need;
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (!p.isOnline() || p.isDead()) return;
            if (pd.lastFallDamage >= landMs - 300 || pd.lastDamage >= landMs - 700) return; // damage was dealt
            if (pd.lastTeleport > landMs - 100 || pd.lastVelocity > landMs - 200) return;
            Phys.Support s = Phys.support(w, x, y, z, 0.3);
            if (s == Phys.UNKNOWN) return;
            if (s != null && s.mat != null && (s.mat == Material.HAY_BLOCK || s.mat == Material.SLIME_BLOCK || s.mat == Material.HONEY_BLOCK)) return;
            pl.vio().flag(pd, NOFALL, String.format("fell %.1f blocks without damage", fall), Math.min(3.0, 1.0 + (fall - needF) / 5.0));
        }, Math.max(1L, (long) NOFALL.p("wait-ticks")));
    }

    // ===================================================================================== knockback (Velocity / AntiKnockback)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent e) {
        Player p = e.getPlayer();
        PlayerData pd = pl.data(p);
        long now = System.currentTimeMillis();
        pd.lastVelocity = now;
        if (!(VEL.enabled || ANTIKB.enabled)) return;
        Vector vec = e.getVelocity();
        double hm = Math.hypot(vec.getX(), vec.getZ());
        // very large or mostly vertical velocities are launch pads / explosions / wind charges: not measurable
        if (hm < VEL.p("min-kb") || hm > 1.5 || Math.abs(vec.getY()) > 0.8 || pd.bypassed(now, pl.settings())) {
            pd.kb = null;
            return;
        }
        Location l = p.getLocation();
        final PlayerData.Kb k = new PlayerData.Kb();
        k.startMs = now;
        k.mag = hm;
        k.dirX = vec.getX() / hm;
        k.dirZ = vec.getZ() / hm;
        k.startX = l.getX();
        k.startY = l.getY();
        k.startZ = l.getZ();
        pd.kb = k;
        long delay = 9 + Math.min(10, pd.ping / 50);
        Bukkit.getScheduler().runTaskLater(pl, () -> evalKb(pd, k), delay);
    }

    private void evalKb(PlayerData pd, PlayerData.Kb k) {
        if (pd.kb != k) return; // replaced by a newer knockback
        pd.kb = null;
        Player p = pd.player;
        Settings s = pl.settings();
        if (p == null || !p.isOnline()) return;
        long now = System.currentTimeMillis();
        if (pd.bypassed(now, s) || (s.lagProtection && pl.ticks().unstable())) return;
        if (pd.lastVelocity != k.startMs || pd.lastTeleport > k.startMs || pd.lastRespawn > k.startMs) return;
        if (pd.ping > 250 || p.isInsideVehicle() || p.isGliding() || p.isFlying() || p.getAllowFlight() || p.isDead()
                || p.isBlocking() || pd.exotic) {
            return;
        }
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;
        Location l = p.getLocation();
        World w = l.getWorld();
        if (w == null) return;
        int env = Phys.env(w, l.getX(), l.getY(), l.getZ());
        if ((env & (MoveCtx.WATER | MoveCtx.LAVA | MoveCtx.CLIMB | MoveCtx.WEB | MoveCtx.BUBBLE | MoveCtx.HONEY)) != 0) return;
        // walls in the way make the measurement meaningless
        if (Phys.blockedAhead(w, k.startX, k.startY, k.startZ, k.dirX, k.dirZ) || Phys.blockedAhead(w, l.getX(), l.getY(), l.getZ(), k.dirX, k.dirZ)) return;
        if (Phys.ceilingAbove(w, k.startX, k.startY, k.startZ)) return;

        double proj = (l.getX() - k.startX) * k.dirX + (l.getZ() - k.startZ) * k.dirZ;
        double expected = k.mag * 2.0; // conservative lower bound of what ~9 ticks of knockback move a player
        double ratio = proj / expected;
        if (ANTIKB.enabled && ratio < ANTIKB.p("min-ratio") && Math.abs(proj) < 0.12) {
            if (pd.bump("antikb", 1) >= ANTIKB.p("buffer")) {
                pd.setBuf("antikb", 0);
                pl.vio().flag(pd, ANTIKB, String.format("moved %.2f of expected %.2f blocks", proj, expected), 1.0);
            }
            return;
        }
        if (VEL.enabled && ratio < VEL.p("min-ratio")) {
            if (pd.bump("vel", 1) >= VEL.p("buffer")) {
                pd.setBuf("vel", 0);
                pl.vio().flag(pd, VEL, String.format("only %.0f%% of expected knockback", Math.max(0, ratio) * 100), 1.0);
            }
            return;
        }
        pd.bump("antikb", -0.5);
        pd.bump("vel", -0.5);
    }

    // ===================================================================================== state events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent e) {
        PlayerData pd = pl.data(e.getPlayer());
        pd.lastTeleport = System.currentTimeMillis();
        pd.resetMotion();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        PlayerData pd = pl.data(e.getPlayer());
        pd.lastRespawn = System.currentTimeMillis();
        pd.resetMotion();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) {
        PlayerData pd = pl.data(e.getPlayer());
        pd.lastWorld = System.currentTimeMillis();
        pd.resetMotion();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent e) {
        if (e.getEntity() instanceof Player p) {
            PlayerData pd = pl.data(p);
            pd.lastGlide = System.currentTimeMillis();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRiptide(PlayerRiptideEvent e) {
        pl.data(e.getPlayer()).lastRiptide = System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFlight(PlayerToggleFlightEvent e) {
        PlayerData pd = pl.data(e.getPlayer());
        pd.lastFlight = System.currentTimeMillis();
        pd.resetMotion();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        PlayerData pd = pl.data(e.getPlayer());
        pd.lastFlight = System.currentTimeMillis();
        pd.resetMotion();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleEnter(VehicleEnterEvent e) {
        if (e.getEntered() instanceof Player p) {
            PlayerData pd = pl.data(p);
            pd.lastVehicle = System.currentTimeMillis();
            pd.resetMotion();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleExit(VehicleExitEvent e) {
        if (e.getExited() instanceof Player p) {
            PlayerData pd = pl.data(p);
            pd.lastVehicle = System.currentTimeMillis();
            pd.resetMotion();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) {
            PlayerData pd = pl.data(p);
            long now = System.currentTimeMillis();
            pd.lastDamage = now;
            if (e.getCause() == EntityDamageEvent.DamageCause.FALL) pd.lastFallDamage = now;
        }
    }

    private void markNear(org.bukkit.block.Block b) {
        World w = b.getWorld();
        long now = System.currentTimeMillis();
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(b.getLocation()) < 49) pl.data(p).lastSticky = now;
        }
    }

    private void markLaunch(Location l, double radius) {
        World w = l.getWorld();
        if (w == null) return;
        long now = System.currentTimeMillis();
        double r2 = radius * radius;
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(l) <= r2) pl.data(p).lastLaunch = now;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onExplode(EntityExplodeEvent e) {
        markLaunch(e.getLocation(), 12);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockExplode(BlockExplodeEvent e) {
        markLaunch(e.getBlock().getLocation(), 12);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProjectileHit(ProjectileHitEvent e) {
        if (e.getEntity().getType().name().contains("WIND_CHARGE")) markLaunch(e.getEntity().getLocation(), 8);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        markNear(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        markNear(e.getBlock());
    }
}
