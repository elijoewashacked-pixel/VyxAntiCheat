package com.vyxac;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

import java.util.HashMap;
import java.util.Map;

/**
 * Small helpers that keep VyxAC working across 1.20.x and 1.21.x.
 * Attribute and potion constants were renamed between versions, so they are looked up by name at runtime
 * instead of being referenced directly.
 */
public final class Compat {
    private static final Map<String, Attribute> ATTR_CACHE = new HashMap<>();
    private static final Map<Integer, Double> APEX_CACHE = new HashMap<>();

    private static java.lang.reflect.Method PING;
    private static boolean pingInit;

    private Compat() {}

    /** Player ping without a compile-time dependency on Player#getPing (not present on every API version). */
    public static int ping(Player p) {
        try {
            if (!pingInit) {
                pingInit = true;
                try {
                    PING = Player.class.getMethod("getPing");
                } catch (Throwable t) {
                    PING = null;
                }
            }
            if (PING == null) return 0;
            return ((Number) PING.invoke(p)).intValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    public static Attribute attr(String... names) {
        String key = names[0];
        if (ATTR_CACHE.containsKey(key)) return ATTR_CACHE.get(key);
        Attribute found = null;
        for (String n : names) {
            try {
                Object o = Attribute.class.getField(n).get(null);
                if (o instanceof Attribute) {
                    found = (Attribute) o;
                    break;
                }
            } catch (Throwable ignored) {
                // constant does not exist on this server version
            }
        }
        ATTR_CACHE.put(key, found);
        return found;
    }

    public static double attrValue(Player p, double def, String... names) {
        try {
            Attribute a = attr(names);
            if (a == null) return def;
            AttributeInstance ai = p.getAttribute(a);
            return ai == null ? def : ai.getValue();
        } catch (Throwable t) {
            return def;
        }
    }

    /** Effect level (amplifier + 1) of the first effect whose legacy or new name matches, 0 if none. */
    public static int level(Player p, String... names) {
        try {
            for (PotionEffect e : p.getActivePotionEffects()) {
                String n = e.getType().getName();
                if (n == null) continue;
                for (String want : names) {
                    if (n.equalsIgnoreCase(want)) return e.getAmplifier() + 1;
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return 0;
    }

    /** Highest point (blocks above the take-off point) a normal jump can reach, simulated with vanilla gravity. */
    public static double jumpApex(int jumpLvl, double jumpStrength) {
        double v = jumpStrength + 0.1 * jumpLvl;
        int key = (int) Math.round(v * 1000.0);
        Double cached = APEX_CACHE.get(key);
        if (cached != null) return cached;
        double y = 0;
        for (int i = 0; i < 400 && v > 0; i++) {
            y += v;
            v = (v - 0.08) * 0.98;
        }
        APEX_CACHE.put(key, y);
        return y;
    }
}
