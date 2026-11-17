package com.vyxac;

import org.bukkit.scheduler.BukkitRunnable;

/** Measures TPS and detects long ticks so flags caused by server lag can be ignored. */
public final class TickMonitor {
    private final VyxAC plugin;
    private long lastTickNano, lastSampleNano;
    private long lagUntilMs;
    private double tps = 20.0;
    private double sample1 = 20.0, sample2 = 20.0;
    private volatile long tick;

    public TickMonitor(VyxAC plugin) {
        this.plugin = plugin;
    }

    public void start() {
        new BukkitRunnable() {
            @Override
            public void run() {
                onTick();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void onTick() {
        long now = System.nanoTime();
        tick++;
        if (lastTickNano != 0 && (now - lastTickNano) > 120_000_000L) {
            lagUntilMs = System.currentTimeMillis() + 3000L;
        }
        lastTickNano = now;
        if (tick % 20 == 0) {
            if (lastSampleNano != 0) {
                double ms = (now - lastSampleNano) / 1_000_000.0;
                double s = Math.min(20.0, 20.0 * 1000.0 / Math.max(1.0, ms));
                sample2 = sample1;
                sample1 = s;
                tps = (s + sample1 + sample2) / 3.0;
            }
            lastSampleNano = now;
        }
    }

    public long tick() {
        return tick;
    }

    public double tps() {
        return tps;
    }

    /** True while the server is lagging (low TPS or a recent long tick). */
    public boolean unstable() {
        return System.currentTimeMillis() < lagUntilMs || tps < plugin.settings().minTps;
    }

    public boolean recentLag(long ms) {
        return System.currentTimeMillis() < lagUntilMs - 3000L + ms;
    }
}
