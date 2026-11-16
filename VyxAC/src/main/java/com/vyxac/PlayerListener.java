package com.vyxac;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;

/** Player, world, inventory and consumption checks plus join/quit handling. */
public final class PlayerListener implements Listener {
    private static Method DESTROY, PREFERRED;
    private static boolean reflected;

    private final VyxAC pl;
    private final CheckInfo FREECAM, SCAFFOLD, FASTPLACE, NUKER, FASTBREAK, XRAY, ARMOR, STEALER, FISH, FASTEAT, AUTOEAT;

    public PlayerListener(VyxAC pl) {
        this.pl = pl;
        CheckRegistry r = pl.checks();
        FREECAM = r.get("Freecam");
        SCAFFOLD = r.get("Scaffold");
        FASTPLACE = r.get("FastPlace");
        NUKER = r.get("Nuker");
        FASTBREAK = r.get("FastBreak");
        XRAY = r.get("XRay");
        ARMOR = r.get("AutoArmor");
        STEALER = r.get("ChestStealer");
        FISH = r.get("AutoFish");
        FASTEAT = r.get("FastEat");
        AUTOEAT = r.get("AutoEat");
    }

    private boolean active(Player p, PlayerData pd, long now) {
        Settings s = pl.settings();
        if (!s.enabled || pd.bypassed(now, s)) return false;
        GameMode gm = p.getGameMode();
        if (gm == GameMode.SPECTATOR) return false;
        return !(s.lagProtection && pl.ticks().unstable());
    }

    private static long pack(Block b) {
        return ((long) (b.getX() & 0x3FFFFFF) << 38) | ((long) (b.getZ() & 0x3FFFFFF) << 12) | (b.getY() & 0xFFF);
    }

    // ===================================================================================== lifecycle

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        PlayerData pd = pl.data(e.getPlayer());
        pd.lastTeleport = System.currentTimeMillis();
        pd.resetMotion();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        pl.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) {
        pl.data(e.getPlayer()).lastHeldMs = System.currentTimeMillis();
    }

    // ===================================================================================== interaction

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        PlayerData pd = pl.data(p);
        pd.evInteracts++;
        if (e.getHand() != EquipmentSlot.HAND) return;
        long now = System.currentTimeMillis();
        Action a = e.getAction();
        ItemStack item = e.getItem();

        if (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) {
            if (item != null) {
                Material m = item.getType();
                pd.useStartMs = now;
                pd.useItem = m.name();
                if (m == Material.FIREWORK_ROCKET) pd.lastFirework = now;
                if (m == Material.END_CRYSTAL && e.getClickedBlock() != null) {
                    pd.lastCrystalPlace = now;
                    Block b = e.getClickedBlock();
                    pd.ring("cx", 1).add(b.getX() + 0.5);
                    pd.ring("cy", 1).add(b.getY() + 1.0);
                    pd.ring("cz", 1).add(b.getZ() + 0.5);
                }
                // AutoEat: slot switch and eat within a tick or two, again and again
                if (AUTOEAT.enabled && m.isEdible() && active(p, pd, now) && pd.lastHeldMs != 0) {
                    if (now - pd.lastHeldMs < AUTOEAT.p("min-delay-ms")) {
                        if (pd.bump("autoeat", 1) >= AUTOEAT.p("buffer")) {
                            pd.setBuf("autoeat", 0);
                            pl.vio().flag(pd, AUTOEAT, "started eating " + (now - pd.lastHeldMs) + "ms after switching slot", 1.0);
                        }
                    } else {
                        pd.bump("autoeat", -0.5);
                    }
                }
            }
        }

        // Freecam: the clicked face must point towards the player's eye
        if (FREECAM.enabled && e.getClickedBlock() != null && (a == Action.RIGHT_CLICK_BLOCK || a == Action.LEFT_CLICK_BLOCK)
                && active(p, pd, now) && pd.ping <= pl.settings().maxPing && now - pd.lastMitigate > 1500
                && now - pd.lastTeleport > 1500 && !p.isInsideVehicle()) {
            Block b = e.getClickedBlock();
            BlockFace f = e.getBlockFace();
            double ex = p.getEyeLocation().getX(), ey = p.getEyeLocation().getY(), ez = p.getEyeLocation().getZ();
            double beyond;
            switch (f) {
                case UP -> beyond = ey - (b.getY() + 1);
                case DOWN -> beyond = b.getY() - ey;
                case NORTH -> beyond = b.getZ() - ez;
                case SOUTH -> beyond = ez - (b.getZ() + 1);
                case WEST -> beyond = b.getX() - ex;
                case EAST -> beyond = ex - (b.getX() + 1);
                default -> beyond = 0;
            }
            if (beyond < -FREECAM.p("tolerance")) {
                if (pl.vio().flag(pd, FREECAM, String.format("clicked the %s face from %.1f blocks behind it", f.name().toLowerCase(), -beyond), 1.0)) {
                    e.setCancelled(true);
                }
            }
        }
    }

    // ===================================================================================== consuming

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (!FASTEAT.enabled) return;
        Player p = e.getPlayer();
        PlayerData pd = pl.data(p);
        long now = System.currentTimeMillis();
        if (!active(p, pd, now)) return;
        Material m = e.getItem().getType();
        if (pd.useStartMs == 0 || !m.name().equals(pd.useItem)) return;
        long elapsed = now - pd.useStartMs;
        if (elapsed < 0 || elapsed > 20_000) return;
        int ticks = m == Material.DRIED_KELP ? 16 : (m == Material.HONEY_BOTTLE ? 40 : 32);
        double expected = ticks * 50.0;
        if (elapsed + Math.max(100, pd.ping) < expected * FASTEAT.p("tolerance")) {
            if (pl.vio().flag(pd, FASTEAT, "finished " + m.name().toLowerCase() + " after " + elapsed + "ms (needs " + (long) expected + "ms)", 1.0)) {
                e.setCancelled(true);
            }
        }
    }

    // ===================================================================================== fishing

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (!FISH.enabled) return;
        Player p = e.getPlayer();
        PlayerData pd = pl.data(p);
        long now = System.currentTimeMillis();
        String st = e.getState().name();
        if (st.equals("BITE")) {
            pd.ts("bite", now);
            return;
        }
        if (!st.equals("CAUGHT_FISH") || !active(p, pd, now)) return;
        long bite = pd.ts("bite");
        if (bite == 0 || now - bite > 4000) return;
        double react = now - bite - pd.ping;
        Ring r = pd.ring("fish", 8);
        r.add(react);
        boolean fast = react < FISH.p("max-reaction-ms");
        if (fast) pd.bump("fish", 1);
        else pd.bump("fish", -1);
        boolean constant = r.full() && r.std() < 20 && r.mean() < 400;
        if (pd.buf("fish") >= FISH.p("buffer") || constant) {
            pd.setBuf("fish", 0);
            String info = constant ? String.format("reel-in delay %.0fms +-%.0f over %d catches", r.mean(), r.std(), r.size())
                    : String.format("reeled in %.0fms after the bite (ping-adjusted)", Math.max(0, react));
            r.clear();
            pl.vio().flag(pd, FISH, info, 1.0);
        }
    }

    // ===================================================================================== blocks

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        PlayerData pd = pl.data(p);
        long now = System.currentTimeMillis();
        if (!active(p, pd, now) || p.getGameMode() == GameMode.CREATIVE) return;
        boolean cancel = false;

        if (FASTPLACE.enabled) {
            Ring r = pd.ring("place", 32);
            r.add(now);
            int cnt = 0;
            for (int k = 0; k < r.size(); k++) if (now - (long) r.get(k) <= 1000) cnt++;
            if (cnt > FASTPLACE.p("max-per-sec")) {
                cancel |= pl.vio().flag(pd, FASTPLACE, cnt + " blocks placed in one second", 1.0);
            }
        }

        if (SCAFFOLD.enabled && pd.ping <= pl.settings().maxPing && now - pd.lastTeleport > 1500) {
            Block against = e.getBlockAgainst();
            double ex = p.getEyeLocation().getX(), ey = p.getEyeLocation().getY(), ez = p.getEyeLocation().getZ();
            double exp = SCAFFOLD.p("expand");
            double bx = against.getX(), by = against.getY(), bz = against.getZ();
            float yaw = p.getLocation().getYaw(), pitch = p.getLocation().getPitch();
            boolean hit = Geo.rayHitsBox(ex, ey, ez, Geo.dirX(yaw, pitch), Geo.dirY(pitch), Geo.dirZ(yaw, pitch), 8,
                    bx - exp, by - exp, bz - exp, bx + 1 + exp, by + 1 + exp, bz + 1 + exp);
            MoveCtx prev = pd.prev;
            if (!hit && prev != null) {
                double eh = p.getEyeHeight();
                hit = Geo.rayHitsBox(ex, ey, ez, Geo.dirX(prev.yaw, prev.pitch), Geo.dirY(prev.pitch), Geo.dirZ(prev.yaw, prev.pitch), 8,
                        bx - exp, by - exp, bz - exp, bx + 1 + exp, by + 1 + exp, bz + 1 + exp)
                        || Geo.rayHitsBox(prev.x, prev.y + eh, prev.z, Geo.dirX(prev.yaw, prev.pitch), Geo.dirY(prev.pitch), Geo.dirZ(prev.yaw, prev.pitch), 8,
                        bx - exp, by - exp, bz - exp, bx + 1 + exp, by + 1 + exp, bz + 1 + exp);
            }
            if (!hit) {
                if (pd.bump("scaffold", 1) >= SCAFFOLD.p("buffer")) {
                    pd.setBuf("scaffold", 0);
                    cancel |= pl.vio().flag(pd, SCAFFOLD, "placed a block without looking at it", 1.0);
                }
            } else {
                pd.bump("scaffold", -0.5);
            }
        }
        if (cancel) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageBlock(BlockDamageEvent e) {
        PlayerData pd = pl.data(e.getPlayer());
        if (pd.dmg.size() > 64) pd.dmg.clear();
        pd.dmg.put(pack(e.getBlock()), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        PlayerData pd = pl.data(p);
        long now = System.currentTimeMillis();
        Block b = e.getBlock();
        Long started = pd.dmg.remove(pack(b));
        if (!active(p, pd, now) || p.getGameMode() == GameMode.CREATIVE) return;
        boolean cancel = false;

        // ---- Nuker: only breaks that the player really started (BlockDamageEvent) count; plugin multi-breaks do not
        if (NUKER.enabled && started != null) {
            Ring r = pd.ring("break", 32);
            r.add(now);
            int cnt = 0;
            for (int k = 0; k < r.size(); k++) if (now - (long) r.get(k) <= 1000) cnt++;
            if (now - pd.ts("brkLast") <= 60) pd.bump("brkTick", 1);
            else pd.setBuf("brkTick", 1);
            pd.ts("brkLast", now);
            if (cnt > NUKER.p("max-per-sec")) {
                cancel |= pl.vio().flag(pd, NUKER, cnt + " blocks broken in one second", 1.0);
            } else if (pd.buf("brkTick") >= NUKER.p("same-tick")) {
                pd.setBuf("brkTick", 0);
                cancel |= pl.vio().flag(pd, NUKER, "several blocks broken within a single tick", 1.5);
            }
        }

        // ---- FastBreak: compare with the fastest possible time for the best tool in the hotbar
        if (FASTBREAK.enabled && started != null && pd.ping <= pl.settings().maxPing) {
            double minTicks = minBreakTicks(p, b);
            if (minTicks > 0) {
                long elapsed = now - started;
                double expectedMs = minTicks * 50.0 * FASTBREAK.p("tolerance");
                if (elapsed + 100 < expectedMs) {
                    cancel |= pl.vio().flag(pd, FASTBREAK, String.format("%s broken in %dms (needs at least %.0fms)",
                            b.getType().name().toLowerCase(), elapsed, minTicks * 50.0), 1.0);
                }
            }
        }

        // ---- XRay heuristic: hidden valuable ores relative to all blocks mined
        if (XRAY.enabled) {
            long window = (long) (XRAY.p("window-sec") * 1000);
            if (now - pd.ts("xrayStart") > window) {
                pd.ts("xrayStart", now);
                pd.setBuf("xr_total", 0);
                pd.setBuf("xr_hidden", 0);
            }
            pd.bump("xr_total", 1);
            if (isValuableOre(b.getType()) && isHidden(b)) {
                double hidden = pd.bump("xr_hidden", 1);
                double ratio = hidden / Math.max(1.0, pd.buf("xr_total"));
                if (hidden >= XRAY.p("min-hidden") && ratio >= XRAY.p("min-ratio")) {
                    pd.setBuf("xr_hidden", 0);
                    pd.setBuf("xr_total", 0);
                    pl.vio().flag(pd, XRAY, String.format("%.0f hidden valuable ores out of %.0f%% of mined blocks", hidden, ratio * 100), 1.0);
                }
            }
        }
        if (cancel) e.setCancelled(true);
    }

    private static boolean isValuableOre(Material m) {
        String n = m.name();
        return n.equals("DIAMOND_ORE") || n.equals("DEEPSLATE_DIAMOND_ORE") || n.equals("EMERALD_ORE")
                || n.equals("DEEPSLATE_EMERALD_ORE") || n.equals("ANCIENT_DEBRIS");
    }

    /** an ore with no air or liquid next to it cannot be seen without X-ray */
    private static boolean isHidden(Block b) {
        World w = b.getWorld();
        int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] o : d) {
            Block n = Phys.at(w, b.getX() + o[0], b.getY() + o[1], b.getZ() + o[2]);
            if (n == null || n.getType().isAir() || n.isLiquid() || n.isPassable()) return false;
        }
        return true;
    }

    /** fastest possible break time in ticks, or -1 when it cannot be computed safely (then the check is skipped) */
    private double minBreakTicks(Player p, Block b) {
        if (!reflected) {
            reflected = true;
            try {
                DESTROY = BlockData.class.getMethod("getDestroySpeed", ItemStack.class, boolean.class);
            } catch (Throwable t) {
                try {
                    DESTROY = BlockData.class.getMethod("getDestroySpeed", ItemStack.class);
                } catch (Throwable t2) {
                    DESTROY = null;
                }
            }
            try {
                PREFERRED = Block.class.getMethod("isPreferredTool", ItemStack.class);
            } catch (Throwable t) {
                PREFERRED = null;
            }
        }
        if (DESTROY == null) return -1;
        float hardness = b.getType().getHardness();
        if (hardness <= 0) return -1; // instant break
        double best = 0;
        try {
            BlockData bd = b.getBlockData();
            for (int slot = 0; slot < 9; slot++) {
                ItemStack it = p.getInventory().getItem(slot);
                if (it == null) it = new ItemStack(Material.AIR);
                Object r = DESTROY.getParameterCount() == 2 ? DESTROY.invoke(bd, it, true) : DESTROY.invoke(bd, it);
                best = Math.max(best, ((Number) r).doubleValue());
            }
        } catch (Throwable t) {
            return -1;
        }
        if (best <= 0) return -1;
        int haste = Compat.level(p, "FAST_DIGGING", "HASTE");
        if (haste > 0) best *= 1.0 + 0.2 * haste;
        // assume the tool is the preferred one: that divides by 30 instead of 100 and gives the lowest (safest) limit
        double perTick = best / hardness / 30.0;
        if (perTick >= 1.0) return -1; // breaks instantly
        return Math.ceil(1.0 / perTick);
    }

    // ===================================================================================== inventories

    private static boolean isArmor(ItemStack it) {
        if (it == null) return false;
        String n = it.getType().name();
        return n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p && e.getInventory().getType() != InventoryType.CRAFTING
                && e.getInventory().getType() != InventoryType.CREATIVE) {
            pl.data(p).containerOpenSince = System.currentTimeMillis();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) pl.data(p).containerOpenSince = 0;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        PlayerData pd = pl.data(p);
        long now = System.currentTimeMillis();
        if (!active(p, pd, now) || p.getGameMode() == GameMode.CREATIVE) return;

        // ---- ChestStealer: a long run of clicks in a container at one click per tick or faster
        if (STEALER.enabled && e.getClickedInventory() != null && e.getClickedInventory() == e.getView().getTopInventory()) {
            InventoryType t = e.getClickedInventory().getType();
            if (t == InventoryType.CHEST || t == InventoryType.SHULKER_BOX || t == InventoryType.BARREL || t == InventoryType.ENDER_CHEST) {
                long dt = now - pd.ts("csLast");
                pd.ts("csLast", now);
                if (dt <= STEALER.p("max-interval-ms")) {
                    if (pd.bump("steal", 1) >= STEALER.p("buffer")) {
                        pd.setBuf("steal", 0);
                        pl.vio().flag(pd, STEALER, "container clicks every " + dt + "ms", 1.0);
                    }
                } else if (dt > 150) {
                    pd.setBuf("steal", 0);
                }
            }
        }

        // ---- AutoArmor: several armor pieces equipped in consecutive ticks
        if (ARMOR.enabled) {
            boolean equip = (e.getSlotType() == InventoryType.SlotType.ARMOR && (isArmor(e.getCursor()) || e.getHotbarButton() >= 0))
                    || (e.isShiftClick() && isArmor(e.getCurrentItem()) && e.getClickedInventory() == p.getInventory());
            if (equip) {
                long dt = now - pd.ts("armLast");
                pd.ts("armLast", now);
                if (dt <= ARMOR.p("max-interval-ms")) {
                    if (pd.bump("armor", 1) >= ARMOR.p("buffer")) {
                        pd.setBuf("armor", 0);
                        pl.vio().flag(pd, ARMOR, "armor equipped every " + dt + "ms", 1.0);
                    }
                } else if (dt > 300) {
                    pd.setBuf("armor", 0);
                }
            }
        }
    }
}
