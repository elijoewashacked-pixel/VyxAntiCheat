package com.vyxac.hacks;

import com.vyxac.VyxAC;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Messages in messages/&lt;lang&gt;.yml. Supports MiniMessage colour tags (a practical subset: named colours, &lt;#rrggbb&gt;,
 * bold / italic / underlined / strikethrough / obfuscated / reset), legacy &amp; codes and PlaceholderAPI.
 */
public final class HackText {
    public static final String[] LANGUAGES = {"en", "es", "de", "fr", "it", "pt", "ru", "nl", "tr"};
    private static final Map<String, ChatColor> COLORS = new HashMap<>();
    private static final Map<String, ChatColor> FORMATS = new HashMap<>();

    static {
        String[][] c = {{"black", "BLACK"}, {"dark_blue", "DARK_BLUE"}, {"dark_green", "DARK_GREEN"}, {"dark_aqua", "DARK_AQUA"},
                {"dark_red", "DARK_RED"}, {"dark_purple", "DARK_PURPLE"}, {"gold", "GOLD"}, {"gray", "GRAY"}, {"grey", "GRAY"},
                {"dark_gray", "DARK_GRAY"}, {"dark_grey", "DARK_GRAY"}, {"blue", "BLUE"}, {"green", "GREEN"}, {"aqua", "AQUA"},
                {"red", "RED"}, {"light_purple", "LIGHT_PURPLE"}, {"yellow", "YELLOW"}, {"white", "WHITE"}};
        for (String[] e : c) COLORS.put(e[0], ChatColor.valueOf(e[1]));
        String[][] f = {{"bold", "BOLD"}, {"b", "BOLD"}, {"italic", "ITALIC"}, {"i", "ITALIC"}, {"em", "ITALIC"},
                {"underlined", "UNDERLINE"}, {"u", "UNDERLINE"}, {"strikethrough", "STRIKETHROUGH"}, {"st", "STRIKETHROUGH"},
                {"obfuscated", "MAGIC"}, {"obf", "MAGIC"}};
        for (String[] e : f) FORMATS.put(e[0], ChatColor.valueOf(e[1]));
    }

    private final VyxAC pl;
    private final Map<String, String> messages = new HashMap<>();
    private final Map<String, String> fallback = new HashMap<>();

    public HackText(VyxAC pl) {
        this.pl = pl;
    }

    public void load(String lang) {
        messages.clear();
        fallback.clear();
        for (String code : LANGUAGES) {
            File f = new File(pl.getDataFolder(), "messages/" + code + ".yml");
            if (!f.exists() && pl.getResource("messages/" + code + ".yml") != null) pl.saveResource("messages/" + code + ".yml", false);
        }
        read(fallback, new InputStreamReaderHolder(pl).en());
        File f = new File(pl.getDataFolder(), "messages/" + lang.toLowerCase(Locale.ROOT) + ".yml");
        if (f.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            for (String k : y.getKeys(false)) messages.put(k, y.getString(k, ""));
        }
    }

    private static void read(Map<String, String> into, YamlConfiguration y) {
        if (y == null) return;
        for (String k : y.getKeys(false)) into.put(k, y.getString(k, ""));
    }

    /** helper that reads the bundled English file as the fallback for missing keys */
    private static final class InputStreamReaderHolder {
        private final VyxAC pl;

        InputStreamReaderHolder(VyxAC pl) {
            this.pl = pl;
        }

        YamlConfiguration en() {
            var in = pl.getResource("messages/en.yml");
            return in == null ? null : YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    public String raw(String key) {
        String v = messages.get(key);
        if (v == null || v.isEmpty()) v = fallback.get(key);
        return v == null ? key : v;
    }

    /** coloured message; kv = placeholder, value, placeholder, value ... (placeholders are written %name% in the files) */
    public String msg(String key, String... kv) {
        String s = raw(key);
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("%" + kv[i] + "%", kv[i + 1] == null ? "" : kv[i + 1]);
        return color(s);
    }

    public String prefixed(String key, String... kv) {
        return color(raw("prefix")) + msg(key, kv);
    }

    // ------------------------------------------------------------------ colours

    public static String color(String s) {
        if (s == null) return "";
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', mini(s));
    }

    public static String strip(String s) {
        return org.bukkit.ChatColor.stripColor(s);
    }

    /** MiniMessage subset to legacy section codes */
    static String mini(String s) {
        StringBuilder out = new StringBuilder();
        List<String[]> stack = new ArrayList<>(); // {tag name, code}
        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch != '<') {
                out.append(ch);
                i++;
                continue;
            }
            int end = s.indexOf('>', i);
            if (end < 0 || end - i > 40) {
                out.append(ch);
                i++;
                continue;
            }
            String tag = s.substring(i + 1, end).toLowerCase(Locale.ROOT);
            boolean closing = tag.startsWith("/");
            String name = closing ? tag.substring(1) : tag;
            if (name.startsWith("color:")) name = name.substring(6);
            String code = null;
            if (COLORS.containsKey(name)) code = COLORS.get(name).toString();
            else if (FORMATS.containsKey(name)) code = FORMATS.get(name).toString();
            else if (name.matches("#[0-9a-f]{6}")) code = ChatColor.of(name).toString();
            else if (name.equals("reset") || name.equals("r")) {
                if (!closing) {
                    stack.clear();
                    out.append(ChatColor.RESET);
                    i = end + 1;
                    continue;
                }
            }
            if (code == null) { // not a known tag: keep it as plain text
                out.append(ch);
                i++;
                continue;
            }
            if (!closing) {
                stack.add(new String[]{name, code});
                out.append(code);
            } else {
                for (int k = stack.size() - 1; k >= 0; k--) {
                    if (stack.get(k)[0].equals(name)) {
                        stack.remove(k);
                        break;
                    }
                }
                out.append(ChatColor.RESET);
                for (String[] st : stack) out.append(st[1]);
            }
            i = end + 1;
        }
        return out.toString();
    }

    public static String papi(Player p, String s) {
        try {
            if (p == null || Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return s;
            Class<?> c = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            return (String) c.getMethod("setPlaceholders", Player.class, String.class).invoke(null, p, s);
        } catch (Throwable t) {
            return s;
        }
    }

    public static int hex(String color) {
        try {
            return Integer.parseInt(color.replace("#", ""), 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return 0xFF5555;
        }
    }
}
