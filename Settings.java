package com.vyxac;

/** Immutable snapshot of one movement event. */
public final class MoveCtx {
    public static final int WATER = 1, LAVA = 2, CLIMB = 4, WEB = 8, ICE = 16, BOUNCE = 32, HONEY = 64, SOUL = 128, BUBBLE = 256;
    /** environments in which normal gravity / ground rules do not apply */
    public static final int SPECIAL = WATER | LAVA | CLIMB | WEB | BOUNCE | HONEY | BUBBLE;

    public final long time;
    public final double x, y, z;
    public final float yaw, pitch;
    public final int env;
    public final boolean supported;

    public MoveCtx(long time, double x, double y, double z, float yaw, float pitch, int env, boolean supported) {
        this.time = time;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.env = env;
        this.supported = supported;
    }
}
