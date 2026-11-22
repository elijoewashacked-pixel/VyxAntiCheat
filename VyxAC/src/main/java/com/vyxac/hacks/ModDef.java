package com.vyxac.hacks;

import java.util.List;

/** A client mod that can be detected (one entry of the "mods" section in checkhacks.yml). */
public final class ModDef {
    /** one thing to ask the client: mode TRANSLATE / KEYBIND / METEOR, the key, and (METEOR) a second translation key */
    public static final class Probe {
        public final String mode, key, key2;

        public Probe(String mode, String key, String key2) {
            this.mode = mode;
            this.key = key;
            this.key2 = key2;
        }
    }

    public final String id, name, category;
    public final boolean enabled, verified;
    public final List<Probe> probes;

    public ModDef(String id, String name, String category, boolean enabled, boolean verified, List<Probe> probes) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.enabled = enabled;
        this.verified = verified;
        this.probes = probes;
    }
}
