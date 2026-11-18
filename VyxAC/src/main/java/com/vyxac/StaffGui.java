package com.vyxac;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
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
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Staff menus: report tickets, online players, player inspector. */
public final class StaffGui implements Listener {
    private enum Screen { REPORTS, REPORT, PLAYERS, INSPECT }

    private static final class Holder implements InventoryHolder {
        Inventory inv;
        Screen screen;
        int page, reportId;
        UUID target;
        boolean closedView, sortVl;
        String filter = "";

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final int[] VL_SLOTS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};

    private final VyxAC pl;

    public StaffGui(VyxAC pl) {
        this.pl = pl;
    }

    // ===================================================================================== helpers

    private static Material mat(String name) {
        Material m = Material.matchMaterial(name);
        return m == null ? Material.PAPER : m;
    }

    private static List<String> lore(String... lines) {
        return new ArrayList<>(Arrays.asList(lines));
    }

    private ItemStack item(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Settings.color(name));
            List<String> l = new ArrayList<>();
            for (String s : lore) l.add(Settings.color(s));
            meta.setLore(l);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack head(OfflinePlayer op, String name, List<String> lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta m = it.getItemMeta();
        if (m instanceof SkullMeta sm) {
            sm.setOwningPlayer(op);
            sm.setDisplayName(Settings.color(name));
            List<String> l = new ArrayList<>();
            for (String s : lore) l.add(Settings.color(s));
            sm.setLore(l);
            it.setItemMeta(sm);
        }
        return it;
    }

    private static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String w : text.split(" ")) {
            if (line.length() > 0 && line.length() + w.length() + 1 > width) {
                out.add("&f" + line);
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(w);
        }
        if (line.length() > 0) out.add("&f" + line);
        return out;
    }

    private static String age(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m";
        long h = m / 60;
        return h < 48 ? h + "h" : (h / 24) + "d";
    }

    private boolean allowed(Player p) {
        return p.hasPermission("vyxac.staff") || p.hasPermission("vyxac.admin");
    }

    private void msg(Player p, String t) {
        p.sendMessage(Settings.color(pl.settings().prefix + t));
    }

    private Holder holder(Screen s, String title, int size) {
        Holder h = new Holder();
        h.screen = s;
        h.inv = Bukkit.createInventory(h, size, Settings.color(title));
        return h;
    }

    private static String topCheck(PlayerData pd) {
        String best = "";
        double v = 0;
        for (Map.Entry<String, Double> e : pd.vl.entrySet()) {
            if (e.getValue() > v) {
                v = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    private void prompt(Player p, StaffManager.Await a, String text) {
        pl.staff().await(p.getUniqueId(), a);
        p.closeInventory();
        msg(p, text + " &8(type 'cancel' to abort)");
    }

    // ===================================================================================== opening

    public void openReports(Player p, boolean closed, String filter, int page) {
        Holder h = holder(Screen.REPORTS, "&8Reports - " + (closed ? "closed" : "open"), 54);
        h.closedView = closed;
        h.filter = filter == null ? "" : filter;
        h.page = page;
        render(h);
        p.openInventory(h.inv);
    }

    public void openReport(Player p, int id) {
        Holder h = holder(Screen.REPORT, "&8Report #" + id, 36);
        h.reportId = id;
        render(h);
        p.openInventory(h.inv);
    }

    public void openPlayers(Player p, int page, boolean sortVl) {
        Holder h = holder(Screen.PLAYERS, "&8Online players", 54);
        h.page = page;
        h.sortVl = sortVl;
        h.target = p.getUniqueId();
        render(h);
        p.openInventory(h.inv);
    }

    public void openInspect(Player p, UUID target) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(target);
        Holder h = holder(Screen.INSPECT, "&8Inspect - " + (op.getName() == null ? "?" : op.getName()), 54);
        h.target = target;
        render(h);
        p.openInventory(h.inv);
    }

    private void render(Holder h) {
        h.inv.clear();
        switch (h.screen) {
            case REPORTS -> renderReports(h);
            case REPORT -> renderReport(h);
            case PLAYERS -> renderPlayers(h);
            case INSPECT -> renderInspect(h);
        }
    }

    // ===================================================================================== reports list

    private List<Report> visible(Holder h) {
        List<Report> base = h.closedView ? pl.reports().closed(200) : pl.reports().open();
        if (h.filter.isEmpty()) return base;
        List<Report> out = new ArrayList<>();
        for (Report r : base) if (r.targetName.equalsIgnoreCase(h.filter)) out.add(r);
        return out;
    }

    private ItemStack reportItem(Report r) {
        long now = System.currentTimeMillis();
        Material m = switch (r.status) {
            case Report.OPEN -> Material.PAPER;
            case Report.CLAIMED -> mat("WRITABLE_BOOK");
            case Report.ACTIONED -> mat("LIME_DYE");
            default -> mat("GRAY_DYE");
        };
        List<String> l = lore("&7Reported by: &f" + r.reporterName, "&7Against: &c" + r.targetName);
        l.addAll(wrap(r.reason, 36));
        l.add("&7Opened: &f" + age(now - r.createdAt) + " ago");
        if (r.status.equals(Report.CLAIMED)) l.add("&7Claimed by: &f" + r.claimedBy);
        if (!r.isOpen()) l.add("&7" + r.status.toLowerCase(Locale.ROOT) + " by &f" + r.closedBy + " &7" + age(now - r.closedAt) + " ago");
        if (!r.snapshot.isEmpty()) l.add("&8" + r.snapshot);
        l.add("");
        l.add("&eLeft-click: &7open ticket");
        if (r.isOpen()) l.add("&eRight-click: &7claim / unclaim");
        return item(m, "&e#" + r.id + " &f" + r.targetName, l);
    }

    private void renderReports(Holder h) {
        List<Report> list = visible(h);
        int per = 45, pages = Math.max(1, (list.size() + per - 1) / per);
        h.page = Math.max(0, Math.min(h.page, pages - 1));
        for (int i = h.page * per, slot = 0; i < list.size() && slot < per; i++, slot++) h.inv.setItem(slot, reportItem(list.get(i)));
        h.inv.setItem(45, item(mat("BARRIER"), "&cClose", lore()));
        if (h.page > 0) h.inv.setItem(48, item(mat("ARROW"), "&bPrevious page", lore()));
        List<String> info = lore("&7Page " + (h.page + 1) + "/" + pages);
        if (!h.filter.isEmpty()) {
            info.add("&7Filter: &f" + h.filter);
            info.add("&eClick to clear the filter");
        }
        h.inv.setItem(49, item(mat("PAPER"), "&7" + (h.closedView ? "Closed" : "Open") + " reports: &f" + list.size(), info));
        if (h.page < pages - 1) h.inv.setItem(50, item(mat("ARROW"), "&bNext page", lore()));
        h.inv.setItem(52, item(mat(h.closedView ? "PAPER" : "WRITABLE_BOOK"), h.closedView ? "&bShow open reports" : "&bShow closed reports", lore("&eClick to switch")));
        h.inv.setItem(53, item(mat("CLOCK"), "&bRefresh", lore()));
        if (list.isEmpty()) h.inv.setItem(22, item(mat("BARRIER"), "&7Nothing here", lore(h.closedView ? "&7No closed reports." : "&aNo open reports. Nice!")));
    }

    // ===================================================================================== single report

    private void renderReport(Holder h) {
        Report r = pl.reports().get(h.reportId);
        if (r == null) {
            h.inv.setItem(13, item(mat("BARRIER"), "&cReport not found", lore("&7It may have been removed.")));
            h.inv.setItem(27, item(mat("ARROW"), "&bBack", lore()));
            return;
        }
        long now = System.currentTimeMillis();
        List<String> info = lore("&7Reported by: &f" + r.reporterName, "&7Against: &c" + r.targetName);
        info.addAll(wrap(r.reason, 36));
        info.add("&7Opened: &f" + age(now - r.createdAt) + " ago");
        info.add("&7Status: &f" + r.status + (r.claimedBy.isEmpty() ? "" : " (" + r.claimedBy + ")"));
        if (!r.snapshot.isEmpty()) info.add("&8" + r.snapshot);
        if (!r.isOpen()) info.add("&7Closed by &f" + r.closedBy + (r.resolution.isEmpty() ? "" : "&7: &f" + r.resolution));
        h.inv.setItem(4, item(mat("PAPER"), "&e#" + r.id + " &f" + r.targetName, info));

        List<String> hist = lore("&7" + r.messages.size() + " message(s)");
        int from = Math.max(0, r.messages.size() - 6);
        for (int i = from; i < r.messages.size(); i++) {
            String[] p = r.messages.get(i).split("\\|", 3);
            if (p.length == 3) hist.add("&b" + p[0] + "&7: &f" + (p[2].length() > 40 ? p[2].substring(0, 40) + "..." : p[2]));
        }
        h.inv.setItem(14, item(mat("BOOK"), "&bConversation", hist));

        if (r.isOpen()) {
            h.inv.setItem(10, item(mat("ENDER_PEARL"), "&bTeleport to " + r.targetName, lore("&7The player must be online.")));
            h.inv.setItem(11, item(mat("SPYGLASS"), "&bInspect " + r.targetName, lore("&7Violations, AI, ping, notes")));
            boolean claimed = r.status.equals(Report.CLAIMED);
            h.inv.setItem(12, item(mat("NAME_TAG"), claimed ? "&eUnclaim" : "&aClaim this ticket", lore("&7Shows other staff you are on it.")));
            h.inv.setItem(13, item(mat("WRITABLE_BOOK"), "&bReply to " + r.reporterName, lore("&7Click, then type your message in chat.", "&7They get it in chat (or on next join).")));
            h.inv.setItem(19, item(mat("RED_DYE"), "&cDismiss: no violation", lore("&7Closes the ticket and tells the reporter.", "", "&eLeft-click: &7default message", "&eRight-click: &7add your own note")));
            h.inv.setItem(20, item(mat("LIME_DYE"), "&aClose: action taken", lore("&7Closes the ticket and tells the reporter.", "", "&eLeft-click: &7default message", "&eRight-click: &7add your own note")));
            if (pl.settings() != null) {
                h.inv.setItem(22, item(mat("ANVIL"), "&4Punish " + r.targetName, lore("&7Runs the punishment ladder and closes", "&7the ticket. Needs vyxac.admin.", "", "&eShift-click to confirm")));
            }
        } else {
            h.inv.setItem(19, item(mat("LEVER"), "&eReopen ticket", lore("&7Moves it back to the open list.")));
        }
        h.inv.setItem(27, item(mat("ARROW"), "&bBack to list", lore()));
    }

    // ===================================================================================== players

    private void renderPlayers(Holder h) {
        List<Player> list = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) if (!o.getUniqueId().equals(h.target)) list.add(o);
        if (h.sortVl) {
            list.sort(Comparator.comparingDouble((Player o) -> {
                PlayerData pd = pl.dataOrNull(o.getUniqueId());
                return pd == null ? 0 : -pd.totalVl();
            }));
        } else {
            list.sort(Comparator.comparing((Player o) -> o.getName().toLowerCase(Locale.ROOT)));
        }
        int per = 45, pages = Math.max(1, (list.size() + per - 1) / per);
        h.page = Math.max(0, Math.min(h.page, pages - 1));
        for (int i = h.page * per, slot = 0; i < list.size() && slot < per; i++, slot++) {
            Player o = list.get(i);
            PlayerData pd = pl.dataOrNull(o.getUniqueId());
            boolean fr = pl.staff().isFrozen(o.getUniqueId());
            List<String> l = lore("&7Ping: &f" + (pd == null ? "?" : pd.ping));
            if (pd != null) {
                l.add("&7Total VL: &f" + String.format("%.1f", pd.totalVl()) + (topCheck(pd).isEmpty() ? "" : " &8(" + topCheck(pd) + ")"));
                l.add("&7AI confidence: &f" + String.format("%.0f%%", pd.aiConfidence * 100));
            }
            int rep = pl.reports().openAgainst(o.getUniqueId());
            if (rep > 0) l.add("&cOpen reports against them: " + rep);
            if (fr) l.add("&bFrozen");
            l.add("");
            l.add("&eClick to inspect");
            h.inv.setItem(slot, head(o, (fr ? "&b[FROZEN] " : "") + "&f" + o.getName(), l));
        }
        h.inv.setItem(45, item(mat("BARRIER"), "&cClose", lore()));
        if (h.page > 0) h.inv.setItem(48, item(mat("ARROW"), "&bPrevious page", lore()));
        h.inv.setItem(49, item(mat("PAPER"), "&7Page " + (h.page + 1) + "/" + pages, lore("&7" + list.size() + " players")));
        if (h.page < pages - 1) h.inv.setItem(50, item(mat("ARROW"), "&bNext page", lore()));
        h.inv.setItem(52, item(mat("COMPARATOR"), "&bSort: " + (h.sortVl ? "highest VL first" : "A-Z"), lore("&eClick to switch")));
    }

    // ===================================================================================== inspector

    private void renderInspect(Holder h) {
        UUID id = h.target;
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        Player tp = Bukkit.getPlayer(id);
        PlayerData pd = pl.dataOrNull(id);
        String name = op.getName() == null ? "?" : op.getName();
        List<String> l = new ArrayList<>();
        if (tp == null || pd == null) {
            l.add("&7Offline: live data is not available.");
        } else {
            l.add("&7Ping: &f" + pd.ping);
            l.add("&7Total VL: &f" + String.format("%.1f", pd.totalVl()));
            l.add("&7Flags this session: &f" + pd.flagsTotal);
            l.add("&7Punishment strikes: &f" + pd.strikes);
            l.add("&7AI confidence: &f" + String.format("%.0f%%", pd.aiConfidence * 100));
            if (pl.staff().isFrozen(id)) l.add("&bFrozen");
        }
        l.add("&7Open reports about them: &f" + pl.reports().openAgainst(id));
        h.inv.setItem(4, head(op, "&b" + name, l));

        if (pd != null) {
            List<Map.Entry<String, Double>> vl = new ArrayList<>(pd.vl.entrySet());
            vl.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            for (int i = 0; i < Math.min(VL_SLOTS.length, vl.size()); i++) {
                String check = vl.get(i).getKey();
                CheckInfo ci = pl.checks().find(check);
                List<String> cl = lore(ci == null ? "" : "&7" + ci.description);
                if (ci != null) cl.add("&7Punish evidence at VL " + (ci.punishable ? (int) ci.punishVl : 0) + (ci.punishable ? "" : " (alert only)"));
                h.inv.setItem(VL_SLOTS[i], item(CustomizeGui.iconFor(check), "&c" + check + " &f" + String.format("%.1f", vl.get(i).getValue()), cl));
            }
            if (vl.isEmpty()) h.inv.setItem(22, item(mat("LIME_DYE"), "&aNo violations", lore("&7Nothing flagged right now.")));
        }

        List<String[]> notes = pl.staff().notes(id);
        List<String> nl = lore("&7" + notes.size() + " note(s)");
        for (int i = Math.max(0, notes.size() - 3); i < notes.size(); i++) {
            String t = notes.get(i)[2];
            nl.add("&b" + notes.get(i)[1] + "&7: &f" + (t.length() > 36 ? t.substring(0, 36) + "..." : t));
        }
        nl.add("");
        nl.add("&eClick to add a note");

        h.inv.setItem(45, item(mat("ARROW"), "&bBack to players", lore()));
        h.inv.setItem(46, item(mat("ENDER_PEARL"), "&bTeleport", lore(tp == null ? "&cOffline" : "&7Teleport to them")));
        h.inv.setItem(47, item(mat("PACKED_ICE"), pl.staff().isFrozen(id) ? "&bUnfreeze" : "&bFreeze", lore(tp == null ? "&cOffline" : "&7Toggle freeze")));
        h.inv.setItem(48, item(mat("CHEST"), "&bInventory viewer", lore(tp == null ? "&cOffline" : "&7Read-only view")));
        h.inv.setItem(49, item(mat("WRITABLE_BOOK"), "&bStaff notes", nl));
        h.inv.setItem(50, item(mat("PAPER"), "&bReports about them", lore("&7Open the ticket list filtered to " + name)));
        h.inv.setItem(51, item(mat("MILK_BUCKET"), "&aClear violations", lore("&7Marks them as clean (resets VL).")));
        h.inv.setItem(52, item(mat("ANVIL"), "&4Punish", lore("&7Runs the punishment ladder (vyxac.admin).", "", "&eShift-click to confirm")));
    }

    // ===================================================================================== clicks

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !allowed(p)) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getRawSlot();
        ClickType click = e.getClick();
        boolean redraw = false;
        switch (h.screen) {
            case REPORTS -> redraw = clickReports(p, h, slot, click);
            case REPORT -> redraw = clickReport(p, h, slot, click);
            case PLAYERS -> redraw = clickPlayers(p, h, slot);
            case INSPECT -> redraw = clickInspect(p, h, slot, click);
        }
        if (redraw && p.getOpenInventory().getTopInventory() == h.inv) render(h);
    }

    private boolean clickReports(Player p, Holder h, int slot, ClickType click) {
        if (slot == 45) {
            p.closeInventory();
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
        if (slot == 49 && !h.filter.isEmpty()) {
            h.filter = "";
            h.page = 0;
            return true;
        }
        if (slot == 52) {
            h.closedView = !h.closedView;
            h.page = 0;
            return true;
        }
        if (slot == 53) return true;
        if (slot < 0 || slot >= 45) return false;
        List<Report> list = visible(h);
        int idx = h.page * 45 + slot;
        if (idx >= list.size()) return false;
        Report r = list.get(idx);
        if ((click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) && r.isOpen()) {
            if (r.status.equals(Report.CLAIMED) && r.claimedBy.equals(p.getName())) pl.reports().unclaim(r);
            else pl.reports().claim(r, p.getName());
            return true;
        }
        openReport(p, r.id);
        return false;
    }

    private boolean clickReport(Player p, Holder h, int slot, ClickType click) {
        Report r = pl.reports().get(h.reportId);
        if (slot == 27 || r == null) {
            openReports(p, r != null && !r.isOpen(), "", 0);
            return false;
        }
        boolean right = click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT;
        if (!r.isOpen()) {
            if (slot == 19) {
                pl.reports().reopen(r);
                return true;
            }
            return false;
        }
        switch (slot) {
            case 10 -> {
                Player t = Bukkit.getPlayer(r.targetId);
                if (t == null) msg(p, "&c" + r.targetName + " is offline.");
                else {
                    p.closeInventory();
                    p.teleport(t);
                    msg(p, "&7Teleported to &f" + t.getName() + "&7.");
                }
                return false;
            }
            case 11 -> {
                if (Bukkit.getPlayer(r.targetId) == null) msg(p, "&c" + r.targetName + " is offline: no live data.");
                else openInspect(p, r.targetId);
                return false;
            }
            case 12 -> {
                if (r.status.equals(Report.CLAIMED) && r.claimedBy.equals(p.getName())) pl.reports().unclaim(r);
                else pl.reports().claim(r, p.getName());
                return true;
            }
            case 13 -> {
                prompt(p, new StaffManager.Await(StaffManager.AwaitType.REPLY, r.id, null, ""), "&bType your reply to &f" + r.reporterName + "&b in chat.");
                return false;
            }
            case 19 -> {
                if (right) {
                    prompt(p, new StaffManager.Await(StaffManager.AwaitType.DISMISS_NOTE, r.id, null, ""), "&bType the note for the reporter; the ticket will be dismissed.");
                    return false;
                }
                pl.reports().close(r, p.getName(), false, "");
                msg(p, "&7Report #" + r.id + " dismissed. The reporter was told.");
                openReports(p, false, "", 0);
                return false;
            }
            case 20 -> {
                if (right) {
                    prompt(p, new StaffManager.Await(StaffManager.AwaitType.ACTION_NOTE, r.id, null, ""), "&bType the note for the reporter; the ticket will be closed.");
                    return false;
                }
                pl.reports().close(r, p.getName(), true, "");
                msg(p, "&aReport #" + r.id + " closed as action taken. The reporter was told.");
                openReports(p, false, "", 0);
                return false;
            }
            case 22 -> {
                if (!click.isShiftClick()) return false;
                if (!p.hasPermission("vyxac.admin")) {
                    msg(p, "&cYou need vyxac.admin to punish.");
                    return false;
                }
                PlayerData pd = pl.dataOrNull(r.targetId);
                if (pd == null || Bukkit.getPlayer(r.targetId) == null) {
                    msg(p, "&c" + r.targetName + " is offline.");
                    return false;
                }
                pl.punish().punish(pd, topCheck(pd), true);
                pl.reports().close(r, p.getName(), true, "punished");
                msg(p, "&aPunishment executed and report #" + r.id + " closed.");
                openReports(p, false, "", 0);
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    private boolean clickPlayers(Player p, Holder h, int slot) {
        if (slot == 45) {
            p.closeInventory();
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
        if (slot == 52) {
            h.sortVl = !h.sortVl;
            h.page = 0;
            return true;
        }
        if (slot < 0 || slot >= 45) return false;
        List<Player> list = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) if (!o.getUniqueId().equals(h.target)) list.add(o);
        if (h.sortVl) {
            list.sort(Comparator.comparingDouble((Player o) -> {
                PlayerData pd = pl.dataOrNull(o.getUniqueId());
                return pd == null ? 0 : -pd.totalVl();
            }));
        } else {
            list.sort(Comparator.comparing((Player o) -> o.getName().toLowerCase(Locale.ROOT)));
        }
        int idx = h.page * 45 + slot;
        if (idx < list.size()) openInspect(p, list.get(idx).getUniqueId());
        return false;
    }

    private boolean clickInspect(Player p, Holder h, int slot, ClickType click) {
        Player tp = Bukkit.getPlayer(h.target);
        PlayerData pd = pl.dataOrNull(h.target);
        OfflinePlayer op = Bukkit.getOfflinePlayer(h.target);
        String name = op.getName() == null ? "?" : op.getName();
        switch (slot) {
            case 45 -> {
                openPlayers(p, 0, false);
                return false;
            }
            case 46 -> {
                if (tp == null) msg(p, "&c" + name + " is offline.");
                else {
                    p.closeInventory();
                    p.teleport(tp);
                }
                return false;
            }
            case 47 -> {
                if (tp == null) {
                    msg(p, "&c" + name + " is offline.");
                    return false;
                }
                pl.staff().toggleFreeze(tp, p);
                return true;
            }
            case 48 -> {
                if (tp == null) msg(p, "&c" + name + " is offline.");
                else p.openInventory(tp.getInventory());
                return false;
            }
            case 49 -> {
                prompt(p, new StaffManager.Await(StaffManager.AwaitType.STAFF_NOTE, 0, h.target, name), "&bType the note for &f" + name + "&b.");
                return false;
            }
            case 50 -> {
                openReports(p, false, name, 0);
                return false;
            }
            case 51 -> {
                if (pd != null) {
                    pl.punish().dismiss(pd);
                    msg(p, "&aCleared violations of &f" + name + "&a.");
                }
                return true;
            }
            case 52 -> {
                if (!click.isShiftClick()) return false;
                if (!p.hasPermission("vyxac.admin")) {
                    msg(p, "&cYou need vyxac.admin to punish.");
                    return false;
                }
                if (pd == null || tp == null) {
                    msg(p, "&c" + name + " is offline.");
                    return false;
                }
                pl.punish().punish(pd, topCheck(pd), true);
                msg(p, "&aPunishment ladder executed for &f" + name + "&a.");
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
}
