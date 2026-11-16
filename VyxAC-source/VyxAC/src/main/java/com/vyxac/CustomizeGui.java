package com.vyxac;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** /vyxac customize: toggle checks and main features with clicks. */
public final class CustomizeGui implements Listener {
    private static final class Holder implements InventoryHolder {
        private Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final String[] MODES = {"ALERT_ONLY", "REVIEW", "AUTO"};
    private final VyxAC pl;

    public CustomizeGui(VyxAC pl) {
        this.pl = pl;
    }

    public void open(Player p) {
        Holder h = new Holder();
        Inventory inv = Bukkit.createInventory(h, 54, Settings.color("&8VyxAC &7- customize"));
        h.inv = inv;
        fill(inv, p);
        p.openInventory(inv);
    }

    private ItemStack item(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Settings.color(name));
            List<String> l = new ArrayList<>();
            for (String s : lore) l.add(Settings.color(s));
            meta.setLore(l);
            it.setItemMeta(meta);
        }
        return it;
    }

    private void fill(Inventory inv, Player p) {
        inv.clear();
        int slot = 0;
        for (CheckInfo ci : pl.checks().all()) {
            if (slot > 47) break;
            List<String> lore = new ArrayList<>();
            lore.add("&8" + ci.category);
            lore.add("&7" + ci.description);
            lore.add("&7VL per flag: &f" + ci.vlPerFlag + "&7, punish VL: &f" + (int) ci.punishVl);
            lore.add("&7Counts as punishment evidence: " + (ci.punishable ? "&ayes" : "&cno"));
            lore.add("");
            lore.add("&eLeft-click: &7enable / disable");
            lore.add("&eRight-click: &7evidence on / off");
            inv.setItem(slot++, item(ci.enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                    (ci.enabled ? "&a" : "&c") + ci.name, lore));
        }
        Settings s = pl.settings();
        inv.setItem(48, item(Material.COMPARATOR, "&bPunishment mode: &f" + s.punishMode,
                List.of("&7ALERT_ONLY: never punish", "&7REVIEW: staff decides with one click", "&7AUTO: punish when evidence is strong", "", "&eClick to cycle")));
        inv.setItem(49, item(Material.ENDER_EYE, "&bMini AI: " + (s.aiEnabled ? "&aon" : "&coff"), List.of("&eClick to toggle")));
        inv.setItem(50, item(Material.SHIELD, "&bMitigation: " + (s.mitigation ? "&aon" : "&coff"),
                List.of("&7Cancel flagged moves and hits", "", "&eClick to toggle")));
        inv.setItem(51, item(Material.CLOCK, "&bLag protection: " + (s.lagProtection ? "&aon" : "&coff"),
                List.of("&7Ignore flags while the server lags", "", "&eClick to toggle")));
        inv.setItem(52, item(Material.BELL, "&bMy alerts: " + (pl.vio().alertsEnabled(p.getUniqueId()) ? "&aon" : "&coff"), List.of("&eClick to toggle")));
        inv.setItem(53, item(Material.REDSTONE_TORCH, "&bReload config", List.of("&eClick to reload")));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !p.hasPermission("vyxac.admin")) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getRawSlot();
        Settings s = pl.settings();
        if (slot >= 0 && slot <= 47) {
            int i = 0;
            for (CheckInfo ci : pl.checks().all()) {
                if (i++ != slot) continue;
                if (e.getClick() == ClickType.RIGHT) pl.setConfigValue("checks." + ci.name + ".punishable", !ci.punishable);
                else pl.setConfigValue("checks." + ci.name + ".enabled", !ci.enabled);
                break;
            }
        } else if (slot == 48) {
            int idx = 0;
            for (int k = 0; k < MODES.length; k++) if (MODES[k].equals(s.punishMode)) idx = k;
            pl.setConfigValue("punishments.mode", MODES[(idx + 1) % MODES.length]);
        } else if (slot == 49) {
            pl.setConfigValue("features.ai", !s.aiEnabled);
        } else if (slot == 50) {
            pl.setConfigValue("features.mitigation", !s.mitigation);
        } else if (slot == 51) {
            pl.setConfigValue("features.lag-protection", !s.lagProtection);
        } else if (slot == 52) {
            pl.vio().setAlerts(p.getUniqueId(), !pl.vio().alertsEnabled(p.getUniqueId()));
        } else if (slot == 53) {
            pl.loadAll();
        } else {
            return;
        }
        fill(e.getInventory(), p);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
}
