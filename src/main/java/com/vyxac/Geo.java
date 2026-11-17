package com.vyxac;

/** Plain-double geometry helpers (no allocations in hot paths). Minecraft yaw/pitch convention. */
public final class Geo {
    private Geo() {}

    public static double dirX(float yaw, float pitch) {
        return -Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch));
    }

    public static double dirY(float pitch) {
        return -Math.sin(Math.toRadians(pitch));
    }

    public static double dirZ(float yaw, float pitch) {
        return Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch));
    }

    /** Wraps an angle difference into [-180, 180]. */
    public static float wrap(float a) {
        a %= 360f;
        if (a >= 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    /** Shortest distance from point (px,py,pz) to the axis aligned box. 0 when inside. */
    public static double pointBoxDist(double px, double py, double pz,
                                      double x0, double y0, double z0, double x1, double y1, double z1) {
        double dx = Math.max(Math.max(x0 - px, 0), px - x1);
        double dy = Math.max(Math.max(y0 - py, 0), py - y1);
        double dz = Math.max(Math.max(z0 - pz, 0), pz - z1);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Slab test. True when the ray (origin o, direction d, length 0..maxT) hits the box. */
    public static boolean rayHitsBox(double ox, double oy, double oz, double dx, double dy, double dz, double maxT,
                                     double x0, double y0, double z0, double x1, double y1, double z1) {
        double tmin = 0, tmax = maxT;
        double[] o = {ox, oy, oz}, d = {dx, dy, dz}, lo = {x0, y0, z0}, hi = {x1, y1, z1};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-9) {
                if (o[i] < lo[i] || o[i] > hi[i]) return false;
            } else {
                double t1 = (lo[i] - o[i]) / d[i], t2 = (hi[i] - o[i]) / d[i];
                if (t1 > t2) {
                    double t = t1;
                    t1 = t2;
                    t2 = t;
                }
                tmin = Math.max(tmin, t1);
                tmax = Math.min(tmax, t2);
                if (tmin > tmax) return false;
            }
        }
        return true;
    }

    /** Angle in degrees between direction (dx,dy,dz) and the vector (vx,vy,vz). */
    public static double angleDeg(double dx, double dy, double dz, double vx, double vy, double vz) {
        double dl = Math.sqrt(dx * dx + dy * dy + dz * dz), vl = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (dl < 1e-9 || vl < 1e-9) return 0;
        double c = (dx * vx + dy * vy + dz * vz) / (dl * vl);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, c))));
    }
}
