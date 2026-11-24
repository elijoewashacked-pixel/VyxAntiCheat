package com.vyxac.hacks;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure logic of the sign-translation check (no Minecraft types, so it can be tested on its own).
 *
 * The client resolves translatable / keybind components BEFORE it sends the sign text back, so:
 *   - a key the client knows comes back as its translation / key name  -> the mod is installed
 *   - a key the client does not know comes back unchanged               -> the mod is absent
 *   - every sign carries a CONTROL line with a random unknown key: if even that comes back changed or empty,
 *     the client rewrites sign text (an exploit preventer) and nothing it says can be trusted -> PROTECTED
 */
public final class ProbeEval {
    private ProbeEval() {}

    public enum Result { DETECTED, NOT_DETECTED, PROTECTED, SKIPPED }

    /** one probe the caller wants answered. mode is TRANSLATE, KEYBIND or METEOR (keybind + category translation) */
    public static final class ProbeRef {
        public final int id;
        public final String mode, key, key2;

        public ProbeRef(int id, String mode, String key, String key2) {
            this.id = id;
            this.mode = mode;
            this.key = key;
            this.key2 = key2 == null ? "" : key2;
        }

        int lines() {
            return mode.equals("METEOR") && !key2.isEmpty() ? 2 : 1;
        }
    }

    /** one virtual sign: 3 content lines + 1 control line */
    public static final class Sign {
        public final String[] kinds = {"EMPTY", "EMPTY", "EMPTY", "EMPTY"};
        public final String[] keys = {"", "", "", ""};
        public final int control = 3;
        public final List<Integer> probeIds = new ArrayList<>();
        public final List<int[]> probeLines = new ArrayList<>();
        public String mode = "";
        int used;
    }

    /** Packs probes into signs. Probes of different modes never share a sign (the control line must match the mode). */
    public static List<Sign> plan(List<ProbeRef> probes, String seed) {
        List<Sign> signs = new ArrayList<>();
        Sign cur = null;
        for (ProbeRef p : probes) {
            int need = p.lines();
            if (cur == null || !cur.mode.equals(p.mode) || cur.used + need > 3) {
                cur = new Sign();
                cur.mode = p.mode;
                signs.add(cur);
                boolean translate = p.mode.equals("TRANSLATE");
                cur.kinds[3] = translate ? "TRANSLATE" : "KEYBIND";
                cur.keys[3] = (translate ? "vyxac.control." : "key.vyxac.control.") + seed + signs.size();
            }
            int[] lines = new int[need];
            for (int i = 0; i < need; i++) {
                int line = cur.used++;
                lines[i] = line;
                boolean second = i == 1;
                switch (p.mode) {
                    case "TRANSLATE" -> cur.kinds[line] = "TRANSLATE";
                    case "KEYBIND" -> cur.kinds[line] = "KEYBIND";
                    default -> cur.kinds[line] = second ? "TRANSLATE" : "KEYBIND"; // METEOR
                }
                cur.keys[line] = second ? p.key2 : p.key;
            }
            cur.probeIds.add(p.id);
            cur.probeLines.add(lines);
        }
        return signs;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * Judges one sign. resp == null means the client never answered (it is probably blocking the packet) -> PROTECTED.
     * Returns one result per probe of the sign, in the order of sign.probeIds.
     */
    public static Result[] evaluate(Sign sign, String[] resp) {
        int n = sign.probeIds.size();
        Result[] out = new Result[n];
        if (resp == null || resp.length < 4) {
            java.util.Arrays.fill(out, Result.PROTECTED);
            return out;
        }
        if (!norm(resp[sign.control]).equals(norm(sign.keys[sign.control]))) { // exploit preventer rewrote even the control line
            java.util.Arrays.fill(out, Result.PROTECTED);
            return out;
        }
        for (int i = 0; i < n; i++) {
            boolean detected = false, blank = false;
            for (int line : sign.probeLines.get(i)) {
                String r = norm(resp[line]);
                if (r.isEmpty()) blank = true;
                else if (!r.equals(norm(sign.keys[line]))) detected = true;
            }
            out[i] = detected ? Result.DETECTED : (blank ? Result.PROTECTED : Result.NOT_DETECTED);
        }
        return out;
    }

    /** DoubleCheck: a result only stands when the second scan agrees. */
    public static Result merge(Result first, Result second) {
        if (first == second) return first;
        if (first == Result.PROTECTED && second == Result.DETECTED) return Result.DETECTED;
        if (first == Result.DETECTED && second == Result.PROTECTED) return Result.PROTECTED;
        return Result.NOT_DETECTED; // the second scan did not confirm: treat as a false positive
    }

    /** a mod is DETECTED if any probe is, else PROTECTED if any probe is, else clean */
    public static Result combine(List<Result> r) {
        boolean prot = false;
        for (Result x : r) {
            if (x == Result.DETECTED) return Result.DETECTED;
            if (x == Result.PROTECTED) prot = true;
        }
        return prot ? Result.PROTECTED : Result.NOT_DETECTED;
    }
}
