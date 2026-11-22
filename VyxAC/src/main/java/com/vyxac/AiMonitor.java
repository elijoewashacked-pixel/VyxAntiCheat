package com.vyxac;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The AI: a compact statistical model that scores every fighting player on ten behavioural features.
 *
 *  features : click rhythm, aim precision, reaction constancy, population outlier, click entropy, mouse-step quantisation,
 *             hit accuracy, snap-to-target, average reach, and a sudden change against the player's own history.
 *  model    : logistic score over the features. The weights LEARN: every time staff punish a player (cheater) or dismiss one
 *             the AI flagged (clean), and every time a hack client is confirmed by the client-mod check, the weights move
 *             a little. The learned model is saved in plugins/VyxAC/ai-model.yml.
 *  cost     : hooks are O(1); the analysis is time-budgeted (ai.max-micros-per-second) and round-robin.
 *  safety   : the AI never punishes on its own; it raises its own alerts and counts as one extra piece of evidence.
 */
public final class AiMonitor {
    public static final String[] NAMES = {"click rhythm", "aim precision", "reaction constancy", "population outlier", "click entropy",
            "mouse step quantisation", "hit accuracy", "snap to target", "average reach", "change vs own history"};
    private static final double[] PRIOR = {0.9, 1.2, 0.9, 0.4, 0.7, 1.0, 0.5, 0.9, 0.7, 0.6};

    /** Online mean / variance (Welford) with a cap so the baseline keeps adapting. */
    private static final class Welford {
        int n;
        double mean, m2;

        void add(double x) {
            if (n >= 4000) {
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
    private final File modelFile;
    private BukkitTask task;
    private int intervalSec = 3, minAttacks = 16;
    private double sensitivity = 1.0, alertConf = 0.88;
    private long budgetMicrosPerSec = 8000;
    private boolean learning = true, deep = true;

    private final double[] w = PRIOR.clone();
    private double bias = 1.0;
    private int labels;
    private boolean dirty;
    private long lastSave = System.currentTimeMillis();

    private final Welford popAimStd = new Welford(), popMode = new Welford();
    private int rr;
    private long runs, skipped, totalNanos, lastRunNanos, analysed;

    public AiMonitor(VyxAC pl) {
        this.pl = pl;
        this.modelFile = new File(pl.getDataFolder(), "ai-model.yml");
    }

    public void reload(FileConfiguration c) {
        intervalSec = Math.max(1, c.getInt("ai.interval-seconds", 3));
        minAttacks = Math.max(8, c.getInt("ai.min-attacks", 16));
        sensitivity = Math.max(0.2, Math.min(3.0, c.getDouble("ai.sensitivity", 1.0)));
        alertConf = Math.max(0.5, Math.min(0.999, c.getDouble("ai.alert-confidence", 0.88)));
        budgetMicrosPerSec = Math.max(200, c.getLong("ai.max-micros-per-second", 8000));
        learning = c.getBoolean("ai.learning", true);
        deep = c.getBoolean("ai.deep", true);
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

    // ------------------------------------------------------------------ model persistence

    public void loadModel() {
        if (!modelFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(modelFile);
        List<Double> ws = y.getDoubleList("weights");
        if (ws.size() == w.length) for (int i = 0; i < w.length; i++) w[i] = Math.max(0, Math.min(3, ws.get(i)));
        bias = Math.max(0.5, Math.min(2.0, y.getDouble("bias", 1.0)));
        labels = y.getInt("labels", 0);
        popAimStd.n = y.getInt("pop.aim.n", 0);
        popAimStd.mean = y.getDouble("pop.aim.mean", 0);
        popAimStd.m2 = y.getDouble("pop.aim.m2", 0);
        popMode.n = y.getInt("pop.mode.n", 0);
        popMode.mean = y.getDouble("pop.mode.mean", 0);
        popMode.m2 = y.getDouble("pop.mode.m2", 0);
    }

    public void saveModel() {
        YamlConfiguration y = new YamlConfiguration();
        List<Double> ws = new ArrayList<>();
        for (double v : w) ws.add(v);
        y.set("weights", ws);
        y.set("bias", bias);
        y.set("labels", labels);
        y.set("pop.aim.n", popAimStd.n);
        y.set("pop.aim.mean", popAimStd.mean);
        y.set("pop.aim.m2", popAimStd.m2);
        y.set("pop.mode.n", popMode.n);
        y.set("pop.mode.mean", popMode.mean);
        y.set("pop.mode.m2", popMode.m2);
        try {
            modelFile.getParentFile().mkdirs();
            y.save(modelFile);
            dirty = false;
            lastSave = System.currentTimeMillis();
        } catch (IOException ex) {
            pl.getLogger().warning("Could not save ai-model.yml: " + ex.getMessage());
        }
    }

    public void resetModel() {
        System.arraycopy(PRIOR, 0, w, 0, w.length);
        bias = 1.0;
        labels = 0;
        popAimStd.n = popMode.n = 0;
        popAimStd.mean = popAimStd.m2 = popMode.mean = popMode.m2 = 0;
        saveModel();
    }

    // ------------------------------------------------------------------ O(1) hooks

    /** @param reactionMs time from the last big camera move to this hit, or -1 when unknown */
    public void onHit(PlayerData pd, double aimErrDeg, long now, double reactionMs) {
        onHitEx(pd, aimErrDeg, now, reactionMs, 0, -1);
    }

    /**
     * @param preRotDeg size of the last big camera move before the hit
     * @param reachDist lag-compensated distance to the target, or -1 when unknown
     */
    public void onHitEx(PlayerData pd, double aimErrDeg, long now, double reactionMs, double preRotDeg, double reachDist) {
        if (!pl.settings().aiEnabled) return;
        AiStats st = pd.ai;
        if (st.lastHitMs != 0) {
            long gap = now - st.lastHitMs;
            if (gap < 1500) st.addInterval((int) Math.round(gap / 50.0));
        }
        st.lastHitMs = now;
        st.hits++;
        if (aimErrDeg >= 0) {
            st.addAim(aimErrDeg);
            st.snapSamples++;
            if (preRotDeg > 25 && aimErrDeg < 2.0) st.snapHits++; // big flick that ends exactly on the target
        }
        if (reactionMs >= 0 && reactionMs < 600) st.addReaction(reactionMs);
        if (reachDist >= 0) st.addReach(reachDist);
    }

    /** every arm swing: used for hit accuracy while fighting */
    public void onSwing(PlayerData pd, long now) {
        AiStats st = pd.ai;
        if (now - st.lastHitMs < 2000) st.swings++;
    }

    /** every camera move while fighting */
    public void onRotation(PlayerData pd, double yawDelta, double pitchDelta) {
        AiStats st = pd.ai;
        double ay = Math.abs(yawDelta), ap = Math.abs(pitchDelta);
        if (ay > 0.02 && ay < 120) st.addRot(ay);
        if (ap > 0.02 && ap < 90) st.addPitch(ap);
    }

    // ------------------------------------------------------------------ periodic analysis

    private void run() {
        if (!pl.settings().aiEnabled) return;
        long t0 = System.nanoTime();
        long budget = budgetMicrosPerSec * 1000L * intervalSec;
        List<PlayerData> list = new ArrayList<>(pl.allData());
        int n = list.size();
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
        if (learning && dirty && now - lastSave > 300_000L) saveModel();
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static double sigmoid(double v) {
        return 1.0 / (1.0 + Math.exp(-v));
    }

    /** builds the feature vector of the current window */
    private double[] features(PlayerData pd) {
        AiStats st = pd.ai;
        double[] x = new double[AiStats.F];
        double mode = st.modeRatio();
        double aimMean = st.aimMean, aimStd = st.aimStd(), reStd = st.reStd();

        x[0] = mode <= 0 ? 0 : clamp01((mode - 0.55) / 0.40);
        x[1] = st.aimN >= 10 ? clamp01((3.0 - aimMean) / 3.0) * clamp01((2.5 - aimStd) / 2.5) : 0;
        x[2] = st.reN >= 8 ? clamp01((18.0 - reStd) / 18.0) * (st.reMean < 120 ? 1.0 : 0.3) : 0;
        if (popAimStd.n >= 40 && st.aimN >= 10) x[3] = clamp01(((popAimStd.mean - aimStd) / Math.max(popAimStd.std(), 0.25)) / 4.0);
        if (!deep) return x;

        double h = st.entropy();
        x[4] = h < 0 ? 0 : clamp01((1.7 - h) / 1.2);
        double sup = AiStats.quantSupport(st.rot, st.rotN);
        double supP = AiStats.quantSupport(st.pit, st.pitN);
        double q = sup < 0 ? supP : (supP < 0 ? sup : Math.min(sup, supP));
        x[5] = q < 0 ? 0 : clamp01((0.35 - q) / 0.30);
        if (st.swings >= 25) x[6] = clamp01((st.hits / (double) st.swings - 0.85) / 0.15);
        if (st.snapSamples >= 12) x[7] = clamp01((st.snapHits / (double) st.snapSamples - 0.35) / 0.40);
        if (st.reachN >= 8) x[8] = clamp01((st.reachMean - 2.85) / 0.50);
        if (st.emaN >= 6 && st.emaAim > 0.5 && st.aimN >= 10) x[9] = clamp01(((st.emaAim - aimStd) / st.emaAim - 0.45) / 0.40);
        return x;
    }

    private double dot(double[] x) {
        double d = 0;
        for (int i = 0; i < x.length; i++) d += w[i] * x[i];
        return d;
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

        double[] x = features(pd);
        double raw = sigmoid(5.0 * sensitivity * (dot(x) - bias));
        boolean fresh = now - pd.aiConfAt < 60_000L;
        double conf = fresh ? 0.5 * pd.aiConfidence + 0.5 * raw : raw;
        pd.aiConfidence = conf;
        pd.aiConfAt = now;
        st.lastScore = raw;
        st.lastX = x;
        st.lastRaw = raw;
        st.lastXAt = now;

        double aimStd = st.aimStd(), mode = st.modeRatio();
        // learn what "normal" looks like on this server from clean players only
        boolean clean = pd.totalVl() < 0.5 && now - pd.lastFlagMs > 120_000L && conf < 0.5;
        if (clean) {
            if (st.aimN >= 10) popAimStd.add(aimStd);
            popMode.add(mode);
            dirty = true;
        }
        // the player's own long-term norm (updated with every window that is not already suspicious)
        if (conf < 0.6 && st.aimN >= 10) {
            st.emaAim = st.emaAim < 0 ? aimStd : 0.85 * st.emaAim + 0.15 * aimStd;
            st.emaMode = st.emaMode < 0 ? mode : 0.85 * st.emaMode + 0.15 * mode;
            st.emaN++;
        }

        if (conf >= alertConf) st.hotWindows++;
        else st.hotWindows = Math.max(0, st.hotWindows - 1);
        if (st.hotWindows >= 2) {
            StringBuilder top = new StringBuilder();
            int shown = 0;
            for (int i = 0; i < x.length && shown < 3; i++) {
                if (x[i] >= 0.5) {
                    if (shown++ > 0) top.append(", ");
                    top.append(NAMES[i]).append(' ').append(String.format("%.2f", x[i]));
                }
            }
            pl.vio().flag(pd, pl.checks().get("AI"), String.format("conf %.0f%% (%s)", conf * 100, top.length() == 0 ? "weak signals combined" : top), 1.0);
        }
        st.resetWindow();
    }

    // ------------------------------------------------------------------ learning from staff / confirmed hack clients

    /**
     * Teaches the model. cheater=true: staff punished the player or a hack client was confirmed.
     * cheater=false: staff dismissed a player the AI had flagged.
     * @return true when the model was updated
     */
    public boolean label(PlayerData pd, boolean cheater, String source) {
        if (!learning || !pl.settings().aiEnabled) return false;
        AiStats st = pd.ai;
        long now = System.currentTimeMillis();
        if (st.lastX == null || now - st.lastXAt > 600_000L) return false;
        if (!cheater && pd.aiConfidence < 0.3) return false; // nothing to correct
        double y = cheater ? 1.0 : 0.0;
        double p = st.lastRaw;
        double g = y - p;
        double lr = 0.08 / (1.0 + labels / 150.0) + 0.01;
        for (int i = 0; i < w.length; i++) w[i] = Math.max(0, Math.min(3, w[i] + lr * g * st.lastX[i]));
        bias = Math.max(0.5, Math.min(2.0, bias - lr * g * 0.3));
        labels++;
        dirty = true;
        pl.vio().log("AI-LEARN " + pd.name + " label=" + (cheater ? "cheater" : "clean") + " source=" + source + " p=" + String.format("%.2f", p));
        if (labels % 10 == 0) saveModel();
        return true;
    }

    public List<String> explain(PlayerData pd) {
        List<String> out = new ArrayList<>();
        AiStats st = pd.ai;
        out.add("AI explanation for " + pd.name + ": confidence " + String.format("%.0f%%", pd.aiConfidence * 100)
                + " (model learned from " + labels + " staff decisions)");
        if (st.lastX == null) {
            out.add("No analysed window yet: the player needs " + minAttacks + " hits in a fight.");
            return out;
        }
        double total = 0;
        for (int i = 0; i < st.lastX.length; i++) {
            double c = w[i] * st.lastX[i];
            total += c;
            out.add(String.format("%-24s signal %.2f x weight %.2f = %.2f", NAMES[i], st.lastX[i], w[i], c));
        }
        out.add(String.format("Sum %.2f against bias %.2f -> raw score %.0f%%", total, bias, st.lastRaw * 100));
        return out;
    }

    public void resetPlayer(PlayerData pd) {
        pd.ai.resetWindow();
        pd.ai.hotWindows = 0;
        pd.ai.lastX = null;
        pd.ai.emaAim = pd.ai.emaMode = -1;
        pd.ai.emaN = 0;
        pd.aiConfidence = 0;
    }

    public String status() {
        double avgMicros = runs == 0 ? 0 : totalNanos / 1000.0 / runs;
        return String.format("runs=%d, avg=%.0fus/run, last=%.0fus, budget=%dus/s, analysed=%d, skipped-runs=%d, labels=%d, baseline=%d, %s",
                runs, avgMicros, lastRunNanos / 1000.0, budgetMicrosPerSec, analysed, skipped, labels, popAimStd.n, deep ? "deep" : "light");
    }
}
