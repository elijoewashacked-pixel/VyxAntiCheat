package com.vyxac;

/** Rolling per-player combat statistics used by the mini AI. O(1) updates, a few hundred bytes per player. */
public final class AiStats {
    public final int[] iv = new int[32];
    public int ivN, ivI;
    public long lastHitMs;
    public int aimN, reN, hits, hotWindows;
    public double aimMean, aimM2, reMean, reM2, lastScore;

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

    public void resetWindow() {
        aimN = 0;
        aimMean = aimM2 = 0;
        reN = 0;
        reMean = reM2 = 0;
        hits = 0;
    }
}
