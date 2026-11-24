package com.vyxac.hacks;

import com.vyxac.VyxAC;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable snapshot of checkhacks.yml and checklang.yml. A reload builds a NEW snapshot, so a check that is already
 * running keeps the settings it started with.
 */
public final class HackConfig {
    public static final class Rule {
        public final String mod, result;
        public final List<String> commands;

        Rule(String mod, String result, List<String> commands) {
            this.mod = mod;
            this.result = result;
            this.commands = commands;
        }
    }

    public static final class Hook {
        public final String key, plugin, event, getter;
        public final boolean enabled;

        Hook(String key, boolean enabled, String plugin, String event, String getter) {
            this.key = key;
            this.enabled = enabled;
            this.plugin = plugin;
            this.event = event;
            this.getter = getter;
        }
    }

    public final boolean enabled, doublecheck, showClean, autoJoin, autoJoinFirstOnly, langJoin, langFirstOnly, v2, vyxacHook,
            webEnabled, updaterEnabled;
    public final int timeoutTicks, betweenTicks, cooldownSec, maxConcurrent, vyxacMinVl, joinDelaySec, webPort, tokenMinutes, sessionMinutes;
    public final String lang, accent, hackWebhook, langWebhook, webBind, webPublicUrl, updaterRepo, hackTemplate, langTemplate, signPosition;
    public final List<String> bedrockPrefixes, listNormal, listJoin, listAnticheat, onDetected, onProtected, onClean, langHighlight;
    public final List<Rule> rules = new ArrayList<>();
    public final List<Hook> hooks = new ArrayList<>();
    public final LinkedHashMap<String, ModDef> mods = new LinkedHashMap<>();
    public final Map<String, String> langNames = new LinkedHashMap<>();
    public final boolean discordOnlyFlagged;

    private HackConfig(YamlConfiguration y, YamlConfiguration l) {
        enabled = y.getBoolean("enabled", true);
        lang = y.getString("language", "en");
        timeoutTicks = Math.max(10, y.getInt("settings.timeout-ticks", 40));
        betweenTicks = Math.max(1, y.getInt("settings.between-sign-ticks", 1));
        doublecheck = y.getBoolean("settings.doublecheck", true);
        cooldownSec = Math.max(0, y.getInt("settings.cooldown-seconds", 60));
        maxConcurrent = Math.max(1, y.getInt("settings.max-concurrent", 3));
        showClean = y.getBoolean("settings.show-clean-lines", true);
        signPosition = y.getString("settings.sign-position", "auto").toLowerCase(Locale.ROOT);
        bedrockPrefixes = y.getStringList("settings.bedrock-prefixes");

        autoJoin = y.getBoolean("auto-check.on-join", false);
        joinDelaySec = Math.max(1, y.getInt("auto-check.join-delay-seconds", 5));
        autoJoinFirstOnly = y.getBoolean("auto-check.first-join-only", false);
        langJoin = y.getBoolean("auto-check.language-on-join", true);
        langFirstOnly = y.getBoolean("auto-check.language-first-join-only", true);

        listNormal = y.getStringList("lists.normal");
        listJoin = y.getStringList("lists.join");
        listAnticheat = y.getStringList("lists.anticheat");

        vyxacHook = y.getBoolean("detect-flag.vyxac.enabled", true);
        vyxacMinVl = Math.max(1, y.getInt("detect-flag.vyxac.min-vl", 6));
        ConfigurationSection ac = y.getConfigurationSection("detect-flag.anticheats");
        if (ac != null) {
            for (String k : ac.getKeys(false)) {
                hooks.add(new Hook(k, ac.getBoolean(k + ".enabled", false), ac.getString(k + ".plugin", k),
                        ac.getString(k + ".event", ""), ac.getString(k + ".getter", "getPlayer")));
            }
        }

        onDetected = y.getStringList("commands.on-detected");
        onProtected = y.getStringList("commands.on-protected");
        onClean = y.getStringList("commands.on-clean");
        for (Map<?, ?> m : y.getMapList("commands.command-rules")) {
            Object mod = m.get("mod"), res = m.get("result"), cmds = m.get("commands");
            List<String> list = new ArrayList<>();
            if (cmds instanceof List<?> cl) for (Object o : cl) list.add(String.valueOf(o));
            else if (cmds != null) list.add(String.valueOf(cmds));
            if (mod != null && !list.isEmpty()) rules.add(new Rule(String.valueOf(mod).toLowerCase(Locale.ROOT), res == null ? "ANY" : String.valueOf(res).toUpperCase(Locale.ROOT), list));
        }

        v2 = y.getBoolean("discord.use-components-v2", true);
        accent = y.getString("discord.accent-color", "#ff5555");
        hackWebhook = y.getString("discord.hack-webhook", "");
        langWebhook = y.getString("discord.lang-webhook", "");
        hackTemplate = y.getString("discord.hack-template", "**&name&** was checked by &checker& (&reason&)\n&results&");
        langTemplate = y.getString("discord.lang-template", "**&name&** uses the client language &lang&");
        discordOnlyFlagged = y.getBoolean("discord.only-flagged", true);

        webEnabled = y.getBoolean("web.enabled", false);
        webBind = y.getString("web.bind", "127.0.0.1");
        webPort = y.getInt("web.port", 8123);
        webPublicUrl = y.getString("web.public-url", "");
        tokenMinutes = Math.max(1, y.getInt("web.token-minutes", 10));
        sessionMinutes = Math.max(5, y.getInt("web.session-minutes", 60));
        updaterEnabled = y.getBoolean("updater.enabled", false);
        updaterRepo = y.getString("updater.repo", "");

        ConfigurationSection ms = y.getConfigurationSection("mods");
        if (ms != null) {
            for (String id : ms.getKeys(false)) {
                ConfigurationSection s = ms.getConfigurationSection(id);
                if (s == null) continue;
                List<ModDef.Probe> probes = new ArrayList<>();
                for (Map<?, ?> m : s.getMapList("probes")) {
                    Object key = m.get("key");
                    if (key == null || String.valueOf(key).isBlank()) continue;
                    String mode = String.valueOf(m.get("mode")).toUpperCase(Locale.ROOT);
                    if (!mode.equals("TRANSLATE") && !mode.equals("METEOR")) mode = "KEYBIND";
                    Object k2 = m.get("key2");
                    probes.add(new ModDef.Probe(mode, String.valueOf(key).trim(), k2 == null ? "" : String.valueOf(k2).trim()));
                }
                String idl = id.toLowerCase(Locale.ROOT);
                mods.put(idl, new ModDef(idl, s.getString("name", id), s.getString("category", "other").toLowerCase(Locale.ROOT),
                        s.getBoolean("enabled", true), s.getBoolean("verified", false), probes));
            }
        }

        langHighlight = l.getStringList("highlight");
        ConfigurationSection ln = l.getConfigurationSection("languages");
        if (ln != null) for (String k : ln.getKeys(false)) langNames.put(k.toLowerCase(Locale.ROOT), ln.getString(k, k));
    }

    public static HackConfig load(VyxAC pl) {
        File f = new File(pl.getDataFolder(), "checkhacks.yml");
        if (!f.exists()) pl.saveResource("checkhacks.yml", false);
        File lf = new File(pl.getDataFolder(), "checklang.yml");
        if (!lf.exists()) pl.saveResource("checklang.yml", false);
        return new HackConfig(YamlConfiguration.loadConfiguration(f), YamlConfiguration.loadConfiguration(lf));
    }
}
