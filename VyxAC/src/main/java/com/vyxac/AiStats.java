package com.vyxac;

/** Rolling per-player combat statistics used by the mini AI. Updates are O(1); the heavy maths runs in AiMonitor. */
public final class AiStats {
    /** number of model features */
    public static final int F = 10;

    public final int[] iv = new int[32];
    public int ivN, ivI;
    public long lastHitMs;
    public int aimN, reN, hits, hotWindows, swings, snapHits, snapSamples, reachN;
    public double aimMean, aimM2, reMean, reM2, reachMean, lastScore;

    /** |yaw| / |pitch| change per rotation while fighting (used for the sensitivity-quantisation test) */
    public final double[] rot = new double[48], pit = new double[48];
    public int rotN, rotI, pitN, pitI;

    /** the player's own long-term norm (exponential moving average) */
    public double emaAim = -1, emaMode = -1;
    public int emaN;

    /** features of the latest analysis window, kept so staff decisions can teach the model */
    public double[] lastX;
    public double lastRaw;
    public long lastXAt;

    public void addInterval(int ticks) {
        iv[ivI] = ticks;
        ivI = (ivI + 1) % iv.length;
        if (ivN < iv.length) ivN++;
    }

    public void addAim(double deg) {
        aimN++;
        double d = deg - aimMean;
        aimMean += d / aimN;
        aimM2 += d * (deg - aimMean);
    }

    public void addReaction(double ms) {
        reN++;
        double d = ms - reMean;
        reMean += d / reN;
        reM2 += d * (ms - reMean);
    }

    public void addReach(double dist) {
        reachN++;
        reachMean += (dist - reachMean) / reachN;
    }

    public void addRot(double yawDelta) {
        rot[rotI] = yawDelta;
        rotI = (rotI + 1) % rot.length;
        if (rotN < rot.length) rotN++;
    }

    public void addPitch(double pitchDelta) {
        pit[pitI] = pitchDelta;
        pitI = (pitI + 1) % pit.length;
        if (pitN < pit.length) pitN++;
    }

    public double aimStd() { return aimN > 1 ? Math.sqrt(aimM2 / (aimN - 1)) : 0; }
    public double reStd() { return reN > 1 ? Math.sqrt(reM2 / (reN - 1)) : 0; }

    /** Share of recent click intervals that equal the most common interval (in ticks). */
    public double modeRatio() {
        if (ivN < 12) return 0;
        int[] counts = new int[64];
        int best = 0;
        for (int k = 0; k < ivN; k++) {
            int v = Math.min(63, Math.max(0, iv[k]));
            if (++counts[v] > best) best = counts[v];
        }
        return best / (double) ivN;
    }

    /** Shannon entropy (bits) of the click-interval distribution; machines are far more predictable than hands. -1 = not enough data */
    public double entropy() {
        if (ivN < 24) return -1;
        int[] counts = new int[64];
        for (int k = 0; k < ivN; k++) counts[Math.min(63, Math.max(0, iv[k]))]++;
        double h = 0;
        for (int c : counts) {
            if (c == 0) continue;
            double p = c / (double) ivN;
            h -= p * Math.log(p) / Math.log(2);
        }
        return h;
    }

    private static double gcd(double a, double b) {
        while (b > 0.0008) {
            double t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    /**
     * Mouse input is quantised: every rotation is a whole multiple of a sensitivity dependent step, so the GCD of consecutive
     * rotations keeps returning the same step. Silent aim / killaura rotations are not multiples of anything.
     * Returns the share of consecutive pairs that agree on one step (human ~0.5-0.9, machine ~0), or -1 when there is too little data.
     */
    public static double quantSupport(double[] a, int n) {
        if (n < 24) return -1;
        double[] g = new double[n];
        int m = 0;
        for (int i = 1; i < n; i++) {
            double v = gcd(Math.max(a[i - 1], a[i]), Math.min(a[i - 1], a[i]));
            if (v >= 0.008) g[m++] = v;
        }
        if (m < 6) return 0;
        int best = 0;
        for (int i = 0; i < m; i++) {
            int c = 0;
            for (int j = 0; j < m; j++) if (Math.abs(g[j] - g[i]) <= 0.03 * g[i] + 0.0004) c++;
            if (c > best) best = c;
        }
        return best / (double) (n - 1);
    }

    public void resetWindow() {
        aimN = 0;
        aimMean = aimM2 = 0;
        reN = 0;
        reMean = reM2 = 0;
        hits = 0;
        swings = 0;
        snapHits = 0;
        snapSamples = 0;
        reachN = 0;
        reachMean = 0;
    }
}
