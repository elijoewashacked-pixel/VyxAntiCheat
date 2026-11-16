package com.vyxac;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/**
 * The "mini AI": a very small statistical model, not a neural network, so it costs almost no CPU.
 *
 *  - Combat hooks only do O(1) updates (a few additions per hit).
 *  - Every few seconds each player with enough hits is scored from three features
 *    (click rhythm, aim precision, reaction constancy) plus how far the player is from the server's own
 *    population of clean players (the baseline learns from players that have no violations).
 *  - A hard time budget per second guarantees the analysis can never lag the server; players that do not fit
 *    in the budget are simply analysed on the next run (round robin).
 *
 * The AI never punishes alone: it raises its own alerts and acts as one extra piece of evidence for PunishManager.
 */
public final class AiMonitor {
    /** Online mean / variance (Welford) with a cap so the baseline keeps adapting. */
    private static final class Welford {
        int n;
        double mean, m2;

        void add(double x) {
            if (n >= 4000) { // forget slowly
                m2 *= (n - 1) / (double) n;
                n--;
            }
            n++;
            double d = x - mean;
            mean += d / n;
            m2 += d * (x - mean);
        }

        double std() {
            return n > 1 ? Math.sqrt(m2 / (n - 1)) : 0;
        }
    }

    private final VyxAC pl;
    private BukkitTask task;
    private int intervalSec = 4, minAttacks = 20;
    private double sensitivity = 1.0, alertConf = 0.88;
    private long budgetMicrosPerSec = 3000;

    private final Welford popAimStd = new Welford(), popMode = new Welford();
    private int rr;
    private long runs, skipped, totalNanos, lastRunNanos, analysed;

    public AiMonitor(VyxAC pl) {
        this.pl = pl;
    }

    public void reload(FileConfiguration c) {
        intervalSec = Math.max(1, c.getInt("ai.interval-seconds", 4));
        minAttacks = Math.max(8, c.getInt("ai.min-attacks", 20));
        sensitivity = Math.max(0.2, Math.min(3.0, c.getDouble("ai.sensitivity", 1.0)));
        alertConf = Math.max(0.5, Math.min(0.999, c.getDouble("ai.alert-confidence", 0.88)));
        budgetMicrosPerSec = Math.max(200, c.getLong("ai.max-micros-per-second", 3000));
        stop();
        if (pl.settings().aiEnabled) {
            long period = intervalSec * 20L;
            task = Bukkit.getScheduler().runTaskTimer(pl, this::run, period, period);
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    // ------------------------------------------------------------------ O(1) hooks (called from the combat listener)

    /** @param reactionMs time from the last big camera move to this hit, or -1 when unknown */
    public void onHit(PlayerData pd, double aimErrDeg, long now, double reactionMs) {
        if (!pl.settings().aiEnabled) return;
        AiStats st = pd.ai;
        if (st.lastHitMs != 0) {
            long gap = now - st.lastHitMs;
            if (gap < 1500) st.addInterval((int) Math.round(gap / 50.0));
        }
        st.lastHitMs = now;
        st.hits++;
        if (aimErrDeg >= 0) st.addAim(aimErrDeg);
        if (reactionMs >= 0 && reactionMs < 600) st.addReaction(reactionMs);
    }

    // ------------------------------------------------------------------ periodic analysis

    private void run() {
        if (!pl.settings().aiEnabled) return;
        long t0 = System.nanoTime();
        long budget = budgetMicrosPerSec * 1000L * intervalSec;
        List<PlayerData> list = new ArrayList<>(pl.allData());
        int n = list.size();
        if (n == 0) return;
        long now = System.currentTimeMillis();
        for (int k = 0; k < n; k++) {
            if (System.nanoTime() - t0 > budget) {
                rr = (rr + k) % n;
                skipped++;
                break;
            }
            analyse(list.get((rr + k) % n), now);
        }
        lastRunNanos = System.nanoTime() - t0;
        totalNanos += lastRunNanos;
        runs++;
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private void analyse(PlayerData pd, long now) {
        AiStats st = pd.ai;
        if (st.hits == 0) return;
        if (now - st.lastHitMs > 90_000L) { // stale window: start over
            st.resetWindow();
            st.hotWindows = 0;
            return;
        }
        if (st.hits < minAttacks) return;
        if (pd.bypassed(now, pl.settings())) {
            st.resetWindow();
            return;
        }
        analysed++;

        double mode = st.modeRatio();
        double aimMean = st.aimMean, aimStd = st.aimStd();
        double reStd = st.reStd();

        // feature 1: click rhythm too regular (only meaningful at a real click speed)
        double s1 = mode <= 0 ? 0 : clamp01((mode - 0.55) / 0.40);
        // feature 2: aim too precise AND too consistent
        double s2 = st.aimN >= 10 ? clamp01((3.0 - aimMean) / 3.0) * clamp01((2.5 - aimStd) / 2.5) : 0;
        // feature 3: the delay between camera move and hit is constant and very short
        double s3 = st.reN >= 8 ? clamp01((18.0 - reStd) / 18.0) * (st.reMean < 120 ? 1.0 : 0.3) : 0;
        // feature 4: how unusual is this aim consistency for THIS server's players
        double pop = 0;
        if (popAimStd.n >= 40 && st.aimN >= 10) {
            double z = (popAimStd.mean - aimStd) / Math.max(popAimStd.std(), 0.25);
            pop = clamp01(z / 4.0);
        }

        double x = 0.9 * s1 + 1.2 * s2 + 0.9 * s3 + 0.4 * pop;
        double raw = 1.0 / (1.0 + Math.exp(-5.0 * sensitivity * (x - 1.0)));
        boolean fresh = now - pd.aiConfAt < 60_000L;
        double conf = fresh ? 0.5 * pd.aiConfidence + 0.5 * raw : raw;
        pd.aiConfidence = conf;
        pd.aiConfAt = now;
        st.lastScore = raw;

        // learn the population from clean players only
        boolean clean = pd.totalVl() < 0.5 && now - pd.lastFlagMs > 120_000L && conf < 0.5;
        if (clean) {
            if (st.aimN >= 10) popAimStd.add(aimStd);
            popMode.add(mode);
        }

        if (conf >= alertConf) st.hotWindows++;
        else st.hotWindows = Math.max(0, st.hotWindows - 1);
        if (st.hotWindows >= 2) {
            String info = String.format("conf %.0f%% (rhythm %.2f, aim %.2f, reaction %.2f, pop %.2f)",
                    conf * 100, s1, s2, s3, pop);
            pl.vio().flag(pd, pl.checks().get("AI"), info, 1.0);
        }
        st.resetWindow();
    }

    public void resetPlayer(PlayerData pd) {
        pd.ai.resetWindow();
        pd.ai.hotWindows = 0;
        pd.aiConfidence = 0;
    }

    public String status() {
        double avgMicros = runs == 0 ? 0 : totalNanos / 1000.0 / runs;
        return String.format("runs=%d, avg=%.0fus/run, last=%.0fus, budget=%dus/s, analysed=%d, skipped-runs=%d, baseline-samples=%d",
                runs, avgMicros, lastRunNanos / 1000.0, budgetMicrosPerSec, analysed, skipped, popAimStd.n);
    }
}
