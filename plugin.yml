package com.vyxac.hacks;

import com.vyxac.VyxAC;
import org.bukkit.Bukkit;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Discord webhooks. Components V2 (Container + Text Display + Separator, flags 32768) or classic embeds. Always asynchronous. */
public final class Discord {
    private Discord() {}

    static String json(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> { }
                case '\t' -> sb.append(' ');
                default -> {
                    if (c >= 0x20) sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    static boolean validUrl(String url) {
        return url != null && (url.startsWith("https://discord.com/api/webhooks/") || url.startsWith("https://discordapp.com/api/webhooks/")
                || url.startsWith("https://canary.discord.com/api/webhooks/") || url.startsWith("https://ptb.discord.com/api/webhooks/"));
    }

    public static void send(VyxAC pl, HackConfig c, String url, String text) {
        if (!validUrl(url)) return;
        final int color = HackText.hex(c.accent);
        final String footer = "VyxAC | " + Instant.now();
        final String body;
        final String target;
        if (c.v2) {
            body = "{\"flags\":32768,\"components\":[{\"type\":17,\"accent_color\":" + color + ",\"components\":["
                    + "{\"type\":10,\"content\":\"" + json(text) + "\"},"
                    + "{\"type\":14,\"divider\":true,\"spacing\":1},"
                    + "{\"type\":10,\"content\":\"-# " + json(footer) + "\"}]}]}";
            target = url + (url.contains("?") ? "&" : "?") + "with_components=true";
        } else {
            body = "{\"embeds\":[{\"description\":\"" + json(text) + "\",\"color\":" + color + ",\"footer\":{\"text\":\"VyxAC\"},\"timestamp\":\"" + Instant.now() + "\"}]}";
            target = url;
        }
        Bukkit.getScheduler().runTaskAsynchronously(pl, () -> {
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(target).openConnection();
                con.setRequestMethod("POST");
                con.setConnectTimeout(5000);
                con.setReadTimeout(5000);
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "application/json");
                con.setRequestProperty("User-Agent", "VyxAC");
                try (OutputStream os = con.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
                int code = con.getResponseCode();
                if (code >= 400) { // read Discord's real error instead of guessing
                    String err = "";
                    try (InputStream es = con.getErrorStream()) {
                        if (es != null) err = new String(es.readNBytes(400), StandardCharsets.UTF_8);
                    }
                    pl.getLogger().warning("Discord webhook answered HTTP " + code + ": " + err);
                }
                con.disconnect();
            } catch (Throwable t) {
                pl.getLogger().warning("Discord webhook failed: " + t.getMessage());
            }
        });
    }
}
