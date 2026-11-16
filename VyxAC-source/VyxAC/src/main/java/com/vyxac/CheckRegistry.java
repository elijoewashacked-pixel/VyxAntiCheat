package com.vyxac;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * All checks and their default settings. Every value can be changed in config.yml under
 * checks.&lt;Name&gt;.* or with /vyxac configure and /vyxac customize.
 */
public final class CheckRegistry {
    private final Map<String, CheckInfo> map = new LinkedHashMap<>();

    public CheckRegistry() {
        defineAll();
    }

    private void def(String name, String cat, String desc, boolean punishable,
                     double vlPerFlag, double alertVl, double punishVl, double mitigateVl, Object... kv) {
        CheckInfo c = new CheckInfo(name, cat, desc);
        c.punishable = punishable;
        c.vlPerFlag = vlPerFlag;
        c.alertVl = alertVl;
        c.punishVl = punishVl;
        c.mitigateVl = mitigateVl;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            c.defaults.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        }
        map.put(name, c);
    }

    private void defineAll() {
        final double NO = 9999; // "never mitigate"

        // ------------------------------------------------------------ combat
        def("KillAura", "combat", "Hits that no human could land (angle, instant target switching)", true, 1.0, 1, 8, 3,
                "max-angle", 95, "switch-angle", 60, "switch-ms", 250, "min-distance", 1.0, "buffer", 4);
        def("AimAssist", "combat", "Unnaturally precise aim lock on moving targets", true, 1.0, 2, 10, NO,
                "lock-error", 1.2, "window", 12, "min-lock", 10, "min-distance", 1.5);
        def("AutoClicker", "combat", "Inhuman click speed or click rhythm", true, 1.0, 2, 10, 5,
                "max-cps", 20, "mode-ratio", 0.9, "min-cps", 7);
        def("TriggerBot", "combat", "Attacks with a machine-constant delay after the crosshair moves", true, 1.0, 2, 10, NO,
                "max-reaction-ms", 70, "max-std-ms", 12, "samples", 10);
        def("Reach", "combat", "Hits from further away than the game allows", true, 1.0, 1, 6, 2,
                "tolerance", 0.45);
        def("Hitbox", "combat", "Hits while the crosshair is not on the target", true, 1.0, 1, 8, 3,
                "expand", 0.3);
        def("Criticals", "combat", "Critical hits without really falling", true, 1.0, 2, 8, 4,
                "max-fall", 0.5, "buffer", 4);
        def("Velocity", "combat", "Takes much less knockback than expected", true, 1.0, 2, 8, NO,
                "min-ratio", 0.3, "buffer", 3, "min-kb", 0.25);
        def("AntiKnockback", "combat", "Takes no knockback at all", true, 1.0, 2, 8, NO,
                "min-ratio", 0.1, "buffer", 3);
        def("FastBow", "combat", "Fires a bow faster than the charge time allows", true, 1.0, 1, 5, 1,
                "tolerance-ms", 150, "force-slack", 0.06);
        def("AutoPot", "combat", "Throws potions within a tick of switching slots", true, 1.0, 2, 8, NO,
                "min-delay-ms", 70, "buffer", 3);
        def("AutoTotem", "combat", "Re-equips a totem faster than a human can react", true, 1.0, 2, 8, NO,
                "max-reaction-ms", 90, "buffer", 3);
        def("MultiAura", "combat", "Attacks several targets in the same tick", true, 1.0, 1, 6, 2,
                "same-tick-ms", 35);
        def("AutoCrystal", "combat", "Places and detonates end crystals with machine timing", true, 1.0, 2, 8, NO,
                "max-reaction-ms", 60, "buffer", 4);
        def("Backtrack", "combat", "Hits targets at positions older than lag compensation allows", true, 0.7, 1, 8, 3,
                "extra-ms", 400);

        // ---------------------------------------------------------- movement
        def("Speed", "movement", "Moves faster than vanilla physics allow", true, 1.0, 1, 8, 3,
                "tolerance", 1.0, "leniency", 1.12, "ice-multiplier", 2.2);
        def("Fly", "movement", "Hovers or rises without support", true, 1.0, 1, 8, 3,
                "tolerance", 0.55, "hover-limit", 8);
        def("LongJump", "movement", "Jumps further than the airtime allows", true, 1.0, 1, 6, 2,
                "leniency", 1.35, "slack", 0.6);
        def("BunnyHop", "movement", "Chains hops faster than vanilla momentum allows", true, 1.0, 1, 6, 3,
                "leniency", 1.28, "runs", 4);
        def("NoFall", "movement", "Lands from a height without taking fall damage", true, 1.0, 1, 5, 2,
                "min-drop", 4.0, "wait-ticks", 4);
        def("Jesus", "movement", "Walks on liquids", true, 1.0, 1, 6, 3,
                "buffer", 8);
        def("Spider", "movement", "Climbs walls without a ladder", true, 1.0, 1, 6, 3,
                "tolerance", 0.55);
        def("Step", "movement", "Steps up blocks higher than allowed", true, 1.0, 1, 6, 2,
                "tolerance", 0.12);
        def("NoSlow", "movement", "Moves at normal speed while using an item", true, 1.0, 1, 6, 3,
                "max-speed", 0.12, "buffer", 6, "min-use-ms", 400);
        def("Timer", "movement", "Runs the game clock faster than normal", true, 1.0, 1, 6, 4,
                "tolerance-ms", 450, "bank-ms", 4000);
        def("Blink", "movement", "Holds movement back and releases it in a burst (alert only: lagging clients look similar)", false, 1.0, 1, 6, NO,
                "min-gap-ms", 600, "burst", 8, "stable-ping-delta", 80, "buffer", 3);
        def("Phase", "movement", "Moves through solid blocks", true, 1.0, 1, 5, 1,
                "buffer", 2);
        def("FastLadder", "movement", "Climbs ladders and vines too fast", true, 1.0, 1, 6, 3,
                "max-up", 0.27, "buffer", 4);
        def("ElytraFly", "movement", "Impossible elytra speed or hovering", true, 1.0, 1, 6, 3,
                "max-speed", 3.2, "hover-limit", 8);

        // ------------------------------------------------- player / inventory
        def("AutoArmor", "player", "Equips armor in consecutive ticks", true, 1.0, 2, 8, NO,
                "max-interval-ms", 55, "buffer", 3);
        def("ChestStealer", "player", "Empties containers faster than a human can click", true, 1.0, 2, 8, NO,
                "max-interval-ms", 55, "buffer", 8);
        def("InventoryMove", "player", "Keeps moving while a container is open", true, 1.0, 1, 6, 3,
                "min-open-ms", 600, "min-speed", 0.15, "buffer", 6);
        def("Freecam", "player", "Interacts with a block face the player cannot see", true, 1.0, 1, 6, 1,
                "tolerance", 0.5);
        def("XRay", "player", "Mines hidden ores far more often than chance allows (needs staff review)", false, 1.0, 1, 5, NO,
                "min-hidden", 5, "min-ratio", 0.08, "window-sec", 600);
        def("Scaffold", "player", "Places blocks without looking at them", true, 1.0, 1, 6, 2,
                "expand", 0.25, "buffer", 4);
        def("FastPlace", "player", "Places blocks faster than allowed", true, 1.0, 1, 6, 2,
                "max-per-sec", 14);
        def("Nuker", "player", "Breaks many blocks at once", true, 1.0, 1, 6, 2,
                "max-per-sec", 16, "same-tick", 4);
        def("FastBreak", "player", "Breaks blocks faster than tool speed allows", true, 1.0, 1, 6, 2,
                "tolerance", 0.6);
        def("Baritone", "player", "Bot-like movement: sprinting sideways relative to the camera", false, 1.0, 1, 6, NO,
                "min-offset-deg", 75, "buffer", 12);
        def("AutoFish", "player", "Reels in a fish with machine timing", false, 1.0, 1, 6, NO,
                "max-reaction-ms", 110, "buffer", 4);

        // --------------------------------------------------- packet / misc
        def("PingSpoof", "packet", "Reported latency swings that real networks do not produce (approximate)", false, 1.0, 1, 6, NO,
                "delta", 300, "buffer", 6);
        def("BadPackets", "packet", "Invalid movement or rotation values", true, 1.0, 1, 4, 1);
        def("PacketFlood", "packet", "Sends far more packets than a client does", true, 1.0, 1, 5, NO,
                "max-moves", 60, "max-clicks", 60, "kick-at", 0);
        def("GroundSpoof", "packet", "Claims to be on the ground while in the air", true, 1.0, 1, 6, 3,
                "buffer", 5);
        def("RotationSpoof", "packet", "Impossible or machine-constant rotations", true, 1.0, 1, 6, 2,
                "const-steps", 8);
        def("FastEat", "packet", "Finishes eating or drinking too quickly", true, 1.0, 1, 5, 1,
                "tolerance", 0.7);
        def("AutoEat", "packet", "Switches slot and eats with machine timing", false, 1.0, 2, 8, NO,
                "min-delay-ms", 70, "buffer", 4);

        // ------------------------------------------------------------ mini AI
        def("AI", "ai", "Statistical anomaly model (only ever corroborates, never punishes alone)", false, 1.0, 1, 9999, NO);
    }

    public CheckInfo get(String name) {
        CheckInfo c = map.get(name);
        if (c == null) throw new IllegalStateException("Unknown check: " + name);
        return c;
    }

    public CheckInfo find(String name) {
        for (CheckInfo c : map.values()) if (c.name.equalsIgnoreCase(name)) return c;
        return null;
    }

    public Collection<CheckInfo> all() {
        return map.values();
    }

    /** Adds every check's defaults so a fresh config.yml gets complete sections. */
    public void registerDefaults(FileConfiguration c) {
        for (CheckInfo ci : map.values()) {
            String b = "checks." + ci.name + ".";
            c.addDefault(b + "enabled", true);
            c.addDefault(b + "punishable", ci.punishable);
            c.addDefault(b + "vl-per-flag", ci.vlPerFlag);
            c.addDefault(b + "alert-vl", ci.alertVl);
            c.addDefault(b + "punish-vl", ci.punishVl);
            c.addDefault(b + "mitigate-vl", ci.mitigateVl);
            for (Map.Entry<String, Double> e : ci.defaults.entrySet()) {
                c.addDefault(b + "params." + e.getKey(), e.getValue());
            }
        }
    }

    public void load(FileConfiguration c) {
        for (CheckInfo ci : map.values()) {
            String b = "checks." + ci.name + ".";
            ci.enabled = c.getBoolean(b + "enabled", true);
            ci.punishable = c.getBoolean(b + "punishable", ci.punishable);
            ci.vlPerFlag = c.getDouble(b + "vl-per-flag", ci.vlPerFlag);
            ci.alertVl = c.getDouble(b + "alert-vl", ci.alertVl);
            ci.punishVl = c.getDouble(b + "punish-vl", ci.punishVl);
            ci.mitigateVl = c.getDouble(b + "mitigate-vl", ci.mitigateVl);
            for (Map.Entry<String, Double> e : ci.defaults.entrySet()) {
                ci.params.put(e.getKey(), c.getDouble(b + "params." + e.getKey(), e.getValue()));
            }
        }
    }
}
