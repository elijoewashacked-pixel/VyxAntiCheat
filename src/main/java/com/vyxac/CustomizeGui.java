package com.vyxac;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /vyxac customize
 *  - main menu with categories
 *  - alphabetical check lists (per category or all) with one icon per check
 *  - a settings page per check: toggles and +/- buttons for every value
 *  - a general settings page
 */
public final class CustomizeGui implements Listener {
    private enum Screen { MAIN, LIST, CHECK, SETTINGS }

    private static final class Holder implements InventoryHolder {
        Inventory inv;
        Screen screen;
        String arg = "";
        int page;
        String backCat = "ALL";
        int backPage;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    /** category key -> {label, icon} */
    private static final Map<String, String[]> CATS = new LinkedHashMap<>();
    private static final Map<String, String> ICONS = new HashMap<>();
    private static final String[] MODES = {"ALERT_ONLY", "REVIEW", "AUTO"};
    private static final int[] PARAM_SLOTS = {28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    static {
        CATS.put("combat", new String[]{"Combat", "DIAMOND_SWORD"});
        CATS.put("movement", new String[]{"Movement", "LEATHER_BOOTS"});
        CATS.put("player", new String[]{"Player & World", "CRAFTING_TABLE"});
        CATS.put("packet", new String[]{"Packets & Misc", "COMPARATOR"});
        CATS.put("ai", new String[]{"Mini AI", "ENDER_EYE"});

        String[][] icons = {
                {"KillAura", "DIAMOND_SWORD"}, {"AimAssist", "SPYGLASS"}, {"AutoClicker", "STONE_BUTTON"}, {"TriggerBot", "TRIPWIRE_HOOK"},
                {"Reach", "BLAZE_ROD"}, {"Hitbox", "TARGET"}, {"Criticals", "GOLDEN_AXE"}, {"Velocity", "SLIME_BALL"},
                {"AntiKnockback", "OBSIDIAN"}, {"FastBow", "BOW"}, {"AutoPot", "SPLASH_POTION"}, {"AutoTotem", "TOTEM_OF_UNDYING"},
                {"MultiAura", "DIAMOND_AXE"}, {"AutoCrystal", "END_CRYSTAL"}, {"Backtrack", "CLOCK"},
                {"Speed", "SUGAR"}, {"Fly", "FEATHER"}, {"LongJump", "RABBIT_FOOT"}, {"BunnyHop", "LEATHER_BOOTS"},
                {"NoFall", "HAY_BLOCK"}, {"Jesus", "WATER_BUCKET"}, {"Spider", "SPIDER_EYE"}, {"Step", "OAK_STAIRS"},
                {"NoSlow", "COBWEB"}, {"Timer", "REPEATER"}, {"Blink", "ENDER_PEARL"}, {"Phase", "GLASS_PANE"},
                {"FastLadder", "LADDER"}, {"ElytraFly", "ELYTRA"},
                {"AutoArmor", "IRON_CHESTPLATE"}, {"ChestStealer", "CHEST"}, {"InventoryMove", "HOPPER"}, {"Freecam", "OBSERVER"},
                {"XRay", "DIAMOND_ORE"}, {"Scaffold", "SCAFFOLDING"}, {"FastPlace", "BRICKS"}, {"Nuker", "TNT"},
                {"FastBreak", "DIAMOND_PICKAXE"}, {"Baritone", "COMPASS"}, {"AutoFish", "FISHING_ROD"},
                {"PingSpoof", "REDSTONE_TORCH"}, {"BadPackets", "BOOK"}, {"PacketFlood", "DISPENSER"}, {"GroundSpoof", "GRASS_BLOCK"},
                {"RotationSpoof", "ITEM_FRAME"}, {"FastEat", "COOKED_BEEF"}, {"AutoEat", "GOLDEN_APPLE"},
                {"AI", "ENDER_EYE"}
        };
        for (String[] i : icons) ICONS.put(i[0], i[1]);
    }

    private final VyxAC pl;

    public CustomizeGui(VyxAC pl) {
        this.pl = pl;
    }

    // ===================================================================================== helpers

    private static Material mat(String name) {
        Material m = Material.matchMaterial(name);
        return m == null ? Material.PAPER : m;
    }

    private Material iconOf(CheckInfo ci) {
        return mat(ICONS.getOrDefault(ci.name, "PAPER"));
    }

    private ItemStack item(Material m, String name, List<String> lore, boolean glow) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Settings.color(name));
            List<String> l = new ArrayList<>();
            for (String s : lore) l.add(Settings.color(s));
            meta.setLore(l);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            if (glow) {
                try {
                    meta.addEnchant(Enchantment.LURE, 1, true);
                } catch (Throwable ignored) {
                    // no glow on this version: the [ON]/[OFF] tag still shows the state
                }
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private static List<String> lore(String... lines) {
        return new ArrayList<>(Arrays.asList(lines));
    }

    private static String fmt(double v) {
        if (v >= 9999) return "never";
        if (v == Math.rint(v)) return Long.toString((long) v);
        String s = String.format(Locale.ROOT, "%.3f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private static String onOff(boolean b) {
        return b ? "&aON" : "&cOFF";
    }

    private List<CheckInfo> sorted(String cat) {
        List<CheckInfo> list = new ArrayList<>();
        for (CheckInfo c : pl.checks().all()) if (cat.equals("ALL") || c.category.equals(cat)) list.add(c);
        list.sort(Comparator.comparing((CheckInfo c) -> c.name.toLowerCase(Locale.ROOT)));
        return list;
    }

    private Holder holder(Screen s, String title, int size) {
        Holder h = new Holder();
        h.screen = s;
        h.inv = Bukkit.createInventory(h, size, Settings.color(title));
        return h;
    }

    private void set(String path, Object val) {
        pl.setConfigValue(path, val);
    }

    private void adjust(String path, double cur, double step, boolean isInt, double min, double max, ClickType click) {
        boolean up = click == ClickType.LEFT || click == ClickType.SHIFT_LEFT;
        boolean down = click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT;
        if (!up && !down) return;
        double nv = cur + (up ? 1 : -1) * step * (click.isShiftClick() ? 5 : 1);
        nv = Math.max(min, Math.min(max, Math.round(nv * 10000.0) / 10000.0));
        set(path, isInt ? (Object) (int) Math.round(nv) : (Object) nv);
    }

    private static double stepFor(double def) {
        double a = Math.abs(def);
        boolean integer = def == Math.rint(def);
        if (integer) return a >= 100 ? 10 : (a >= 20 ? 5 : 1);
        return a < 10 ? 0.05 : 0.5;
    }

    private List<String> numHint(double step) {
        return lore("", "&eLeft-click: &7+" + fmt(step), "&eRight-click: &7-" + fmt(step), "&eShift: &7x5");
    }

    // ===================================================================================== opening screens

    public void open(Player p) {
        openMain(p);
    }

    private void openMain(Player p) {
        Holder h = holder(Screen.MAIN, "&8VyxAC &7- menu", 27);
        render(h, p);
        p.openInventory(h.inv);
    }

    private void openList(Player p, String cat, int page) {
        Holder h = holder(Screen.LIST, "&8VyxAC &7- " + (cat.equals("ALL") ? "All checks (A-Z)" : CATS.get(cat)[0] + " (A-Z)"), 54);
        h.arg = cat;
        h.page = page;
        render(h, p);
        p.openInventory(h.inv);
    }

    private void openCheck(Player p, String name, String backCat, int backPage) {
        Holder h = holder(Screen.CHECK, "&8VyxAC &7- " + name, 54);
        h.arg = name;
        h.backCat = backCat;
        h.backPage = backPage;
        render(h, p);
        p.openInventory(h.inv);
    }

    private void openSettings(Player p) {
        Holder h = holder(Screen.SETTINGS, "&8VyxAC &7- general settings", 27);
        render(h, p);
        p.openInventory(h.inv);
    }

    // ===================================================================================== rendering

    private void render(Holder h, Player p) {
        h.inv.clear();
        switch (h.screen) {
            case MAIN -> renderMain(h);
            case LIST -> renderList(h);
            case CHECK -> renderCheck(h);
            case SETTINGS -> renderSettings(h, p);
        }
    }

    private void renderMain(Holder h) {
        int slot = 10;
        for (Map.Entry<String, String[]> e : CATS.entrySet()) {
            List<CheckInfo> l = sorted(e.getKey());
            int on = 0;
            for (CheckInfo c : l) if (c.enabled) on++;
            h.inv.setItem(slot++, item(mat(e.getValue()[1]), "&b" + e.getValue()[0],
                    lore("&7" + on + " of " + l.size() + " checks enabled", "", "&eClick to open"), false));
        }
        h.inv.setItem(15, item(mat("BOOKSHELF"), "&bAll checks (A-Z)", lore("&7Every check in alphabetical order", "", "&eClick to open"), false));
        h.inv.setItem(16, item(mat("COMMAND_BLOCK"), "&bGeneral settings",
                lore("&7Punishment mode, AI, lag protection,", "&7setbacks, alerts, thresholds", "", "&eClick to open"), false));
        h.inv.setItem(22, item(mat("WRITABLE_BOOK"), "&bDaily diagnostics", lore("&7Which checks look noisy, who looks like a cheater", "", "&eClick to print today's report"), false));
    }

    private void renderList(Holder h) {
        List<CheckInfo> list = sorted(h.arg);
        int per = 45;
        int pages = Math.max(1, (list.size() + per - 1) / per);
        h.page = Math.max(0, Math.min(h.page, pages - 1));
        for (int i = h.page * per, slot = 0; i < list.size() && slot < per; i++, slot++) {
            CheckInfo c = list.get(i);
            List<String> l = lore(
                    "&7" + c.description,
                    "&8" + c.category,
                    "",
                    "&7Status: " + (c.enabled ? "&aENABLED" : "&cDISABLED"),
                    "&7Counts as punishment evidence: " + (c.punishable ? "&ayes" : "&cno"),
                    "&7Alert at VL &f" + fmt(c.alertVl) + "&7, punish at VL &f" + (c.punishable ? fmt(c.punishVl) : "-"),
                    "",
                    "&eLeft-click: &7turn on / off",
                    "&eRight-click: &7open settings");
            h.inv.setItem(slot, item(iconOf(c), (c.enabled ? "&a[ON] &f" : "&c[OFF] &7") + c.name, l, c.enabled));
        }
        h.inv.setItem(45, item(mat("ARROW"), "&bBack to menu", lore(), false));
        if (h.page > 0) h.inv.setItem(48, item(mat("ARROW"), "&bPrevious page", lore(), false));
        h.inv.setItem(49, item(mat("PAPER"), "&7Page " + (h.page + 1) + "/" + pages, lore("&7" + list.size() + " checks, A-Z"), false));
        if (h.page < pages - 1) h.inv.setItem(50, item(mat("ARROW"), "&bNext page", lore(), false));
    }

    private void renderCheck(Holder h) {
        CheckInfo c = pl.checks().find(h.arg);
        if (c == null) return;
        h.inv.setItem(4, item(iconOf(c), "&b" + c.name, lore("&7" + c.description, "&8Category: " + c.category), c.enabled));
        h.inv.setItem(19, item(mat(c.enabled ? "LIME_DYE" : "GRAY_DYE"), c.enabled ? "&aEnabled" : "&cDisabled", lore("&7Click to turn " + (c.enabled ? "off" : "on")), false));
        h.inv.setItem(20, item(mat("LEVER"), "&bCounts as punishment evidence: " + (c.punishable ? "&ayes" : "&cno"),
                lore("&7If off, this check only sends alerts", "&7and never helps to punish someone.", "", "&eClick to toggle"), false));
        List<String> vl = lore("&7How many violation points one flag adds.");
        vl.addAll(numHint(0.25));
        h.inv.setItem(21, item(mat("GOLD_NUGGET"), "&bVL per flag: &f" + fmt(c.vlPerFlag), vl, false));
        List<String> al = lore("&7Alerts are only sent once the player", "&7has this many violation points.");
        al.addAll(numHint(1));
        h.inv.setItem(22, item(mat("BELL"), "&bAlert at VL: &f" + fmt(c.alertVl), al, false));
        List<String> pu = lore("&7The check counts as punishment evidence", "&7once the player reaches this VL.");
        pu.addAll(numHint(1));
        h.inv.setItem(23, item(mat("ANVIL"), "&bPunish evidence at VL: &f" + fmt(c.punishVl), pu, false));
        List<String> mi = lore("&7Flagged hits / actions are cancelled from this VL.", "&7(Movement setbacks are a separate switch in general settings.)", "");
        if (c.mitigateVl >= 9999) {
            mi.add("&eLeft-click: &7enable at VL 3");
        } else {
            mi.add("&eLeft-click: &7+1   &eRight-click: &7-1");
            mi.add("&eShift-click: &7never cancel");
        }
        h.inv.setItem(24, item(mat("SHIELD"), "&bCancel at VL: &f" + fmt(c.mitigateVl), mi, false));
        h.inv.setItem(25, item(mat("BARRIER"), "&cReset to defaults", lore("&7Restores every value of this check", "", "&eClick to reset"), false));

        int i = 0;
        for (Map.Entry<String, Double> e : c.defaults.entrySet()) {
            if (i >= PARAM_SLOTS.length) break;
            double cur = c.p(e.getKey());
            double step = stepFor(e.getValue());
            List<String> l = lore("&7Default: &f" + fmt(e.getValue()));
            l.addAll(numHint(step));
            h.inv.setItem(PARAM_SLOTS[i++], item(mat("COMPARATOR"), "&b" + e.getKey().replace('-', ' ') + ": &f" + fmt(cur), l, false));
        }
        h.inv.setItem(45, item(mat("ARROW"), "&bBack to list", lore(), false));
    }

    private void renderSettings(Holder h, Player p) {
        Settings s = pl.settings();
        h.inv.setItem(10, item(mat("COMPARATOR"), "&bPunishment mode: &f" + s.punishMode,
                lore("&7ALERT_ONLY: never punish", "&7REVIEW: staff decides with one click", "&7AUTO: punish when the evidence is strong", "", "&eClick to cycle"), false));
        h.inv.setItem(11, item(mat("ENDER_EYE"), "&bMini AI: " + onOff(s.aiEnabled), lore("&eClick to toggle"), s.aiEnabled));
        h.inv.setItem(12, item(mat("SHIELD"), "&bCancel flagged hits/actions: " + onOff(s.mitigation),
                lore("&7Cancels flagged hits, placing and breaking.", "", "&eClick to toggle"), s.mitigation));
        h.inv.setItem(13, item(mat("PISTON"), "&bMovement setbacks: " + onOff(s.mitigateMovement),
                lore("&7Moves players back on flagged movement.", "&cCan feel like rubber-banding. Off is safest.", "", "&eClick to toggle"), s.mitigateMovement));
        h.inv.setItem(14, item(mat("CLOCK"), "&bLag protection: " + onOff(s.lagProtection),
                lore("&7Ignore flags while the server lags.", "", "&eClick to toggle"), s.lagProtection));
        boolean mine = pl.vio().alertsEnabled(p.getUniqueId());
        h.inv.setItem(15, item(mat("BELL"), "&bMy alerts: " + onOff(mine), lore("&eClick to toggle"), mine));
        h.inv.setItem(16, item(mat("PAPER"), "&bConsole alerts: " + onOff(s.alertConsole), lore("&eClick to toggle"), s.alertConsole));

        h.inv.setItem(19, num("SPYGLASS", "AI sensitivity", "ai.sensitivity", 0.1, "&7Higher = stricter AI."));
        h.inv.setItem(20, num("REPEATER", "Lag guard: ignore flags below TPS", "lag.min-tps", 1, "&7Flags are ignored below this TPS."));
        h.inv.setItem(21, num("HOPPER", "Max ping for combat checks", "lag.max-ping", 50, "&7Players above this ping are not checked."));
        h.inv.setItem(22, num("BOOK", "Punish: different checks needed", "punishments.min-distinct-checks", 1, "&7More = fewer punishments."));
        h.inv.setItem(23, num("BOOK", "Punish: flags needed", "punishments.min-flags", 1, "&7Flags inside the time window."));
        h.inv.setItem(24, item(mat("WRITABLE_BOOK"), "&bDiagnostics", lore("&eClick to print today's report"), false));
        h.inv.setItem(25, item(mat("REDSTONE_TORCH"), "&bReload config", lore("&eClick to reload config.yml"), false));
        h.inv.setItem(26, item(mat("ARROW"), "&bBack to menu", lore(), false));
    }

    private ItemStack num(String icon, String label, String path, double step, String desc) {
        Object cur = pl.getConfig().get(path);
        double v = cur instanceof Number n ? n.doubleValue() : 0;
        List<String> l = lore(desc);
        l.addAll(numHint(step));
        return item(mat(icon), "&b" + label + ": &f" + fmt(v), l, false);
    }

    private void adjustPath(String path, double step, double min, double max, ClickType click) {
        Object cur = pl.getConfig().get(path);
        if (!(cur instanceof Number n)) return;
        boolean isInt = cur instanceof Integer || cur instanceof Long;
        adjust(path, n.doubleValue(), step, isInt, min, max, click);
    }

    // ===================================================================================== clicks

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !p.hasPermission("vyxac.admin")) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getRawSlot();
        ClickType click = e.getClick();
        boolean changed = false;
        switch (h.screen) {
            case MAIN -> clickMain(p, slot);
            case LIST -> changed = clickList(p, h, slot, click);
            case CHECK -> changed = clickCheck(p, h, slot, click);
            case SETTINGS -> changed = clickSettings(p, slot, click);
        }
        if (changed && p.getOpenInventory().getTopInventory() == h.inv) render(h, p);
    }

    private void clickMain(Player p, int slot) {
        int i = 0;
        for (String key : CATS.keySet()) {
            if (slot == 10 + i) {
                openList(p, key, 0);
                return;
            }
            i++;
        }
        if (slot == 15) openList(p, "ALL", 0);
        else if (slot == 16) openSettings(p);
        else if (slot == 22) {
            p.closeInventory();
            for (String l : pl.diagnostics().current()) p.sendMessage(Settings.color("&7" + l));
        }
    }

    private boolean clickList(Player p, Holder h, int slot, ClickType click) {
        if (slot == 45) {
            openMain(p);
            return false;
        }
        if (slot == 48 && h.page > 0) {
            h.page--;
            return true;
        }
        if (slot == 50) {
            h.page++;
            return true;
        }
        if (slot < 0 || slot >= 45) return false;
        List<CheckInfo> list = sorted(h.arg);
        int idx = h.page * 45 + slot;
        if (idx >= list.size()) return false;
        CheckInfo c = list.get(idx);
        if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) {
            openCheck(p, c.name, h.arg, h.page);
            return false;
        }
        set("checks." + c.name + ".enabled", !c.enabled);
        return true;
    }

    private boolean clickCheck(Player p, Holder h, int slot, ClickType click) {
        CheckInfo c = pl.checks().find(h.arg);
        if (c == null) return false;
        String base = "checks." + c.name + ".";
        switch (slot) {
            case 19 -> set(base + "enabled", !c.enabled);
            case 20 -> set(base + "punishable", !c.punishable);
            case 21 -> adjust(base + "vl-per-flag", c.vlPerFlag, 0.25, false, 0.1, 20, click);
            case 22 -> adjust(base + "alert-vl", c.alertVl, 1, false, 0, 500, click);
            case 23 -> adjust(base + "punish-vl", c.punishVl, 1, false, 1, 500, click);
            case 24 -> {
                if (c.mitigateVl >= 9999) {
                    if (click == ClickType.LEFT || click == ClickType.SHIFT_LEFT) set(base + "mitigate-vl", 3.0);
                } else if (click.isShiftClick()) {
                    set(base + "mitigate-vl", 9999.0);
                } else {
                    adjust(base + "mitigate-vl", c.mitigateVl, 1, false, 1, 500, click);
                }
            }
            case 25 -> {
                Map<String, Object> vals = new LinkedHashMap<>();
                vals.put(base + "enabled", c.defaultEnabled);
                vals.put(base + "punishable", c.defPunishable);
                vals.put(base + "vl-per-flag", c.defVlPerFlag);
                vals.put(base + "alert-vl", c.defAlertVl);
                vals.put(base + "punish-vl", c.defPunishVl);
                vals.put(base + "mitigate-vl", c.defMitigateVl);
                for (Map.Entry<String, Double> e : c.defaults.entrySet()) {
                    double d = e.getValue();
                    vals.put(base + "params." + e.getKey(), d == Math.rint(d) ? (Object) (int) d : (Object) d);
                }
                pl.setConfigValues(vals);
            }
            case 45 -> {
                openList(p, h.backCat, h.backPage);
                return false;
            }
            default -> {
                int i = 0;
                for (Map.Entry<String, Double> e : c.defaults.entrySet()) {
                    if (i >= PARAM_SLOTS.length) break;
                    if (PARAM_SLOTS[i++] == slot) {
                        double def = e.getValue();
                        boolean isInt = def == Math.rint(def);
                        adjust(base + "params." + e.getKey(), c.p(e.getKey()), stepFor(def), isInt, 0, 100000, click);
                        return true;
                    }
                }
                return false;
            }
        }
        return true;
    }

    private boolean clickSettings(Player p, int slot, ClickType click) {
        Settings s = pl.settings();
        switch (slot) {
            case 10 -> {
                int idx = 0;
                for (int k = 0; k < MODES.length; k++) if (MODES[k].equals(s.punishMode)) idx = k;
                set("punishments.mode", MODES[(idx + 1) % MODES.length]);
            }
            case 11 -> set("features.ai", !s.aiEnabled);
            case 12 -> set("features.mitigation", !s.mitigation);
            case 13 -> set("features.mitigate-movement", !s.mitigateMovement);
            case 14 -> set("features.lag-protection", !s.lagProtection);
            case 15 -> pl.vio().setAlerts(p.getUniqueId(), !pl.vio().alertsEnabled(p.getUniqueId()));
            case 16 -> set("alerts.console", !s.alertConsole);
            case 19 -> adjustPath("ai.sensitivity", 0.1, 0.2, 3.0, click);
            case 20 -> adjustPath("lag.min-tps", 1, 5, 20, click);
            case 21 -> adjustPath("lag.max-ping", 50, 100, 2000, click);
            case 22 -> adjustPath("punishments.min-distinct-checks", 1, 1, 10, click);
            case 23 -> adjustPath("punishments.min-flags", 1, 1, 100, click);
            case 24 -> {
                p.closeInventory();
                for (String l : pl.diagnostics().current()) p.sendMessage(Settings.color("&7" + l));
                return false;
            }
            case 25 -> pl.loadAll();
            case 26 -> {
                openMain(p);
                return false;
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
}
