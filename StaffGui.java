package com.vyxac;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.entity.Vehicle;
import org.bukkit.util.BoundingBox;

/**
 * Block and collision helpers. Everything here is deliberately conservative: whenever the answer is unclear
 * (unloaded chunk, odd block shape) it errs towards "the player is legitimately supported".
 */
public final class Phys {
    private Phys() {}

    /** Result of a ground check. */
    public static final class Support {
        public final Material mat;

        Support(Material mat) {
            this.mat = mat;
        }
    }

    /** Returned when a needed chunk is not loaded: callers must treat the player as supported. */
    public static final Support UNKNOWN = new Support(null);

    public static int floor(double d) {
        int i = (int) d;
        return d < i ? i - 1 : i;
    }

    /** Block at the position, or null when out of world or the chunk is not loaded (never loads chunks). */
    public static Block at(World w, int x, int y, int z) {
        if (y < w.getMinHeight() || y >= w.getMaxHeight()) return null;
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return null;
        return w.getBlockAt(x, y, z);
    }

    private static boolean isTall(Material t) {
        return Tag.FENCES.isTagged(t) || Tag.WALLS.isTagged(t) || Tag.FENCE_GATES.isTagged(t);
    }

    private static int bodyFlags(Block b, boolean checkWaterlogged) {
        if (b == null) return 0;
        Material t = b.getType();
        if (t.isAir()) return 0;
        if (t == Material.WATER) return MoveCtx.WATER;
        if (t == Material.BUBBLE_COLUMN) return MoveCtx.WATER | MoveCtx.BUBBLE;
        if (t == Material.LAVA) return MoveCtx.LAVA;
        if (t == Material.SEAGRASS || t == Material.TALL_SEAGRASS || t == Material.KELP || t == Material.KELP_PLANT) {
            return MoveCtx.WATER;
        }
        if (Tag.CLIMBABLE.isTagged(t)) return MoveCtx.CLIMB;
        if (t == Material.COBWEB || t == Material.SWEET_BERRY_BUSH || t == Material.POWDER_SNOW) return MoveCtx.WEB;
        if (checkWaterlogged && b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged()) return MoveCtx.WATER;
        return 0;
    }

    private static int belowFlags(Block b) {
        if (b == null) return 0;
        Material t = b.getType();
        if (t == Material.ICE || t == Material.PACKED_ICE || t == Material.BLUE_ICE || t == Material.FROSTED_ICE) {
            return MoveCtx.ICE;
        }
        if (t == Material.SLIME_BLOCK || Tag.BEDS.isTagged(t)) return MoveCtx.BOUNCE;
        if (t == Material.HONEY_BLOCK) return MoveCtx.HONEY;
        if (t == Material.SOUL_SAND || t == Material.SOUL_SOIL) return MoveCtx.SOUL;
        return 0;
    }

    /** Bitmask of MoveCtx.* environment flags around the given feet position. */
    public static int env(World w, double x, double y, double z) {
        int bx = floor(x), bz = floor(z);
        int r = 0;
        r |= bodyFlags(at(w, bx, floor(y), bz), true);
        r |= bodyFlags(at(w, bx, floor(y + 0.9), bz), false);
        r |= bodyFlags(at(w, bx, floor(y + 1.62), bz), false);
        r |= belowFlags(at(w, bx, floor(y - 0.2), bz));
        r |= belowFlags(at(w, bx, floor(y - 1.3), bz));
        return r;
    }

    /**
     * Is there a collidable surface whose top is at most {@code tolAbove} below the feet (and at most 0.25 above)?
     * Returns null when nothing supports the player, {@link #UNKNOWN} when a chunk is not loaded.
     */
    public static Support support(World w, double x, double y, double z, double tolAbove) {
        final double r = 0.3, eps = 0.001;
        int x0 = floor(x - r + eps), x1 = floor(x + r - eps);
        int z0 = floor(z - r + eps), z1 = floor(z + r - eps);
        int y0 = floor(y - tolAbove - 1.5), y1 = floor(y + 0.25);
        for (int bx = x0; bx <= x1; bx++) {
            for (int bz = z0; bz <= z1; bz++) {
                if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return UNKNOWN;
                for (int by = y0; by <= y1; by++) {
                    if (by < w.getMinHeight() || by >= w.getMaxHeight()) continue;
                    Block b = w.getBlockAt(bx, by, bz);
                    if (b.isPassable() || b.isLiquid()) continue;
                    BoundingBox bb = b.getBoundingBox();
                    if (bb.getMaxX() <= x - r + eps || bb.getMinX() >= x + r - eps
                            || bb.getMaxZ() <= z - r + eps || bb.getMinZ() >= z + r - eps) {
                        continue;
                    }
                    Material t = b.getType();
                    double top = isTall(t) ? by + 1.5 : bb.getMaxY();
                    double d = top - y;
                    if (d >= -tolAbove && d <= 0.25) return new Support(t);
                    if (Tag.STAIRS.isTagged(t)) { // lower step of a stair
                        double d2 = by + 0.5 - y;
                        if (d2 >= -tolAbove && d2 <= 0.25) return new Support(t);
                    }
                }
            }
        }
        return null;
    }

    /** Does any solid block overlap the given box? Used for Phase. Unloaded chunks count as empty. */
    public static boolean solidBox(World w, double x0, double y0, double z0, double x1, double y1, double z1) {
        for (int bx = floor(x0); bx <= floor(x1); bx++) {
            for (int bz = floor(z0); bz <= floor(z1); bz++) {
                if (!w.isChunkLoaded(bx >> 4, bz >> 4)) continue;
                for (int by = floor(y0); by <= floor(y1); by++) {
                    if (by < w.getMinHeight() || by >= w.getMaxHeight()) continue;
                    Block b = w.getBlockAt(bx, by, bz);
                    if (b.isPassable() || b.isLiquid()) continue;
                    if (b.getType() == Material.SNOW) continue; // layered snow: outline is taller than collision
                    BoundingBox bb = b.getBoundingBox();
                    if (bb.getMaxX() > x0 && bb.getMinX() < x1 && bb.getMaxY() > y0 && bb.getMinY() < y1
                            && bb.getMaxZ() > z0 && bb.getMinZ() < z1) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Is there a solid block directly beside the player at chest height? (distinguishes Spider from Fly) */
    public static boolean wallNear(World w, double x, double y, double z) {
        double[][] o = {{0.45, 0}, {-0.45, 0}, {0, 0.45}, {0, -0.45}};
        for (double[] d : o) {
            Block b = at(w, floor(x + d[0]), floor(y + 0.5), floor(z + d[1]));
            if (b != null && !b.isPassable() && !b.isLiquid()) return true;
        }
        return false;
    }

    /** Solid block just above the player's head (limits jump height legitimately). */
    public static boolean ceilingAbove(World w, double x, double y, double z) {
        Block b = at(w, floor(x), floor(y + 2.1), floor(z));
        return b != null && !b.isPassable() && !b.isLiquid();
    }

    /** Solid block a short distance in front of the player along (dx, dz) at feet or head height. */
    public static boolean blockedAhead(World w, double x, double y, double z, double dx, double dz) {
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) return false;
        double nx = dx / len, nz = dz / len;
        for (double dist = 0.5; dist <= 1.2; dist += 0.35) {
            for (double hy = 0.2; hy <= 1.6; hy += 1.4) {
                Block b = at(w, floor(x + nx * dist), floor(y + hy), floor(z + nz * dist));
                if (b != null && !b.isPassable() && !b.isLiquid()) return true;
            }
        }
        return false;
    }

    /** Boats, minecarts, shulkers and armor stands can carry a player without any block below. (expensive: call lazily) */
    public static boolean entitySupport(Player p) {
        try {
            for (Entity e : p.getNearbyEntities(0.9, 1.6, 0.9)) {
                if (e instanceof Vehicle || e instanceof Shulker || e instanceof ArmorStand) return true;
            }
        } catch (Throwable ignored) {
            // treat as supported on any API trouble
            return true;
        }
        return false;
    }
}
