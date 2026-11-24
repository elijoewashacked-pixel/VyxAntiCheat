package com.vyxac.hacks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** The outcome of one check of one player. */
public final class ScanResult {
    public static final class Entry {
        public final String modName;
        public ProbeEval.Result result;

        public Entry(String modName, ProbeEval.Result result) {
            this.modName = modName;
            this.result = result;
        }
    }

    public long id;
    public final long ts;
    public final UUID uuid;
    public final String name, reason, starter;
    public boolean doubleChecked;
    public final LinkedHashMap<String, Entry> results = new LinkedHashMap<>();

    public ScanResult(long ts, UUID uuid, String name, String reason, String starter) {
        this.ts = ts;
        this.uuid = uuid;
        this.name = name;
        this.reason = reason;
        this.starter = starter;
    }

    public List<String> names(ProbeEval.Result r) {
        List<String> l = new ArrayList<>();
        for (Entry e : results.values()) if (e.result == r) l.add(e.modName);
        return l;
    }

    public boolean flagged() {
        for (Entry e : results.values()) if (e.result == ProbeEval.Result.DETECTED || e.result == ProbeEval.Result.PROTECTED) return true;
        return false;
    }
}
