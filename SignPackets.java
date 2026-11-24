package com.vyxac.hacks;

import com.vyxac.VyxAC;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Update checker for YOUR GitHub releases (updater.repo = "owner/repo"). It never replaces anything by itself:
 * a staff member runs /checkhacksupdate, the jar is downloaded into plugins/update/ under the current file name and Bukkit
 * installs it at the next restart. Only github.com hosts are accepted, the file must be a jar (zip signature), size capped.
 */
public final class Updater {
    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([0-9][^\"]*)\"");
    private static final Pattern ASSET = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"");
    private static final long MAX_BYTES = 40L * 1024 * 1024;

    private final VyxAC pl;
    private final HackChecker checker;
    private volatile String latest;
    private volatile String assetUrl;
    private volatile boolean busy;

    public Updater(VyxAC pl, HackChecker checker) {
        this.pl = pl;
        this.checker = checker;
    }

    public boolean hasUpdate() {
        return latest != null && assetUrl != null;
    }

    public String latest() {
        return latest;
    }

    static boolean newer(String a, String b) {
        String[] x = a.split("[^0-9]+"), y = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static boolean allowedHost(String host) {
        return host != null && (host.equals("github.com") || host.equals("api.github.com") || host.endsWith(".githubusercontent.com"));
    }

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "VyxAC-updater");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        return c;
    }

    /** background check; if the server is offline nothing is printed */
    public void check() {
        final HackConfig c = checker.cfg();
        if (!c.updaterEnabled || !c.updaterRepo.trim().matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) return;
        Bukkit.getScheduler().runTaskAsynchronously(pl, () -> {
            try {
                HttpURLConnection con = open("https://api.github.com/repos/" + c.updaterRepo.trim() + "/releases/latest");
                if (con.getResponseCode() != 200) return;
                String body;
                try (InputStream in = con.getInputStream()) {
                    body = new String(in.readNBytes(500_000), StandardCharsets.UTF_8);
                }
                Matcher t = TAG.matcher(body);
                if (!t.find()) return;
                String url = null;
                Matcher a = ASSET.matcher(body);
                while (a.find()) {
                    if (a.group(1).toLowerCase().endsWith(".jar")) {
                        url = a.group(1);
                        break;
                    }
                }
                if (url != null && newer(t.group(1), pl.getDescription().getVersion())) {
                    assetUrl = url;
                    latest = t.group(1);
                }
            } catch (Throwable ignored) {
                // offline or rate limited: stay silent
            }
        });
    }

    /** /checkhacksupdate */
    public void download(CommandSender s) {
        final HackText tx = checker.text();
        HackConfig c = checker.cfg();
        if (!c.updaterEnabled) {
            s.sendMessage(tx.prefixed("update-disabled"));
            return;
        }
        if (!hasUpdate()) {
            s.sendMessage(tx.prefixed("update-no-update"));
            return;
        }
        if (busy) return;
        busy = true;
        final String url = assetUrl, version = latest;
        s.sendMessage(tx.prefixed("update-downloading", "version", version));
        Bukkit.getScheduler().runTaskAsynchronously(pl, () -> {
            File part = null;
            try {
                URL u = new URL(url);
                if (!u.getProtocol().equals("https") || !allowedHost(u.getHost())) throw new IllegalStateException("download host is not allowed");
                HttpURLConnection con = open(url);
                try (InputStream in = con.getInputStream()) {
                    if (!allowedHost(con.getURL().getHost())) throw new IllegalStateException("redirected to a host that is not allowed");
                    File dir = Bukkit.getUpdateFolderFile();
                    dir.mkdirs();
                    File target = new File(dir, pl.jarFile().getName());
                    part = new File(dir, pl.jarFile().getName() + ".part");
                    byte[] data = in.readNBytes((int) MAX_BYTES + 1);
                    if (data.length > MAX_BYTES) throw new IllegalStateException("file is larger than 40 MB");
                    if (data.length < 4 || data[0] != 'P' || data[1] != 'K') throw new IllegalStateException("the file is not a jar");
                    Files.write(part.toPath(), data);
                    Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                Bukkit.getScheduler().runTask(pl, () -> {
                    s.sendMessage(tx.prefixed("update-complete"));
                    s.sendMessage(tx.prefixed("update-restart-required"));
                });
            } catch (Throwable t) {
                if (part != null) part.delete();
                final String why = String.valueOf(t.getMessage());
                Bukkit.getScheduler().runTask(pl, () -> s.sendMessage(tx.prefixed("update-failed", "reason", why)));
            } finally {
                busy = false;
            }
        });
    }
}
