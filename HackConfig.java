package com.vyxac.hacks;

import com.vyxac.VyxAC;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Every scan is saved. SQLite (WAL mode, ONE connection, all access on ONE background thread) when the driver is present
 * (Spigot and Paper ship it); otherwise the last 300 scans are kept in memory so the rest of the feature still works.
 */
public final class ScanStore {
    private final VyxAC pl;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VyxAC-scans");
        t.setDaemon(true);
        return t;
    });
    private final List<ScanResult> memory = Collections.synchronizedList(new ArrayList<>());
    private Connection conn;
    private boolean sql;
    private long memoryId = 1;

    public ScanStore(VyxAC pl) {
        this.pl = pl;
    }

    public boolean sql() {
        return sql;
    }

    public void open() {
        try {
            Class.forName("org.sqlite.JDBC");
            File f = new File(pl.getDataFolder(), "checkhacks.db");
            f.getParentFile().mkdirs();
            conn = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
            try (Statement s = conn.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA synchronous=NORMAL");
                s.execute("CREATE TABLE IF NOT EXISTS scans(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, uuid TEXT, name TEXT, reason TEXT, starter TEXT, dc INTEGER)");
                s.execute("CREATE TABLE IF NOT EXISTS results(scan_id INTEGER, mod_id TEXT, mod_name TEXT, result TEXT)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_scans_name ON scans(name COLLATE NOCASE)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_results_scan ON results(scan_id)");
            }
            sql = true;
        } catch (Throwable t) {
            sql = false;
            pl.getLogger().warning("CheckHacks: SQLite is not available (" + t + "): scans are kept in memory only.");
        }
    }

    public void save(ScanResult r) {
        synchronized (memory) {
            r.id = sql ? 0 : memoryId++;
            memory.add(r);
            while (memory.size() > 300) memory.remove(0);
        }
        if (!sql) return;
        io.execute(() -> {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO scans(ts,uuid,name,reason,starter,dc) VALUES(?,?,?,?,?,?)")) {
                ps.setLong(1, r.ts);
                ps.setString(2, r.uuid.toString());
                ps.setString(3, r.name);
                ps.setString(4, r.reason);
                ps.setString(5, r.starter);
                ps.setInt(6, r.doubleChecked ? 1 : 0);
                ps.executeUpdate();
                long id;
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                    rs.next();
                    id = rs.getLong(1);
                }
                r.id = id;
                try (PreparedStatement pr = conn.prepareStatement("INSERT INTO results(scan_id,mod_id,mod_name,result) VALUES(?,?,?,?)")) {
                    for (var e : r.results.entrySet()) {
                        pr.setLong(1, id);
                        pr.setString(2, e.getKey());
                        pr.setString(3, e.getValue().modName);
                        pr.setString(4, e.getValue().result.name());
                        pr.addBatch();
                    }
                    pr.executeBatch();
                }
            } catch (Throwable t) {
                pl.getLogger().warning("CheckHacks: could not save a scan: " + t.getMessage());
            }
        });
    }

    private List<ScanResult> query(String name, int limit) throws Exception {
        List<ScanResult> out = new ArrayList<>();
        String sqlText = "SELECT id,ts,uuid,name,reason,starter,dc FROM scans" + (name == null ? "" : " WHERE name = ? COLLATE NOCASE")
                + " ORDER BY id DESC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sqlText)) {
            int i = 1;
            if (name != null) ps.setString(i++, name);
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID u;
                    try {
                        u = UUID.fromString(rs.getString(3));
                    } catch (IllegalArgumentException e) {
                        u = new UUID(0, 0);
                    }
                    ScanResult r = new ScanResult(rs.getLong(2), u, rs.getString(4), rs.getString(5), rs.getString(6));
                    r.id = rs.getLong(1);
                    r.doubleChecked = rs.getInt(7) == 1;
                    out.add(r);
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT mod_id,mod_name,result FROM results WHERE scan_id = ?")) {
            for (ScanResult r : out) {
                ps.setLong(1, r.id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            r.results.put(rs.getString(1), new ScanResult.Entry(rs.getString(2), ProbeEval.Result.valueOf(rs.getString(3))));
                        } catch (IllegalArgumentException ignored) {
                            // unknown result name from an older version
                        }
                    }
                }
            }
        }
        return out;
    }

    private List<ScanResult> run(String name, int limit) {
        if (!sql) {
            List<ScanResult> out = new ArrayList<>();
            synchronized (memory) {
                for (int i = memory.size() - 1; i >= 0 && out.size() < limit; i--) {
                    ScanResult r = memory.get(i);
                    if (name == null || r.name.equalsIgnoreCase(name)) out.add(r);
                }
            }
            return out;
        }
        Future<List<ScanResult>> f = io.submit(() -> query(name, limit));
        try {
            return f.get(4, TimeUnit.SECONDS);
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    public List<ScanResult> recent(int limit) {
        return run(null, Math.max(1, Math.min(limit, 500)));
    }

    public List<ScanResult> player(String name, int limit) {
        return run(name, Math.max(1, Math.min(limit, 500)));
    }

    public void close() {
        io.shutdown();
        try {
            io.awaitTermination(3, TimeUnit.SECONDS);
            if (conn != null) conn.close();
        } catch (Throwable ignored) {
            // closing
        }
    }
}
