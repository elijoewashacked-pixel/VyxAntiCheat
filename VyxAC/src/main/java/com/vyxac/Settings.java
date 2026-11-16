package com.vyxac;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/** Frequently used settings, cached so event handlers never touch the YAML tree. */
public final class Settings {
    public String prefix = "";
    public boolean enabled, mitigation, lagProtection, bedrockExempt, logToFile, requireSwing, aiEnabled;
    public boolean alertsDefaultOn, alertConsole, alertHover;
    public String bedrockPrefix, alertFormat, alertSound, webhook;
    public double minTps, decayPerMinute;
    public int maxPing, maxRewindMs, rewindExtraMs;
    public long joinGraceMs, tpGraceMs, velGraceMs, alertCooldownMs, cancelHitMs;

    public String punishMode;
    public int minDistinct, minFlags, punishWindowSec, punishMaxPing, autoDelayTicks;
    public long punishGraceMs, punishCooldownMs, reviewCooldownMs;
    public double punishAiConf;
    public List<String> ladder = new ArrayList<>();

    public void load(FileConfiguration c) {
        prefix = color(c.getString("general.prefix", "&8[&bVyxAC&8] &7"));
        enabled = c.getBoolean("general.enabled", true);
        bedrockExempt = c.getBoolean("general.exempt-bedrock", true);
        bedrockPrefix = c.getString("general.bedrock-prefix", ".");
        joinGraceMs = c.getLong("general.join-grace-seconds", 8) * 1000L;
        tpGraceMs = c.getLong("general.teleport-grace-ms", 1500);
        velGraceMs = c.getLong("general.velocity-grace-ms", 2500);
        requireSwing = c.getBoolean("general.require-swing", true);
        cancelHitMs = c.getLong("general.cancel-hit-ms", 1500);
        maxRewindMs = c.getInt("general.max-rewind-ms", 800);
        rewindExtraMs = c.getInt("general.rewind-extra-ms", 150);

        minTps = c.getDouble("lag.min-tps", 17.0);
        maxPing = c.getInt("lag.max-ping", 600);
        decayPerMinute = c.getDouble("violations.decay-per-minute", 1.0);

        alertsDefaultOn = c.getBoolean("alerts.default-on", true);
        alertConsole = c.getBoolean("alerts.console", true);
        alertHover = c.getBoolean("alerts.hover", true);
        alertCooldownMs = c.getLong("alerts.cooldown-ms", 1500);
        alertSound = c.getString("alerts.sound", "");
        webhook = c.getString("alerts.discord-webhook", "");
        alertFormat = c.getString("alerts.format", "%player% failed %check% (%info%) [%vl%/%punish_vl%]");

        punishMode = c.getString("punishments.mode", "REVIEW").toUpperCase();
        minDistinct = c.getInt("punishments.min-distinct-checks", 2);
        minFlags = c.getInt("punishments.min-flags", 6);
        punishWindowSec = c.getInt("punishments.window-seconds", 180);
        punishAiConf = c.getDouble("punishments.ai-corroboration-confidence", 0.90);
        punishGraceMs = c.getLong("punishments.grace-seconds", 30) * 1000L;
        punishCooldownMs = c.getLong("punishments.cooldown-seconds", 120) * 1000L;
        reviewCooldownMs = c.getLong("punishments.review-cooldown-seconds", 300) * 1000L;
        punishMaxPing = c.getInt("punishments.max-ping", 400);
        autoDelayTicks = c.getInt("punishments.auto-delay-ticks", 60);
        ladder = new ArrayList<>(c.getStringList("punishments.ladder"));

        mitigation = c.getBoolean("features.mitigation", true);
        lagProtection = c.getBoolean("features.lag-protection", true);
        logToFile = c.getBoolean("features.log-to-file", true);
        aiEnabled = c.getBoolean("features.ai", true);
    }

    public static String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }
}
