package com.vyxac.hacks;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vyxac.VyxAC;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Built-in web editor (JDK HttpServer, no extra libraries). Login = a ONE-TIME token created in game with /cheditor, then a
 * random session cookie (HttpOnly, SameSite=Strict). No passwords exist. Every page and API answer is served with strict headers,
 * the page only inserts data with textContent (no HTML injection) and POST requests need a custom header (no cross-site requests).
 * It listens on 127.0.0.1 by default; to expose it, put a reverse proxy with HTTPS in front of it.
 */
public final class WebEditor {
    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final VyxAC pl;
    private final HackChecker checker;
    private HttpServer server;
    private final Map<String, Long> tokens = new ConcurrentHashMap<>();
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final Map<String, long[]> attempts = new ConcurrentHashMap<>();
    private final SecureRandom rnd = new SecureRandom();

    public WebEditor(VyxAC pl, HackChecker checker) {
        this.pl = pl;
        this.checker = checker;
    }

    public boolean running() {
        return server != null;
    }

    public void start() {
        HackConfig c = checker.cfg();
        if (!c.webEnabled || server != null) return;
        try {
            server = HttpServer.create(new InetSocketAddress(c.webBind, c.webPort), 0);
            server.createContext("/", this::handle);
            server.setExecutor(Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "VyxAC-web");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            pl.getLogger().info("CheckHacks web editor listening on " + c.webBind + ":" + c.webPort + " (login: /cheditor)");
        } catch (Throwable t) {
            server = null;
            pl.getLogger().warning("CheckHacks web editor could not start: " + t.getMessage());
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    public void restart() {
        stop();
        start();
    }

    private String random(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    public String newToken() {
        long now = System.currentTimeMillis();
        tokens.values().removeIf(v -> v < now);
        String t = random(24);
        tokens.put(t, now + checker.cfg().tokenMinutes * 60_000L);
        return t;
    }

    public String urlFor(String token) {
        HackConfig c = checker.cfg();
        String base = c.webPublicUrl.isBlank() ? "http://" + (c.webBind.equals("0.0.0.0") ? "localhost" : c.webBind) + ":" + c.webPort
                : c.webPublicUrl.replaceAll("/+$", "");
        return base + "/login?token=" + token;
    }

    // ------------------------------------------------------------------ http plumbing

    private void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        var h = ex.getResponseHeaders();
        h.set("Content-Type", type + "; charset=utf-8");
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static Map<String, String> params(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            try {
                String k = URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), StandardCharsets.UTF_8);
                String v = i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
                m.put(k, v);
            } catch (IllegalArgumentException ignored) {
                // malformed escape: skip the pair
            }
        }
        return m;
    }

    private boolean authed(HttpExchange ex) {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return false;
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.startsWith("vyxsession=")) {
                Long exp = sessions.get(p.substring(11));
                return exp != null && exp > System.currentTimeMillis();
            }
        }
        return false;
    }

    private boolean rateLimited(String ip) {
        long now = System.currentTimeMillis();
        long[] a = attempts.computeIfAbsent(ip, k -> new long[]{0, now});
        synchronized (a) {
            if (now - a[1] > 300_000L) {
                a[0] = 0;
                a[1] = now;
            }
            return ++a[0] > 10;
        }
    }

    private void login(HttpExchange ex) throws IOException {
        String ip = ex.getRemoteAddress().getAddress().getHostAddress();
        if (rateLimited(ip)) {
            send(ex, 429, "text/plain", "Too many attempts. Wait a few minutes.");
            return;
        }
        String token = params(ex.getRequestURI().getRawQuery()).getOrDefault("token", "");
        Long exp = tokens.remove(token); // one time only
        if (exp == null || exp < System.currentTimeMillis()) {
            send(ex, 403, "text/plain", "Invalid or expired token. Run /cheditor in game for a new one.");
            return;
        }
        String sid = random(40);
        long ttl = checker.cfg().sessionMinutes * 60_000L;
        sessions.put(sid, System.currentTimeMillis() + ttl);
        sessions.values().removeIf(v -> v < System.currentTimeMillis());
        ex.getResponseHeaders().set("Set-Cookie", "vyxsession=" + sid + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + (ttl / 1000));
        ex.getResponseHeaders().set("Location", "/");
        send(ex, 302, "text/plain", "ok");
    }

    private <T> T sync(java.util.concurrent.Callable<T> c) throws Exception {
        Future<T> f = Bukkit.getScheduler().callSyncMethod(pl, c);
        return f.get(4, TimeUnit.SECONDS);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/login")) {
                login(ex);
                return;
            }
            if (path.equals("/")) {
                send(ex, 200, "text/html", authed(ex) ? APP : LOGIN);
                return;
            }
            if (!authed(ex)) {
                send(ex, 401, "application/json", "{\"error\":\"login required\"}");
                return;
            }
            boolean post = ex.getRequestMethod().equals("POST");
            if (post && !"1".equals(ex.getRequestHeaders().getFirst("X-VyxAC"))) {
                send(ex, 403, "application/json", "{\"error\":\"missing header\"}");
                return;
            }
            Map<String, String> q = params(ex.getRequestURI().getRawQuery());
            switch (path) {
                case "/api/scans" -> send(ex, 200, "application/json", scans(checker.store().recent(num(q.get("limit"), 100))));
                case "/api/player" -> send(ex, 200, "application/json", scans(checker.store().player(q.getOrDefault("name", ""), num(q.get("limit"), 100))));
                case "/api/mods" -> send(ex, 200, "application/json", mods());
                case "/api/online" -> {
                    List<String> names = sync(() -> {
                        List<String> l = new ArrayList<>();
                        for (Player p : Bukkit.getOnlinePlayers()) l.add(p.getName());
                        return l;
                    });
                    StringBuilder sb = new StringBuilder("{\"players\":[");
                    for (int i = 0; i < names.size(); i++) sb.append(i > 0 ? "," : "").append('"').append(Discord.json(names.get(i))).append('"');
                    send(ex, 200, "application/json", sb.append("]}").toString());
                }
                case "/api/check" -> {
                    if (!post) {
                        send(ex, 405, "application/json", "{\"error\":\"POST only\"}");
                        return;
                    }
                    String body = new String(ex.getRequestBody().readNBytes(4096), StandardCharsets.UTF_8);
                    Map<String, String> f = params(body);
                    final String player = f.getOrDefault("player", "").trim();
                    final List<String> mods = new ArrayList<>();
                    for (String m : f.getOrDefault("mods", "").split(",")) if (!m.isBlank()) mods.add(m.trim());
                    String err = sync(() -> checker.webCheck(player, mods));
                    send(ex, 200, "application/json", err == null ? "{\"started\":true}" : "{\"started\":false,\"error\":\"" + Discord.json(err) + "\"}");
                }
                case "/api/logout" -> {
                    sessions.clear();
                    send(ex, 200, "application/json", "{\"ok\":true}");
                }
                default -> send(ex, 404, "application/json", "{\"error\":\"not found\"}");
            }
        } catch (Throwable t) {
            try {
                send(ex, 500, "application/json", "{\"error\":\"server error\"}");
            } catch (Throwable ignored) {
                // connection already closed
            }
        }
    }

    private static int num(String s, int def) {
        try {
            return s == null ? def : Math.max(1, Math.min(500, Integer.parseInt(s)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private String scans(List<ScanResult> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            ScanResult r = list.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(r.id).append(",\"time\":").append(r.ts)
                    .append(",\"player\":\"").append(Discord.json(r.name)).append("\",\"reason\":\"").append(Discord.json(r.reason))
                    .append("\",\"starter\":\"").append(Discord.json(r.starter)).append("\",\"doublecheck\":").append(r.doubleChecked).append(",\"results\":[");
            boolean first = true;
            for (ScanResult.Entry e : r.results.values()) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"mod\":\"").append(Discord.json(e.modName)).append("\",\"result\":\"").append(e.result.name()).append("\"}");
            }
            sb.append("]}");
        }
        return sb.append(']').toString();
    }

    private String mods() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (ModDef m : checker.cfg().mods.values()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":\"").append(Discord.json(m.id)).append("\",\"name\":\"").append(Discord.json(m.name)).append("\",\"category\":\"")
                    .append(Discord.json(m.category)).append("\",\"enabled\":").append(m.enabled && !m.probes.isEmpty()).append(",\"verified\":").append(m.verified).append('}');
        }
        return sb.append(']').toString();
    }

    private static final String LOGIN = "<!doctype html><html><head><meta charset=\"utf-8\"><title>VyxAC CheckHacks</title>"
            + "<style>body{font-family:system-ui,sans-serif;background:#14161a;color:#e6e6e6;display:grid;place-items:center;height:100vh;margin:0}"
            + "div{max-width:420px;padding:24px;background:#1e2127;border-radius:10px}code{background:#2b2f37;padding:2px 6px;border-radius:4px}</style></head>"
            + "<body><div><h2>CheckHacks web editor</h2><p>There are no passwords. In game, run <code>/cheditor</code> and open the one-time link it gives you.</p></div></body></html>";

    private static final String APP = """
            <!doctype html><html><head><meta charset="utf-8"><title>VyxAC CheckHacks</title>
            <style>
            body{font-family:system-ui,sans-serif;background:#14161a;color:#e6e6e6;margin:0;padding:20px;max-width:1100px;margin:auto}
            h1{margin:0 0 14px}section{background:#1e2127;border-radius:10px;padding:14px 16px;margin-bottom:14px}
            input,select,button{background:#2b2f37;color:#e6e6e6;border:1px solid #3a3f49;border-radius:6px;padding:7px 10px;margin-right:6px}
            button{cursor:pointer;background:#2f6feb;border-color:#2f6feb}button:hover{background:#4a83f0}
            table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:6px 8px;border-bottom:1px solid #2b2f37;vertical-align:top}
            .DETECTED{color:#ff6b6b;font-weight:700}.PROTECTED{color:#ffd166}.NOT_DETECTED{color:#6fcf97}.SKIPPED{color:#9aa0aa}
            .tag{display:inline-block;margin:1px 8px 1px 0}.muted{color:#9aa0aa}
            </style></head><body>
            <h1>CheckHacks</h1>
            <section><b>Start a check</b><br><br>
              <select id="online"></select>
              <input id="mods" size="32" placeholder="mod ids / categories (empty = default list)">
              <button id="go">Check</button><span id="msg" class="muted"></span></section>
            <section><b>Look up a player</b><br><br>
              <input id="q" placeholder="player name"><button id="find">Search</button><button id="latest">Latest scans</button></section>
            <section><b id="title">Latest scans</b><br><br>
              <table><thead><tr><th>Time</th><th>Player</th><th>Reason</th><th>Started by</th><th>Results</th></tr></thead><tbody id="rows"></tbody></table></section>
            <script>
            const $ = id => document.getElementById(id);
            async function api(path, opt){ const r = await fetch(path, opt); return r.json(); }
            function cell(tr, text){ const td = document.createElement('td'); td.textContent = text; tr.appendChild(td); return td; }
            function render(list){
              const body = $('rows'); body.textContent = '';
              for (const s of list){
                const tr = document.createElement('tr');
                cell(tr, new Date(s.time).toLocaleString());
                cell(tr, s.player);
                cell(tr, s.reason + (s.doublecheck ? ' (doublecheck)' : ''));
                cell(tr, s.starter);
                const td = document.createElement('td');
                for (const r of s.results){
                  const span = document.createElement('span');
                  span.className = 'tag ' + r.result;
                  span.textContent = r.mod + ': ' + r.result;
                  td.appendChild(span);
                }
                tr.appendChild(td); body.appendChild(tr);
              }
            }
            async function latest(){ $('title').textContent = 'Latest scans'; render(await api('/api/scans?limit=100')); }
            async function online(){
              const d = await api('/api/online'); const sel = $('online'); sel.textContent = '';
              for (const n of d.players){ const o = document.createElement('option'); o.textContent = n; sel.appendChild(o); }
            }
            $('go').onclick = async () => {
              const body = new URLSearchParams({player: $('online').value, mods: $('mods').value});
              const d = await api('/api/check', {method:'POST', headers:{'X-VyxAC':'1','Content-Type':'application/x-www-form-urlencoded'}, body});
              $('msg').textContent = d.started ? ' started, results appear below in a few seconds' : ' ' + d.error;
              setTimeout(latest, 4500);
            };
            $('find').onclick = async () => {
              const n = $('q').value.trim(); if (!n) return;
              $('title').textContent = 'Scans of ' + n; render(await api('/api/player?name=' + encodeURIComponent(n)));
            };
            $('latest').onclick = latest;
            online(); latest(); setInterval(online, 15000);
            </script></body></html>
            """;
}
