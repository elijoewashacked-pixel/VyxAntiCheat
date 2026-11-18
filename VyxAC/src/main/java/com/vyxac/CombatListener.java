package com.vyxac;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

/**
 * Combat checks. Geometry checks (Reach, Hitbox, Backtrack, KillAura angle) only run on player-vs-player hits,
 * use the attacker's ping to look back at the target's recent positions (lag compensation) and use wide tolerances.
 */
public final class CombatListener implements Listener {
    private final VyxAC pl;
    private final CheckInfo KA, AIM, AC, TRIG, REACH, HITBOX, CRIT, FASTBOW, AUTOPOT, TOTEM, MULTI, CRYSTAL, BACK;

    public CombatListener(VyxAC pl) {
        this.pl = pl;
        CheckRegistry r = pl.checks();
        KA = r.get("KillAura");
        AIM = r.get("AimAssist");
        AC = r.get("AutoClicker");
        TRIG = r.get("TriggerBot");
        REACH = r.get("Reach");
        HITBOX = r.get("Hitbox");
        CRIT = r.get("Criticals");
        FASTBOW = r.get("FastBow");
        AUTOPOT = r.get("AutoPot");
        TOTEM = r.get("AutoTotem");
        MULTI = r.get("MultiAura");
        CRYSTAL = r.get("AutoCrystal");
        BACK = r.get("Backtrack");
    }

    // ===================================================================================== helpers

    /** Smallest distance from the eye to the target's hitbox over the lag-compensation window. */
    private static double minDist(PlayerData t, Player tp, double ex, double ey, double ez, long now, long windowMs) {
        double hw = 0.3 * t.scale, hh = 1.8 * t.scale;
        Location cur = tp.getLocation();
        double best = Geo.pointBoxDist(ex, ey, ez, cur.getX() - hw, cur.getY(), cur.getZ() - hw, cur.getX() + hw, cur.getY() + hh, cur.getZ() + hw);
        for (int i = 0; i < t.hCount; i++) {
            int k = t.histIdx(i);
            boolean past = now - t.hT[k] > windowMs; // the first sample older than the window was still valid at its start
            double d = Geo.pointBoxDist(ex, ey, ez, t.hX[k] - hw, t.hY[k], t.hZ[k] - hw, t.hX[k] + hw, t.hY[k] + hh, t.hZ[k] + hw);
            if (d < best) best = d;
            if (past) break;
        }
        return best;
    }

    private static boolean rayHit(double ox, double oy, double oz, double dx, double dy, double dz, double maxT,
                                  PlayerData t, Player tp, long now, long windowMs, double expand) {
        double hw = 0.3 * t.scale + expand, hh = 1.8 * t.scale + expand;
        Location cur = tp.getLocation();
        if (Geo.rayHitsBox(ox, oy, oz, dx, dy, dz, maxT, cur.getX() - hw, cur.getY() - expand, cur.getZ() - hw,
                cur.getX() + hw, cur.getY() + hh, cur.getZ() + hw)) {
            return true;
        }
        for (int i = 0; i < t.hCount; i++) {
            int k = t.histIdx(i);
            boolean past = now - t.hT[k] > windowMs;
            if (Geo.rayHitsBox(ox, oy, oz, dx, dy, dz, maxT, t.hX[k] - hw, t.hY[k] - expand, t.hZ[k] - hw,
                    t.hX[k] + hw, t.hY[k] + hh, t.hZ[k] + hw)) {
                return true;
            }
            if (past) break;
        }
        return false;
    }

    /** Entry distance of the ray into the target box over the lag window (smallest = most lenient), or -1 */
    private static double rayHitT(double ox, double oy, double oz, double dx, double dy, double dz, double maxT,
                                  PlayerData t, Player tp, long now, long windowMs, double expand) {
        double hw = 0.3 * t.scale + expand, hh = 1.8 * t.scale + expand;
        Location cur = tp.getLocation();
        double best = Geo.rayBoxT(ox, oy, oz, dx, dy, dz, maxT, cur.getX() - hw, cur.getY() - expand, cur.getZ() - hw,
                cur.getX() + hw, cur.getY() + hh, cur.getZ() + hw);
        for (int i = 0; i < t.hCount; i++) {
            int k = t.histIdx(i);
            boolean past = now - t.hT[k] > windowMs;
            double r = Geo.rayBoxT(ox, oy, oz, dx, dy, dz, maxT, t.hX[k] - hw, t.hY[k] - expand, t.hZ[k] - hw,
                    t.hX[k] + hw, t.hY[k] + hh, t.hZ[k] + hw);
            if (r >= 0 && (best < 0 || r < best)) best = r;
            if (past) break;
        }
        return best;
    }

    /** Walks along the camera ray like the client does: 0 = clear, 1 = a cobweb is in the way, 2 = a solid wall is in the way. */
    private static int occlusion(World w, double ox, double oy, double oz, double dx, double dy, double dz, double t) {
        if (t < 1.0) return 0;
        int webRun = 0, wallRun = 0;
        for (double d = 0.35; d < t - 0.35; d += 0.2) { // ends and corners are ignored on purpose
            Block b = Phys.at(w, Phys.floor(ox + dx * d), Phys.floor(oy + dy * d), Phys.floor(oz + dz * d));
            if (b == null) {
                webRun = wallRun = 0;
                continue;
            }
            Material m = b.getType();
            if (m == Material.COBWEB) {
                wallRun = 0;
                if (++webRun >= 2) return 1;
            } else if (m.isOccluding()) {
                webRun = 0;
                if (++wallRun >= 4) return 2;
            } else {
                webRun = wallRun = 0;
            }
        }
        return 0;
    }

    // ===================================================================================== swings

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        PlayerData pd = pl.data(e.getPlayer());
        pd.lastSwingMs = System.currentTimeMillis();
        pd.evSwings++;
    }

    // ===================================================================================== hits

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        Settings s = pl.settings();
        if (!s.enabled) return;
        PlayerData pd = pl.data(p);
        long now = System.currentTimeMillis();
        if (pd.bypassed(now, s)) return;
        Entity target = e.getEntity();
        long prevAttack = pd.lastAttackMs;
        pd.lastAttackMs = now;
        if (p.getInventory().getItemInMainHand().getType().name().equals("MACE")) pd.lastLaunch = now; // wind burst launches the attacker

        try {
            if (target instanceof EnderCrystal) {
                if (crystal(pd, target, now)) e.setCancelled(true);
                return;
            }
            if (s.requireSwing && now - pd.lastSwingMs > 1200) return; // damage that did not come from a real click (plugins)
            GameMode gm = p.getGameMode();
            if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;
            if (s.lagProtection && pl.ticks().unstable()) return;
            if (pd.ping > s.maxPing || now - pd.lastTeleport < 1000 || now - pd.lastRespawn < 2000 || pd.exotic) return;
            if (analyse(e, p, pd, target, now, prevAttack)) e.setCancelled(true);
        } catch (Throwable t) {
            if (now - pd.ts("err") > 60_000L) {
                pd.ts("err", now);
                pl.getLogger().warning("Combat check error: " + t);
            }
        }
    }

    /** returns true when the hit should be cancelled (mitigation) */
    private boolean analyse(EntityDamageByEntityEvent e, Player p, PlayerData pd, Entity target, long now, long prevAttack) {
        Settings s = pl.settings();
        boolean cancel = false;
        Player tp = target instanceof Player t ? t : null;
        PlayerData pd2 = tp != null ? pl.dataOrNull(tp.getUniqueId()) : null;

        Location eye = p.getEyeLocation();
        double ex = eye.getX(), ey = eye.getY(), ez = eye.getZ();
        float yaw = eye.getYaw(), pitch = eye.getPitch();
        double ldx = Geo.dirX(yaw, pitch), ldy = Geo.dirY(pitch), ldz = Geo.dirZ(yaw, pitch);
        BoundingBox tb = target.getBoundingBox();
        double aimErr = Geo.angleDeg(ldx, ldy, ldz, tb.getCenterX() - ex, tb.getCenterY() - ey, tb.getCenterZ() - ez);
        double dist = Geo.pointBoxDist(ex, ey, ez, tb.getMinX(), tb.getMinY(), tb.getMinZ(), tb.getMaxX(), tb.getMaxY(), tb.getMaxZ());
        double reaction = pd.lastBigRot != 0 && now - pd.lastBigRot < 600 ? now - pd.lastBigRot : -1;

        // ---------------------------------------------------------------- AutoClicker (hit rhythm / speed)
        Ring ivr = pd.ring("iv", 30);
        Ring times = pd.ring("hits", 40);
        if (prevAttack != 0 && now - prevAttack < 1500) ivr.add(Math.round((now - prevAttack) / 50.0));
        times.add(now);
        if (AC.enabled) {
            int cnt = 0;
            for (int k = 0; k < times.size(); k++) if (now - (long) times.get(k) <= 1000) cnt++;
            if (cnt > AC.p("max-cps")) {
                if (pd.bump("ac_cps", 1) >= 2) {
                    pd.setBuf("ac_cps", 0);
                    cancel |= pl.vio().flag(pd, AC, cnt + " hits in one second", 1.5);
                }
            } else {
                pd.bump("ac_cps", -0.5);
            }
            if (ivr.full()) {
                int[] counts = new int[32];
                int best = 0;
                for (int k = 0; k < ivr.size(); k++) {
                    int v = (int) Math.min(31, Math.max(0, ivr.get(k)));
                    if (++counts[v] > best) best = counts[v];
                }
                double ratio = best / (double) ivr.size();
                double meanTicks = ivr.mean();
                if (ratio >= AC.p("mode-ratio") && meanTicks > 0 && meanTicks <= 20.0 / AC.p("min-cps")) {
                    if (pd.bump("ac_mode", 1) >= 2) {
                        pd.setBuf("ac_mode", 0);
                        ivr.clear();
                        cancel |= pl.vio().flag(pd, AC, String.format("%.0f%% of clicks have the same delay", ratio * 100), 1.0);
                    }
                } else {
                    pd.bump("ac_mode", -0.25);
                }
            }
        }

        // ---------------------------------------------------------------- mini AI sample
        pl.ai().onHit(pd, aimErr, now, reaction);

        // ---------------------------------------------------------------- TriggerBot (constant, very short reaction)
        if (TRIG.enabled && reaction >= 0) {
            Ring rr = pd.ring("react", (int) Math.max(6, TRIG.p("samples")));
            rr.add(reaction);
            if (rr.full() && rr.mean() < TRIG.p("max-reaction-ms") && rr.std() < TRIG.p("max-std-ms")) {
                String info = String.format("camera-to-hit delay %.0fms +-%.0f over %d hits", rr.mean(), rr.std(), rr.size());
                rr.clear();
                cancel |= pl.vio().flag(pd, TRIG, info, 1.0);
            }
        }

        // ---------------------------------------------------------------- Criticals (fake airborne state)
        if (CRIT.enabled) {
            double fd = p.getFallDistance();
            Location l = p.getLocation();
            boolean candidate = !p.isOnGround() && fd > 0 && fd < CRIT.p("max-fall") && pd.airTicks <= 1
                    && !p.isInsideVehicle() && !p.isSwimming() && !pd.levitating && !pd.slowFalling
                    && l.getWorld() != null && Phys.env(l.getWorld(), l.getX(), l.getY(), l.getZ()) == 0
                    && Phys.support(l.getWorld(), l.getX(), l.getY(), l.getZ(), 0.25) != null
                    && Phys.support(l.getWorld(), l.getX(), l.getY(), l.getZ(), 0.25) != Phys.UNKNOWN;
            if (candidate) {
                if (pd.bump("crit", 1) >= CRIT.p("buffer")) {
                    pd.setBuf("crit", 0);
                    cancel |= pl.vio().flag(pd, CRIT, String.format("falling %.2f blocks while standing on the ground", fd), 1.0);
                }
            } else {
                pd.bump("crit", -0.5);
            }
        }

        // ---------------------------------------------------------------- MultiAura / KillAura target switching
        boolean otherTarget = pd.lastTargetId != null && !pd.lastTargetId.equals(target.getUniqueId());
        if (otherTarget && MULTI.enabled && now - pd.lastTargetMs <= MULTI.p("same-tick-ms")) {
            cancel |= pl.vio().flag(pd, MULTI, "two different targets hit within " + (now - pd.lastTargetMs) + "ms", 1.0);
        }
        if (KA.enabled) {
            if (otherTarget && now - pd.lastTargetMs < KA.p("switch-ms")) {
                double dy = Math.abs(Geo.wrap(yaw - pd.lastHitYaw));
                if (dy > KA.p("switch-angle")) {
                    if (pd.bump("ka_sw", 1) >= KA.p("buffer")) {
                        pd.setBuf("ka_sw", 0);
                        cancel |= pl.vio().flag(pd, KA, String.format("snapped %.0f degrees to a new target in %dms", dy, now - pd.lastTargetMs), 1.0);
                    }
                } else {
                    pd.bump("ka_sw", -0.35);
                }
            } else {
                pd.bump("ka_sw", -0.1);
            }
            if (dist > KA.p("min-distance") && aimErr > KA.p("max-angle")) {
                cancel |= pl.vio().flag(pd, KA, String.format("hit a target %.0f degrees away from the crosshair", aimErr), 2.0);
            }
        }
        pd.lastTargetId = target.getUniqueId();
        pd.lastTargetMs = now;
        pd.lastHitYaw = yaw;

        // ---------------------------------------------------------------- geometry (player vs player only)
        if (tp == null || pd2 == null || pd2.exotic || pd2.hCount < 3 || tp.isInsideVehicle() || tp.isGliding()
                || now - pd2.lastTeleport < 1500 || now - pd2.lastRespawn < 2000 || !tp.getWorld().equals(p.getWorld())) {
            return cancel;
        }
        long normal = Math.max(100, Math.min(s.maxRewindMs, pd.ping + s.rewindExtraMs));
        double limit = pd.reachEntity + REACH.p("tolerance");
        double minNorm = minDist(pd2, tp, ex, ey, ez, now, normal);

        // ---- Reach statistic: legit fighters average well below 3 blocks, a reach cheater hovers just above it
        if (REACH.enabled) {
            Ring ra = pd.ring("reachAvg", (int) Math.max(4, REACH.p("avg-hits")));
            ra.add(minNorm);
            if (ra.full() && ra.mean() > REACH.p("avg-limit")) {
                String info = String.format("average hit distance %.2f blocks over %d hits", ra.mean(), ra.size());
                ra.clear();
                cancel |= pl.vio().flag(pd, REACH, info, 1.5);
            }
        }

        // ---- Reach / Backtrack: out of range even with the lag-compensated positions of the target
        if (minNorm > limit) {
            long ext = normal + (long) BACK.p("extra-ms");
            double minExt = minDist(pd2, tp, ex, ey, ez, now, ext);
            boolean rescued = minExt <= limit; // a slightly older position would have been in range
            boolean blatant = minNorm > limit + REACH.p("blatant"); // too far for lag to explain
            if (!rescued || blatant) {
                if (REACH.enabled && pd.bump("reach", 1) >= REACH.p("buffer")) {
                    pd.setBuf("reach", 0);
                    cancel |= pl.vio().flag(pd, REACH, String.format("%.2f blocks (limit %.2f)", minNorm, limit), Math.min(4.0, 1.0 + (minNorm - limit) * 2));
                }
            } else if (BACK.enabled && pd.bump("back", 1) >= BACK.p("buffer")) {
                pd.setBuf("back", 0);
                cancel |= pl.vio().flag(pd, BACK, String.format("hit needs a position older than %dms (%.2f blocks away now)", normal, minNorm), 1.0);
            }
        } else {
            pd.bump("reach", -0.5);
            pd.bump("back", -0.5);
        }

        // ---- Hitbox: the crosshair must have been on the target. The server's copy of the camera can lag the client's by one
        // flick, so every angle swept since the previous movement packet is accepted (flicks stay legal, silent aim does not).
        if (HITBOX.enabled && dist > 0.5 && now - pd.lastBigRot >= HITBOX.p("flick-ms")) {
            double maxT = Math.max(limit, minNorm) + 1.5;
            double expand = HITBOX.p("expand");
            MoveCtx prev = pd.prev;
            boolean usePrev = prev != null && now - prev.time < 150;
            double eh = p.getEyeHeight();
            double[] best = null; // {t, originXYZ, directionXYZ}
            int rays = usePrev ? 6 : 1;
            for (int i = 0; i < rays && best == null; i++) {
                float yw = yaw, pt = pitch;
                if (i > 0) {
                    float f = (i - 1) / 5.0f; // previous camera ... almost the current camera
                    yw = prev.yaw + Geo.wrap(yaw - prev.yaw) * f;
                    pt = prev.pitch + (pitch - prev.pitch) * f;
                }
                double rdx = Geo.dirX(yw, pt), rdy = Geo.dirY(pt), rdz = Geo.dirZ(yw, pt);
                double t = rayHitT(ex, ey, ez, rdx, rdy, rdz, maxT, pd2, tp, now, normal, expand);
                if (t >= 0) {
                    best = new double[]{t, ex, ey, ez, rdx, rdy, rdz};
                } else if (usePrev) {
                    t = rayHitT(prev.x, prev.y + eh, prev.z, rdx, rdy, rdz, maxT, pd2, tp, now, normal, expand);
                    if (t >= 0) best = new double[]{t, prev.x, prev.y + eh, prev.z, rdx, rdy, rdz};
                }
            }
            if (best == null) {
                if (aimErr > HITBOX.p("min-angle")) {
                    if (pd.bump("hitbox", 1) >= HITBOX.p("buffer")) {
                        pd.setBuf("hitbox", 0);
                        cancel |= pl.vio().flag(pd, HITBOX, String.format("crosshair %.0f degrees off the target", aimErr), 1.0);
                    }
                } else {
                    pd.bump("hitbox", -0.5);
                }
            } else {
                pd.bump("hitbox", -0.5);
                // blocks between the player and the target along the very ray that hit it
                int occ = occlusion(p.getWorld(), best[1], best[2], best[3], best[4], best[5], best[6], best[0]);
                if ((occ == 1 && HITBOX.p("web") > 0) || (occ == 2 && HITBOX.p("wall") > 0)) {
                    if (pd.bump("hbthru", 1) >= HITBOX.p("through-buffer")) {
                        pd.setBuf("hbthru", 0);
                        cancel |= pl.vio().flag(pd, HITBOX, occ == 1 ? "hit a player through a cobweb" : "hit a player through a wall", 2.0);
                    }
                } else {
                    pd.bump("hbthru", -0.5);
                }
            }
        }

        // AimAssist rule: aim locked on the centre of a MOVING target again and again
        if (AIM.enabled && dist >= AIM.p("min-distance") && pd2.lastH > 0.12 && now - pd2.lastMoveMs < 250) {
            Ring ar = pd.ring("aim", (int) Math.max(6, AIM.p("window")));
            ar.add(aimErr);
            if (ar.full()) {
                int lock = ar.countBelow(AIM.p("lock-error"));
                if (lock >= AIM.p("min-lock")) {
                    ar.clear();
                    cancel |= pl.vio().flag(pd, AIM, lock + " of the last hits were within " + AIM.p("lock-error") + " degrees of the target centre", 1.0);
                }
            }
        }
        return cancel;
    }

    private boolean crystal(PlayerData pd, Entity target, long now) {
        if (!CRYSTAL.enabled) return false;
        long placed = pd.lastCrystalPlace;
        if (placed == 0 || now - placed > 2000) return false;
        Ring cx = pd.ring("cx", 1), cy = pd.ring("cy", 1), cz = pd.ring("cz", 1);
        if (cx.size() == 0) return false;
        Location tl = target.getLocation();
        double dx = tl.getX() - cx.get(0), dy = tl.getY() - cy.get(0), dz = tl.getZ() - cz.get(0);
        if (dx * dx + dy * dy + dz * dz > 36) return false; // not the crystal this player placed
        pd.lastCrystalPlace = 0; // one placement matches one detonation
        double react = now - placed - pd.ping;
        if (react < CRYSTAL.p("max-reaction-ms")) {
            if (pd.bump("crystal", 1) >= CRYSTAL.p("buffer")) {
                pd.setBuf("crystal", 0);
                return pl.vio().flag(pd, CRYSTAL, String.format("detonated %.0fms after placing (ping-adjusted)", Math.max(0, react)), 1.0);
            }
        } else {
            pd.bump("crystal", -1);
        }
        return false;
    }

    // ===================================================================================== bow

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p) || !FASTBOW.enabled) return;
        ItemStack bow = e.getBow();
        if (bow == null || bow.getType() != Material.BOW) return; // crossbows are charged before shooting
        PlayerData pd = pl.data(p);
        long now = System.currentTimeMillis();
        if (pd.bypassed(now, pl.settings())) return;
        if (pd.useStartMs == 0 || !"BOW".equals(pd.useItem)) return;
        long elapsed = now - pd.useStartMs;
        if (elapsed > 8000 || elapsed < 0) return;
        double f = Math.max(0, (elapsed + FASTBOW.p("tolerance-ms")) / 50.0) / 20.0;
        double expected = Math.min(1.0, (f * f + f * 2.0) / 3.0);
        double force = e.getForce();
        if (force > 0.3 && force > expected + FASTBOW.p("force-slack")) {
            if (pl.vio().flag(pd, FASTBOW, String.format("force %.2f after %dms (max %.2f)", force, elapsed, expected), 1.0)) {
                e.setCancelled(true);
            }
        }
    }

    // ===================================================================================== potions / totems

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof ThrownPotion tpot) || !(tpot.getShooter() instanceof Player p) || !AUTOPOT.enabled) return;
        PlayerData pd = pl.data(p);
        long now = System.currentTimeMillis();
        if (pd.bypassed(now, pl.settings())) return;
        if (pd.lastHeldMs != 0 && now - pd.lastHeldMs < AUTOPOT.p("min-delay-ms")) {
            if (pd.bump("autopot", 1) >= AUTOPOT.p("buffer")) {
                pd.setBuf("autopot", 0);
                pl.vio().flag(pd, AUTOPOT, "potion thrown " + (now - pd.lastHeldMs) + "ms after switching slot", 1.0);
            }
        } else {
            pd.bump("autopot", -0.5);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onResurrect(EntityResurrectEvent e) {
        if (e.getEntity() instanceof Player p) {
            PlayerData pd = pl.data(p);
            pd.lastTotemPop = System.currentTimeMillis();
            pd.ts("totemUsed", 0);
        }
    }

    private void totemEquip(Player p) {
        if (!TOTEM.enabled) return;
        PlayerData pd = pl.data(p);
        long now = System.currentTimeMillis();
        if (pd.lastTotemPop == 0 || now - pd.lastTotemPop > 1500 || pd.ts("totemUsed") == pd.lastTotemPop) return;
        if (pd.bypassed(now, pl.settings())) return;
        pd.ts("totemUsed", pd.lastTotemPop);
        double react = now - pd.lastTotemPop - pd.ping;
        if (react < TOTEM.p("max-reaction-ms")) {
            if (pd.bump("totem", 1) >= TOTEM.p("buffer")) {
                pd.setBuf("totem", 0);
                pl.vio().flag(pd, TOTEM, String.format("totem re-equipped %.0fms after the pop (ping-adjusted)", Math.max(0, react)), 1.0);
            }
        } else {
            pd.bump("totem", -1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        ItemStack off = e.getOffHandItem();
        if (off != null && off.getType() == Material.TOTEM_OF_UNDYING) totemEquip(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTotemClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack cur = e.getCurrentItem(), cursor = e.getCursor();
        boolean totem = (cur != null && cur.getType() == Material.TOTEM_OF_UNDYING)
                || (cursor != null && cursor.getType() == Material.TOTEM_OF_UNDYING);
        if (!totem && e.getHotbarButton() >= 0) {
            ItemStack hb = p.getInventory().getItem(e.getHotbarButton());
            totem = hb != null && hb.getType() == Material.TOTEM_OF_UNDYING;
        }
        if (totem) totemEquip(p);
    }
}
