package com.vyxac;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Definition + live settings of one check. The same object is mutated on reload, so references stay valid. */
public final class CheckInfo {
    public final String name, category, description;
    public boolean enabled = true, defaultEnabled = true, punishable;
    /** VL added per flag (multiplied by the flag weight) */
    public double vlPerFlag;
    /** alerts are only sent once the VL reaches this value */
    public double alertVl;
    /** the check only counts as punishment evidence once its VL reaches this value */
    public double punishVl;
    /** hits / moves are only cancelled (mitigated) once the VL reaches this value */
    public double mitigateVl;
    final Map<String, Double> defaults = new LinkedHashMap<>();
    final Map<String, Double> params = new HashMap<>();

    CheckInfo(String name, String category, String description) {
        this.name = name;
        this.category = category;
        this.description = description;
    }

    public double p(String key) {
        Double v = params.get(key);
        if (v == null) v = defaults.get(key);
        return v == null ? 0.0 : v;
    }
}
