package com.vyxac.hacks;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.vyxac.VyxAC;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Sends virtual signs to a client and collects what the client types back (the sign translation exploit).
 *
 *  1. a fake sign block is shown 5 blocks above (or below) the player, away from the line of sight
 *  2. the sign text is set with translatable / keybind components (Paper's Adventure sign API builds the correct
 *     block entity data for the running Minecraft version, so no NBT is written by hand)
 *  3. the sign editor is opened and closed in the SAME tick (ProtocolLib packets), so nothing can be read
 *  4. closing the editor makes the client send the sign text, already translated, which ProtocolLib hands to us
 *  5. the real blocks are restored
 * Nothing is ever placed in the world.
 */
public final class SignPackets {
    private static final class Session {
        final UUID uuid;
        final Location[] locs;
        final String[][] answers;
        final Map<Long, Integer> index = new ConcurrentHashMap<>();
        final AtomicInteger got = new AtomicInteger();
        final Consumer<String[][]> done;
        volatile boolean finished;
        BukkitTask timeout;

        Session(UUID uuid, int signs, Consumer<String[][]> done) {
            this.uuid = uuid;
            this.locs = new Location[signs];
            this.answers = new String[signs][];
            this.done = done;
        }
    }

    private final VyxAC pl;
    private final ProtocolManager pm;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final PacketAdapter listener;
    private final Method signChange, translatable, keybind, empty;

    public SignPackets(VyxAC pl) throws Exception {
        this.pl = pl;
        this.pm = ProtocolLibrary.getProtocolManager();
        Class<?> component = Class.forName("net.kyori.adventure.text.Component");
        this.translatable = component.getMethod("translatable", String.class);
        this.keybind = component.getMethod("keybind", String.class);
        this.empty = component.getMethod("empty");
        this.signChange = Player.class.getMethod("sendSignChange", Location.class, List.class, DyeColor.class, boolean.class);
        this.listener = new PacketAdapter(pl, ListenerPriority.LOWEST, PacketType.Play.Client.UPDATE_SIGN) {
            @Override
            public void onPacketReceiving(PacketEvent e) {
                onSignUpdate(e);
            }
        };
        pm.addPacketListener(listener);
    }

    public void shutdown() {
        try {
            pm.removePacketListener(listener);
        } catch (Throwable ignored) {
            // shutting down anyway
        }
        for (Session s : sessions.values()) if (s.timeout != null) s.timeout.cancel();
        sessions.clear();
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** runs on a network thread */
    private void onSignUpdate(PacketEvent e) {
        Player p = e.getPlayer();
        if (p == null) return;
        Session s = sessions.get(p.getUniqueId());
        if (s == null || s.finished) return;
        try {
            BlockPosition bp = e.getPacket().getBlockPositionModifier().read(0);
            Integer idx = s.index.get(key(bp.getX(), bp.getY(), bp.getZ()));
            if (idx == null) return;
            String[] lines = e.getPacket().getStringArrays().read(0);
            s.answers[idx] = lines == null ? new String[4] : lines.clone();
            e.setCancelled(true); // the server must never treat it as a real sign edit
            if (s.got.incrementAndGet() >= s.answers.length) Bukkit.getScheduler().runTask(pl, () -> finish(s));
        } catch (Throwable t) {
            // a malformed packet must not break anything
        }
    }

    private Object component(String kind, String key) throws Exception {
        return switch (kind) {
            case "TRANSLATE" -> translatable.invoke(null, key);
            case "KEYBIND" -> keybind.invoke(null, key);
            default -> empty.invoke(null);
        };
    }

    private void sendSign(Player p, Location loc, ProbeEval.Sign sign) throws Exception {
        p.sendBlockChange(loc, Material.OAK_SIGN.createBlockData());
        List<Object> lines = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) lines.add(component(sign.kinds[i], sign.keys[i]));
        signChange.invoke(p, loc, lines, DyeColor.BLACK, false);

        PacketContainer open = pm.createPacket(PacketType.Play.Server.OPEN_SIGN_EDITOR);
        open.getBlockPositionModifier().write(0, new BlockPosition(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
        if (open.getBooleans().size() > 0) open.getBooleans().write(0, true); // front text (1.20+)
        pm.sendServerPacket(p, open);

        PacketContainer close = pm.createPacket(PacketType.Play.Server.CLOSE_WINDOW); // same tick: the editor is gone before it can be drawn
        close.getIntegers().write(0, 0);
        pm.sendServerPacket(p, close);
    }

    private Location position(Player p, int i, String mode) {
        Location base = p.getLocation();
        World w = base.getWorld();
        boolean above = mode.equals("above") || (mode.equals("auto") && base.getBlockY() + 6 < w.getMaxHeight() - 1);
        int y = above ? Math.min(w.getMaxHeight() - 2, base.getBlockY() + 5) : Math.max(w.getMinHeight() + 1, base.getBlockY() - 5);
        return new Location(w, base.getBlockX() + (i % 5) - 2, y, base.getBlockZ() + (i / 5) - 2);
    }

    /** @param done called on the main thread; an entry is null when the client never answered; the whole array is null on failure */
    public void probe(Player p, List<ProbeEval.Sign> signs, int betweenTicks, int timeoutTicks, String positionMode, Consumer<String[][]> done) {
        final Session s = new Session(p.getUniqueId(), signs.size(), done);
        sessions.put(p.getUniqueId(), s);
        for (int i = 0; i < signs.size(); i++) {
            final int idx = i;
            final ProbeEval.Sign sign = signs.get(i);
            Bukkit.getScheduler().runTaskLater(pl, () -> {
                if (s.finished) return;
                if (!p.isOnline()) {
                    abort(s);
                    return;
                }
                try {
                    Location loc = position(p, idx, positionMode);
                    s.locs[idx] = loc;
                    s.index.put(key(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()), idx);
                    sendSign(p, loc, sign);
                } catch (Throwable t) {
                    pl.getLogger().warning("CheckHacks could not send a sign: " + t);
                    abort(s);
                }
            }, (long) i * betweenTicks);
        }
        long total = (long) (signs.size() - 1) * betweenTicks + timeoutTicks;
        s.timeout = Bukkit.getScheduler().runTaskLater(pl, () -> finish(s), total);
    }

    /** a player left: forget their session */
    public void cancel(UUID id) {
        Session s = sessions.get(id);
        if (s != null) abort(s);
    }

    private void abort(Session s) {
        if (s.finished) return;
        s.finished = true;
        if (s.timeout != null) s.timeout.cancel();
        sessions.remove(s.uuid, s);
        restore(s);
        s.done.accept(null);
    }

    private void finish(Session s) {
        if (s.finished) return;
        s.finished = true;
        if (s.timeout != null) s.timeout.cancel();
        sessions.remove(s.uuid, s);
        restore(s);
        s.done.accept(s.answers);
    }

    /** puts the real blocks back on the client */
    private void restore(Session s) {
        Player p = Bukkit.getPlayer(s.uuid);
        if (p == null || !p.isOnline()) return;
        for (Location l : s.locs) {
            if (l == null) continue;
            try {
                if (l.getWorld() != null && l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) {
                    p.sendBlockChange(l, l.getBlock().getBlockData());
                }
            } catch (Throwable ignored) {
                // the client resyncs the block when the chunk is next sent
            }
        }
    }
}
