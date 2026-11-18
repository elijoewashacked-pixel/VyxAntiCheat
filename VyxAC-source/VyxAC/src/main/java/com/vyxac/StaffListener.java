package com.vyxac;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.UUID;
import java.util.regex.Pattern;

/** Event side of staff mode, freezing, staff chat and the "type your answer in chat" prompts. */
public final class StaffListener implements Listener {
    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9_.]{1,32}$");

    private final VyxAC pl;
    private final StaffGui gui;

    public StaffListener(VyxAC pl, StaffGui gui) {
        this.pl = pl;
        this.gui = gui;
    }

    private boolean staff(Player p) {
        return pl.staff().isStaffMode(p.getUniqueId());
    }

    private boolean locked(Player p) {
        return pl.staff().isStaffMode(p.getUniqueId()) || pl.staff().isFrozen(p.getUniqueId());
    }

    private void msg(Player p, String t) {
        p.sendMessage(Settings.color(pl.settings().prefix + t));
    }

    // ===================================================================================== join / quit

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        pl.staff().recoverIfNeeded(p);
        pl.staff().hideVanishedFrom(p);
        pl.reports().deliverPending(p);
        if (p.hasPermission("vyxac.staff") || p.hasPermission("vyxac.admin")) {
            Bukkit.getScheduler().runTaskLater(pl, () -> {
                int n = pl.reports().openCount();
                if (p.isOnline() && n > 0) msg(p, "&e" + n + " open report" + (n == 1 ? "" : "s") + ". &7Use &b/vyxac reports");
            }, 60L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        StaffManager st = pl.staff();
        if (st.isStaffMode(id)) st.exit(p, true); // inventory back before the server saves the player
        if (st.isFrozen(id)) {
            st.unfreezeSilently(id);
            String line = Settings.color(pl.settings().prefix + "&c" + p.getName() + " logged out while frozen!");
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (o.hasPermission("vyxac.staff") || o.hasPermission("vyxac.admin")) o.sendMessage(line);
            }
            String cmd = pl.getConfig().getString("staff.freeze-quit-command", "");
            if (cmd != null && !cmd.isEmpty() && SAFE_NAME.matcher(p.getName()).matches()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
            }
        }
        st.cleanup(id);
    }

    // ===================================================================================== frozen players

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!pl.staff().isFrozen(e.getPlayer().getUniqueId())) return;
        Location f = e.getFrom(), t = e.getTo();
        if (t == null) return;
        if (f.getX() != t.getX() || f.getY() != t.getY() || f.getZ() != t.getZ()) e.setCancelled(true); // turning the head is fine
    }

    // ===================================================================================== staff mode / frozen restrictions

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && staff(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHitOther(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && locked(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && staff(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (e.getTarget() instanceof Player p && staff(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && staff(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInvClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && staff(p)) e.setCancelled(true); // tools cannot be moved, nothing can be taken
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInvDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p && staff(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (staff(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        if (staff(e.getEntity())) { // the real inventory is safe on disk; never drop the kit
            e.setKeepInventory(true);
            e.getDrops().clear();
            e.setKeepLevel(true);
            e.setDroppedExp(0);
        }
    }

    // ===================================================================================== tools

    private void useTool(Player p, String tool) {
        StaffManager st = pl.staff();
        switch (tool) {
            case "reports" -> gui.openReports(p, false, "", 0);
            case "players" -> gui.openPlayers(p, 0, false);
            case "chat" -> st.toggleChat(p);
            case "vanish" -> st.toggleVanish(p);
            case "rtp" -> st.randomTeleport(p);
            case "leave" -> st.exit(p, false);
            default -> msg(p, "&7Right-click a player with this tool.");
        }
    }

    private boolean cooldown(Player p) {
        PlayerData pd = pl.data(p);
        long now = System.currentTimeMillis();
        if (now - pd.ts("toolAt") < 350) return true; // the client sends several events per click
        pd.ts("toolAt", now);
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        StaffManager st = pl.staff();
        if (e.getAction() == Action.PHYSICAL) {
            if (locked(p)) e.setCancelled(true);
            return;
        }
        if (st.isFrozen(p.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        if (!st.isStaffMode(p.getUniqueId()) || e.getHand() != EquipmentSlot.HAND) return;
        String tool = st.toolOf(e.getItem());
        if (tool == null) return; // other right-clicks (doors, chests) are allowed
        e.setCancelled(true);
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (cooldown(p)) return;
        useTool(p, tool);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        StaffManager st = pl.staff();
        if (st.isFrozen(p.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        if (!st.isStaffMode(p.getUniqueId()) || e.getHand() != EquipmentSlot.HAND) return;
        String tool = st.toolOf(p.getInventory().getItemInMainHand());
        if (tool == null) return;
        e.setCancelled(true);
        if (cooldown(p)) return;
        if (!(e.getRightClicked() instanceof Player target)) {
            useTool(p, tool);
            return;
        }
        switch (tool) {
            case "inspect" -> gui.openInspect(p, target.getUniqueId());
            case "freeze" -> st.toggleFreeze(target, p);
            case "invsee" -> {
                p.openInventory(target.getInventory());
                msg(p, "&7Viewing &f" + target.getName() + "&7's inventory (read-only).");
            }
            default -> useTool(p, tool);
        }
    }

    // ===================================================================================== chat

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        final Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        StaffManager st = pl.staff();
        final StaffManager.Await a = st.takeAwait(id);
        if (a != null) {
            e.setCancelled(true);
            final String text = e.getMessage();
            Bukkit.getScheduler().runTask(pl, () -> handleAwait(p, a, text));
            return;
        }
        if (st.inChatMode(id)) {
            e.setCancelled(true);
            final String text = e.getMessage();
            Bukkit.getScheduler().runTask(pl, () -> st.staffChat(p.getName(), text));
        }
    }

    private void handleAwait(Player p, StaffManager.Await a, String text) {
        if (text.equalsIgnoreCase("cancel")) {
            msg(p, "&7Cancelled.");
            if (a.type != StaffManager.AwaitType.STAFF_NOTE) gui.openReport(p, a.reportId);
            return;
        }
        ReportManager rm = pl.reports();
        Report r = a.type == StaffManager.AwaitType.STAFF_NOTE ? null : rm.get(a.reportId);
        if (a.type != StaffManager.AwaitType.STAFF_NOTE && r == null) {
            msg(p, "&cThat report no longer exists.");
            return;
        }
        switch (a.type) {
            case REPLY -> {
                rm.respond(r, p.getName(), text);
                msg(p, "&aSent to &f" + r.reporterName + "&a (they get it when they are online).");
                gui.openReport(p, r.id);
            }
            case DISMISS_NOTE -> {
                rm.close(r, p.getName(), false, text);
                msg(p, "&7Report #" + r.id + " dismissed. The reporter was told.");
                gui.openReports(p, false, "", 0);
            }
            case ACTION_NOTE -> {
                rm.close(r, p.getName(), true, text);
                msg(p, "&aReport #" + r.id + " closed as action taken. The reporter was told.");
                gui.openReports(p, false, "", 0);
            }
            case STAFF_NOTE -> {
                pl.staff().addNote(a.target, a.targetName, p.getName(), text);
                msg(p, "&aNote saved for &f" + a.targetName + "&a.");
                gui.openInspect(p, a.target);
            }
        }
    }
}
