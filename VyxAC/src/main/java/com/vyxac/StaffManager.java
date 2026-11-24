package com.vyxac;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Staff mode: vanish, flight, invulnerability and a hotbar kit. The real inventory is saved in memory AND on disk
 * (plugins/VyxAC/staffmode/&lt;uuid&gt;.yml) so a crash can never lose items; it is restored on exit, on quit,
 * on plugin disable and automatically on the next join if the server crashed.
 */
public final class StaffManager {
    public enum AwaitType { REPLY, DISMISS_NOTE, ACTION_NOTE, STAFF_NOTE }

    /** a staff member is expected to type something in chat */
    public static final class Await {
        public final AwaitType type;
        public final int reportId;
        public final UUID target;
        public final String targetName;
        final long at = System.currentTimeMillis();

        public Await(AwaitType type, int reportId, UUID target, String targetName) {
            this.type = type;
            this.reportId = reportId;
            this.target = target;
            this.targetName = targetName;
        }
    }

    private static final class Saved {
        ItemStack[] storage = new ItemStack[36], armor = new ItemStack[4];
        ItemStack offhand;
        GameMode gm = GameMode.SURVIVAL;
        boolean allowFlight, flying, invulnerable, collidable = true, sleepIgnored;
    }

    private final VyxAC pl;
    private final NamespacedKey toolKey;
    private final File dir, notesFile;
    private final Map<UUID, Saved> saved = new HashMap<>();
    private final Set<UUID> vanished = new HashSet<>();
    private final Set<UUID> frozen = new HashSet<>();
    private final Set<UUID> chatMode = new HashSet<>();
    private final Map<UUID, Await> awaiting = new HashMap<>();
    private YamlConfiguration notes;

    public StaffManager(VyxAC pl) {
        this.pl = pl;
        this.toolKey = new NamespacedKey(pl, "vyxac_tool");
        this.dir = new File(pl.getDataFolder(), "staffmode");
        this.notesFile = new File(pl.getDataFolder(), "notes.yml");
        this.notes = YamlConfiguration.loadConfiguration(notesFile);
    }

    private void msg(Player p, String text) {
        p.sendMessage(Settings.color(pl.settings().prefix + text));
    }

    // ------------------------------------------------------------------ state queries

    public boolean isStaffMode(UUID id) { return saved.containsKey(id); }
    public boolean isVanished(UUID id) { return vanished.contains(id); }
    public boolean isFrozen(UUID id) { return frozen.contains(id); }
    public boolean inChatMode(UUID id) { return chatMode.contains(id); }

    public List<String> staffModeNames() {
        List<String> l = new ArrayList<>();
        for (UUID id : saved.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) l.add(p.getName());
        }
        return l;
    }

    public List<String> frozenNames() {
        List<String> l = new ArrayList<>();
        for (UUID id : frozen) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) l.add(p.getName());
        }
        return l;
    }

    // ------------------------------------------------------------------ kit

    private ItemStack tool(Material m, String id, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Settings.color(name));
            List<String> l = new ArrayList<>();
            for (String s : lore) l.add(Settings.color(s));
            meta.setLore(l);
            meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, id);
            it.setItemMeta(meta);
        }
        return it;
    }

    public String toolOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        ItemMeta meta = it.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
    }

    private static Material mat(String name, Material fallback) {
        Material m = Material.matchMaterial(name);
        return m == null ? fallback : m;
    }

    private ItemStack reportsItem() {
        int n = pl.reports().openCount();
        ItemStack it = tool(Material.PAPER, "reports", "&b&lReports &7(" + n + " open)", "&7Right-click: open the report tickets");
        it.setAmount(Math.max(1, Math.min(64, n)));
        return it;
    }

    private ItemStack vanishItem(boolean on) {
        return tool(mat(on ? "LIME_DYE" : "GRAY_DYE", Material.PAPER), "vanish", on ? "&a&lVanish: ON" : "&7&lVanish: OFF", "&7Right-click: toggle vanish");
    }

    private void giveKit(Player p) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setArmorContents(new ItemStack[4]);
        inv.setItemInOffHand(null);
        inv.setItem(0, reportsItem());
        inv.setItem(1, tool(Material.COMPASS, "players", "&b&lPlayers", "&7Right-click: list online players", "&7(click one to inspect)"));
        inv.setItem(2, tool(mat("SPYGLASS", Material.STICK), "inspect", "&b&lInspect", "&7Right-click a player:", "&7violations, AI, ping, notes"));
        inv.setItem(3, tool(mat("PACKED_ICE", Material.ICE), "freeze", "&b&lFreeze", "&7Right-click a player:", "&7freeze / unfreeze"));
        inv.setItem(4, tool(Material.CHEST, "invsee", "&b&lInventory viewer", "&7Right-click a player:", "&7look inside (read-only)"));
        inv.setItem(5, tool(Material.ENDER_PEARL, "rtp", "&b&lRandom teleport", "&7Right-click: jump to a random player"));
        inv.setItem(6, tool(mat("WRITABLE_BOOK", Material.BOOK), "chat", "&b&lStaff chat", "&7Right-click: toggle staff chat"));
        inv.setItem(7, vanishItem(vanished.contains(p.getUniqueId())));
        inv.setItem(8, tool(mat("RED_DYE", Material.REDSTONE), "leave", "&c&lLeave staff mode", "&7Right-click: restore your inventory"));
        inv.setHeldItemSlot(0);
    }

    /** updates the report counter on every staff member's paper */
    public void refreshKits() {
        for (UUID id : saved.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.getInventory().setItem(0, reportsItem());
        }
    }

    // ------------------------------------------------------------------ enter / exit

    private File fileOf(UUID id) {
        return new File(dir, id + ".yml");
    }

    private void persist(UUID id, Saved s) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("storage", Arrays.asList(s.storage));
        y.set("armor", Arrays.asList(s.armor));
        y.set("offhand", s.offhand);
        y.set("gamemode", s.gm.name());
        y.set("allow-flight", s.allowFlight);
        y.set("flying", s.flying);
        y.set("invulnerable", s.invulnerable);
        y.set("collidable", s.collidable);
        y.set("sleep-ignored", s.sleepIgnored);
        try {
            dir.mkdirs();
            y.save(fileOf(id));
        } catch (IOException ex) {
            pl.getLogger().warning("Could not save staff-mode backup: " + ex.getMessage());
        }
    }

    private static ItemStack[] toArray(List<?> list, int size) {
        ItemStack[] arr = new ItemStack[size];
        if (list != null) {
            for (int i = 0; i < list.size() && i < size; i++) {
                Object o = list.get(i);
                arr[i] = o instanceof ItemStack ? (ItemStack) o : null;
            }
        }
        return arr;
    }

    private Saved readFile(UUID id) {
        File f = fileOf(id);
        if (!f.exists()) return null;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        Saved s = new Saved();
        s.storage = toArray(y.getList("storage"), 36);
        s.armor = toArray(y.getList("armor"), 4);
        s.offhand = y.getItemStack("offhand");
        try {
            s.gm = GameMode.valueOf(y.getString("gamemode", "SURVIVAL"));
        } catch (IllegalArgumentException ex) {
            s.gm = GameMode.SURVIVAL;
        }
        s.allowFlight = y.getBoolean("allow-flight");
        s.flying = y.getBoolean("flying");
        s.invulnerable = y.getBoolean("invulnerable");
        s.collidable = y.getBoolean("collidable", true);
        s.sleepIgnored = y.getBoolean("sleep-ignored");
        return s;
    }

    public boolean enter(Player p) {
        UUID id = p.getUniqueId();
        if (saved.containsKey(id)) return false;
        PlayerInventory inv = p.getInventory();
        Saved s = new Saved();
        s.storage = inv.getStorageContents();
        s.armor = inv.getArmorContents();
        s.offhand = inv.getItemInOffHand();
        s.gm = p.getGameMode();
        s.allowFlight = p.getAllowFlight();
        s.flying = p.isFlying();
        s.invulnerable = p.isInvulnerable();
        s.collidable = p.isCollidable();
        s.sleepIgnored = p.isSleepingIgnored();
        saved.put(id, s);
        persist(id, s); // disk backup BEFORE anything is removed

        PlayerData pd = pl.data(p);
        pd.staffMode = true;
        pd.resetMotion();
        giveKit(p);
        p.setAllowFlight(true);
        p.setInvulnerable(true);
        p.setCollidable(false);
        p.setSleepingIgnored(true);
        setVanish(p, true);
        pl.vio().setAlerts(id, true);
        msg(p, "&aStaff mode &2ON&a. You are vanished and your inventory is saved. Use the red dye (slot 9) to leave.");
        return true;
    }
      
    public boolean exit(Player p, boolean quiet) {
        UUID id = p.getUniqueId();
        Saved s = saved.remove(id);
if (s == null) return false;
frozen.remove(id);
restore(p, s);
        fileOf(id).delete();
        if (!quiet) msg(p, "&cStaff mode &4OFF&c. Your inventory was restored.");
        return true;
    }

    private void restore(Player p, Saved s) {
        UUID id = p.getUniqueId();
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setStorageContents(s.storage);
        inv.setArmorContents(s.armor);
        inv.setItemInOffHand(s.offhand);
        p.updateInventory();
        setVanish(p, false);
        chatMode.remove(id);
        awaiting.remove(id);
        p.setCollidable(s.collidable);
        p.setSleepingIgnored(s.sleepIgnored);
        p.setAllowFlight(s.allowFlight);
        if (!s.allowFlight || !s.flying) p.setFlying(false);
        if (p.getGameMode() != s.gm) p.setGameMode(s.gm);
        // stay invulnerable for a few seconds so leaving staff mode in mid-air cannot kill anyone
        p.setInvulnerable(true);
        final boolean back = s.invulnerable;
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (p.isOnline()) p.setInvulnerable(back);
        }, 100L);
        PlayerData pd = pl.dataOrNull(id);
        if (pd != null) {
            pd.staffMode = false;
            pd.resetMotion();
            pd.lastFlight = System.currentTimeMillis();
        }
    }

    /** crash recovery: the server stopped while this player was in staff mode */
    public void recoverIfNeeded(Player p) {
        UUID id = p.getUniqueId();
        if (saved.containsKey(id)) return;
        Saved s = readFile(id);
        if (s == null) return;
        restore(p, s);
        fileOf(id).delete();
        msg(p, "&eThe server stopped while you were in staff mode: your inventory was restored.");
    }

    public void toggle(Player p) {
        if (isStaffMode(p.getUniqueId())) exit(p, false);
        else enter(p);
    }

    public void exitAll() {
        for (UUID id : new ArrayList<>(saved.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) exit(p, true);
        }
    }

    // ------------------------------------------------------------------ vanish

    public void setVanish(Player p, boolean on) {
        UUID id = p.getUniqueId();
        if (on) vanished.add(id);
        else vanished.remove(id);
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(p)) continue;
            if (on && !o.hasPermission("vyxac.staff") && !o.hasPermission("vyxac.admin")) o.hidePlayer(pl, p);
            else o.showPlayer(pl, p);
        }
        if (saved.containsKey(id)) p.getInventory().setItem(7, vanishItem(on));
    }

    public void toggleVanish(Player p) {
        boolean now = !vanished.contains(p.getUniqueId());
        setVanish(p, now);
        msg(p, "Vanish " + (now ? "&aON" : "&cOFF") + "&7.");
    }

    /** a player joined: hide every vanished staff member from them */
    public void hideVanishedFrom(Player viewer) {
        if (viewer.hasPermission("vyxac.staff") || viewer.hasPermission("vyxac.admin")) return;
        for (UUID id : vanished) {
            Player v = Bukkit.getPlayer(id);
            if (v != null) viewer.hidePlayer(pl, v);
        }
    }

    // ------------------------------------------------------------------ freeze

    public void toggleFreeze(Player target, Player staff) {
        if (frozen.remove(target.getUniqueId())) {
            msg(staff, "&aUnfroze &f" + target.getName() + "&a.");
            msg(target, "&aYou were unfrozen.");
        } else {
            frozen.add(target.getUniqueId());
            msg(staff, "&bFroze &f" + target.getName() + "&b. They cannot move until you unfreeze them.");
            target.sendMessage(Settings.color("&c&lYou have been frozen by staff. &7Do not log out and follow their instructions."));
            try {
                target.sendTitle(Settings.color("&c&lFROZEN"), Settings.color("&7Follow staff instructions"), 5, 100, 10);
            } catch (Throwable ignored) {
                // titles are optional
            }
        }
    }

    public void unfreezeSilently(UUID id) {
        frozen.remove(id);
    }

    // ------------------------------------------------------------------ staff chat

    public void toggleChat(Player p) {
        boolean now = chatMode.add(p.getUniqueId());
        if (!now) chatMode.remove(p.getUniqueId());
        msg(p, "Staff chat " + (now ? "&aON &7(everything you type goes to staff only)" : "&cOFF") + "&7.");
    }

    public void staffChat(String from, String message) {
        String line = Settings.color(pl.getConfig().getString("staff.chat-format", "&8[&cStaff&8] &f%player%&8: &7%message%")
                .replace("%player%", from).replace("%message%", message));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("vyxac.staff") || p.hasPermission("vyxac.admin")) p.sendMessage(line);
        }
        Bukkit.getConsoleSender().sendMessage(line);
    }

    // ------------------------------------------------------------------ random teleport

    public void randomTeleport(Player p) {
        List<Player> c = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (!o.equals(p) && !isStaffMode(o.getUniqueId()) && !o.hasPermission("vyxac.staff")) c.add(o);
        }
        if (c.isEmpty()) {
            msg(p, "&7Nobody to teleport to.");
            return;
        }
        Player t = c.get(ThreadLocalRandom.current().nextInt(c.size()));
        p.teleport(t);
        msg(p, "&7Teleported to &f" + t.getName() + "&7.");
    }

    // ------------------------------------------------------------------ chat prompts

    public void await(UUID staff, Await a) {
        awaiting.put(staff, a);
    }

    /** returns the pending prompt (and clears it), or null when there is none / it expired after 2 minutes */
    public Await takeAwait(UUID staff) {
        Await a = awaiting.remove(staff);
        if (a == null) return null;
        return System.currentTimeMillis() - a.at > 120_000L ? null : a;
    }

    public boolean hasAwait(UUID staff) {
        return awaiting.containsKey(staff);
    }

    public void cleanup(UUID id) {
        chatMode.remove(id);
        awaiting.remove(id);
    }

    // ------------------------------------------------------------------ notes

    public void addNote(UUID target, String targetName, String staff, String text) {
        String clean = text.replaceAll("[&\u00a7|]", "");
        List<String> l = new ArrayList<>(notes.getStringList("notes." + target + ".list"));
        l.add(System.currentTimeMillis() + "|" + staff + "|" + clean);
        notes.set("notes." + target + ".name", targetName);
        notes.set("notes." + target + ".list", l);
        try {
            notes.save(notesFile);
        } catch (IOException ex) {
            pl.getLogger().warning("Could not save notes: " + ex.getMessage());
        }
    }

    /** each entry: {time, staff, text} */
    public List<String[]> notes(UUID target) {
        List<String[]> out = new ArrayList<>();
        for (String s : notes.getStringList("notes." + target + ".list")) {
            String[] p = s.split("\\|", 3);
            if (p.length == 3) out.add(p);
        }
        return out;
    }
}
