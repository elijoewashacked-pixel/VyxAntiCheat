package com.vyxac;

import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** All mutable per-player state. Only ever touched from the main thread. */
public final class PlayerData {
    public record FlagRecord(long time, String check, double vl, String info, boolean punishable) {}

    /** Knockback measurement in progress (Velocity / AntiKnockback). */
    public static final class Kb {
        public long startMs;
        public double mag, dirX, dirZ, startX, startY, startZ;
    }

    /** One airborne phase, finished when the player is supported again. */
    public static final class AirRun {
        public int events;
        public double hDist, maxY, startY, endY;
        public boolean tainted;
        public long startMs, endMs;
        public double drop() { return maxY - endY; }
        public double ticks() { return Math.max(events, (endMs - startMs) / 50.0); }
    }

    public final UUID id;
    public String name;
    public Player player;
    public final long joinMs = System.currentTimeMillis();
    public int ping;

    // ---- lifecycle timestamps (ms) ----
    public long lastTeleport, lastVelocity, lastWorld, lastRespawn, lastFirework, lastGlideEnd, lastVehicle, lastFlight;
    public long lastDamage, lastMitigate, lastSwingMs, lastAttackMs, lastHeldMs, lastFallDamage, lastTotemPop;
    public long lastIce, lastBounce, lastSticky, lastGlide, lastRiptide, lastBigRot, lastCrystalPlace, lastFlagMs;
    public long exemptUntil;
    public double lastBigRotDeg;

    // ---- bypass cache ----
    private boolean bypass;
    private long bypassAt;

    // ---- cached attributes / effects (refreshed ~2x per second) ----
    public long attrAt;
    public double scale = 1, reachEntity = 3.0, reachBlock = 4.5, jumpStrength = 0.42, stepHeight = 0.6, gravity = 0.08, safeFall = 3.0;
    public double attrSpeed = 0.1;
    public int speedLvl, jumpLvl, resLvl;
    public boolean levitating, slowFalling, exotic;

    // ---- movement ----
    public MoveCtx prev;
    public long lastMoveMs;
    public int airTicks, groundTicks, hoverTicks, ascendTicks;
    public double groundY, lastDy, lastH, speedDebt, timerBal;
    public boolean hasGroundY;
    public float lastDYaw;
    public int constRot, hopRuns;
    public AirRun air;
    public AirRun completedRun;
    public Kb kb;
    public long gapMs;
    public int burstN, gapPing;
    public long containerOpenSince;
    public long usingSince;
    public boolean wasUsing;
    public long useStartMs;
    public String useItem = "";

    // ---- position history for lag-compensated hit validation ----
    public static final int HN = 32;
    public final long[] hT = new long[HN];
    public final double[] hX = new double[HN], hY = new double[HN], hZ = new double[HN];
    public int hHead, hCount;

    // ---- combat memory ----
    public UUID lastTargetId;
    public long lastTargetMs;
    public float lastHitYaw;
    public final AiStats ai = new AiStats();

    // ---- violations ----
    public final Map<String, Double> vl = new HashMap<>();
    public final Map<String, Double> buf = new HashMap<>();
    public final Map<String, Long> lastAlert = new HashMap<>();
    public final Map<String, Long> ts = new HashMap<>();
    public final Map<String, Ring> rings = new HashMap<>();
    public final ArrayDeque<FlagRecord> history = new ArrayDeque<>();
    public long flagsTotal;
    /** blocks the player started to break (packed position -> start time) */
    public final HashMap<Long, Long> dmg = new HashMap<>();

    // ---- punishment ----
    public double aiConfidence;
    public long aiConfAt;
    public int strikes;
    public long lastPunishMs, reviewPromptAt;
    public boolean punishScheduled;

    // ---- per-second counters ----
    public int evMoves, evSwings, evInteracts, highMoveSecs, highClickSecs;
    public int lastPingSample, pingDither, sampleStill;
    public double sampleY;

    public PlayerData(Player p) {
        this.id = p.getUniqueId();
        this.name = p.getName();
        this.player = p;
    }

    public boolean bypassed(long now, Settings s) {
        if (now < exemptUntil) return true;
        if (now - bypassAt > 10_000L) {
            bypassAt = now;
            Player p = player;
            bypass = p != null && (p.hasPermission("vyxac.bypass")
                    || (s.bedrockExempt && !s.bedrockPrefix.isEmpty() && name.startsWith(s.bedrockPrefix)));
        }
        return bypass;
    }

    public void forceBypassRecheck() {
        bypassAt = 0;
    }

    public void recordPos(long ms, double x, double y, double z) {
        hHead = (hHead + 1) % HN;
        hT[hHead] = ms;
        hX[hHead] = x;
        hY[hHead] = y;
        hZ[hHead] = z;
        if (hCount < HN) hCount++;
    }

    /** index of the i-th newest history entry (0 = newest) */
    public int histIdx(int i) {
        return ((hHead - i) % HN + HN) % HN;
    }

    public boolean recent(long ts, long now, long window) {
        return ts != 0 && now - ts < window;
    }

    // ---- named buffers / timestamps / rings ----
    public double buf(String k) {
        return buf.getOrDefault(k, 0.0);
    }

    public double bump(String k, double delta) {
        double v = buf.getOrDefault(k, 0.0) + delta;
        if (v < 0) v = 0;
        buf.put(k, v);
        return v;
    }

    public void setBuf(String k, double v) {
        buf.put(k, v);
    }

    public long ts(String k) {
        return ts.getOrDefault(k, 0L);
    }

    public void ts(String k, long v) {
        ts.put(k, v);
    }

    public Ring ring(String k, int size) {
        Ring r = rings.get(k);
        if (r == null) {
            r = new Ring(size);
            rings.put(k, r);
        }
        return r;
    }

    public double totalVl() {
        double t = 0;
        for (double v : vl.values()) t += v;
        return t;
    }

    public void resetMotion() {
        prev = null;
        air = null;
        completedRun = null;
        kb = null;
        airTicks = groundTicks = hoverTicks = ascendTicks = 0;
        lastMoveMs = 0;
        speedDebt = 0;
        timerBal = 0;
        hasGroundY = false;
        gapMs = 0;
        burstN = 0;
        constRot = 0;
    }

    public void clearViolations() {
        vl.clear();
        buf.clear();
        history.clear();
        aiConfidence = 0;
        ai.hotWindows = 0;
    }
}
