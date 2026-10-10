package dev.s1mp1e.client.knife;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * CS2-style knife viewmodel state machine over the extracted first-person clips:
 * draw on equip, idle loop, light (left click: hit/miss alternating, backstab from behind), heavy (right click),
 * inspect (F, CS2's "lookat"; rare variants picked the way CS2 does — mostly the first). Clips cross-fade.
 * Attack rate follows CS2's knife timings so spam-clicking doesn't stutter the animation.
 */
public final class KnifeAnimator {

    private static final float FADE = 0.10f;
    /** CS2 knife: primary every 0.4 s, secondary every 1.0 s. */
    private static final float LIGHT_INTERVAL = 0.40f, HEAVY_INTERVAL = 1.0f;

    private String knife;
    private Map<String, List<String>> groups = new HashMap<>();
    private KClip cur, prev;
    private float curT, prevT, fade = 1f;
    /** playhead up to which this clip's sound events have fired (-1 = none yet) */
    private float evT = -1f;
    private boolean curLoop, prevLoop;
    private String curKind = "";
    private int lightSeq;
    private long last;
    private final Random rng = new Random();
    private final Map<KClip, int[]> boneMaps = new IdentityHashMap<>();
    private final float[] sa = new float[7], sb = new float[7];

    /** Switch knife type. Returns true if it changed (the caller decides whether to play the draw). */
    public boolean setKnife(String k) {
        if (k.equals(knife)) return false;
        knife = k;
        cur = prev = null;
        boneMaps.clear();
        groups = group(KnifePack.clipNames(k));
        return true;
    }

    public String knife() { return knife; }

    /** Current clip name and its playhead (seconds) — for DevShot frame sweeps. */
    public String clipName() { return cur == null ? null : cur.name; }
    public float clipTime() { return curT; }

    private static Map<String, List<String>> group(List<String> names) {
        Map<String, List<String>> g = new HashMap<>();
        for (String n : names) {
            if (n.startsWith("all_") || n.contains("_draw") || n.contains("transfix")) continue;
            String key;
            if (n.startsWith("lookat")) key = "inspect";
            else if (n.startsWith("draw")) key = "draw";
            else if (n.startsWith("idle")) key = "idle";
            else if (n.startsWith("light_backstab")) key = "light_backstab";
            else if (n.startsWith("heavy_backstab")) key = "heavy_backstab";
            else if (n.startsWith("light_hit")) key = "light_hit";
            else if (n.startsWith("light_miss")) key = "light_miss";
            else if (n.startsWith("heavy_hit")) key = "heavy_hit";
            else if (n.startsWith("heavy_miss")) key = n.equals("heavy_miss1") ? "heavy_miss" : "heavy_miss_alt";
            else continue;
            g.computeIfAbsent(key, x -> new ArrayList<>()).add(n);
        }
        for (List<String> l : g.values()) l.sort(String::compareTo);
        return g;
    }

    private String first(String key) {
        List<String> l = groups.get(key);
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    // ---- events ---------------------------------------------------------------------------------------------

    public void draw() {
        play(first("draw"), false, "draw");
        KnifeSounds.deploy(knife);
    }

    public void inspect() {
        if (attacking()) return;                       // CS2 won't start an inspect mid-swing
        List<String> l = groups.get("inspect");
        if (l == null || l.isEmpty()) return;
        // CS2: the first inspect is the common one; extra lookats are the rarer variants
        String pick = l.size() == 1 || rng.nextFloat() < 0.7f ? l.get(0) : l.get(1 + rng.nextInt(l.size() - 1));
        play(pick, false, "inspect");                  // its sounds (if CS:GO had any) come from the clip events
    }

    /** @return true if the swing actually started (CS2 attack intervals gate spam clicks). */
    public boolean light(boolean hit, boolean backstab) {
        if ((curKind.equals("light") && curT < LIGHT_INTERVAL) || (curKind.equals("heavy") && curT < HEAVY_INTERVAL)) return false;
        String key = backstab ? "light_backstab" : hit ? "light_hit" : "light_miss";
        List<String> l = groups.get(key);
        if (l == null || l.isEmpty()) l = groups.get(hit ? "light_miss" : "light_hit");
        if (l == null || l.isEmpty()) return false;
        play(l.get(lightSeq++ % l.size()), false, "light");
        return true;
    }

    /** @return true if the swing actually started. */
    public boolean heavy(boolean hit, boolean backstab) {
        if ((curKind.equals("heavy") && curT < HEAVY_INTERVAL) || (curKind.equals("light") && curT < LIGHT_INTERVAL)) return false;
        String n = backstab ? first("heavy_backstab") : hit ? first("heavy_hit") : first("heavy_miss");
        if (n == null) n = first("heavy_miss");
        if (n == null) return false;
        play(n, false, "heavy");
        return true;
    }

    public boolean attacking() {
        return cur != null && (curKind.equals("light") || curKind.equals("heavy")) && curT < cur.duration();
    }

    private void play(String name, boolean loop, String kind) {
        if (name == null || knife == null) return;
        KClip c = KnifePack.clip(knife, name);
        if (c == null) return;
        prev = cur; prevT = curT; prevLoop = curLoop;
        cur = c; curT = 0f; curLoop = loop; curKind = kind;
        evT = -1f;                                     // fire this clip's t = 0 sound events on the next update
        fade = prev == null ? 1f : 0f;
    }

    // ---- per frame ------------------------------------------------------------------------------------------

    public void update() {
        long now = System.nanoTime();
        float dt = last == 0 ? 0f : Math.min(0.1f, (now - last) / 1e9f);
        last = now;
        if (cur == null) {
            String idle = first("idle");
            if (idle != null) play(idle, true, "idle");
            if (cur == null) return;
        }
        curT += dt;
        prevT += dt;
        fade = Math.min(1f, fade + dt / FADE);
        KnifeSounds.clipEvents(knife, cur.name, evT, curT);   // CS:GO sound events at the clip's own times
        evT = curT;
        if (curLoop) {
            float d = cur.duration();
            if (d > 0 && curT > d) { curT %= d; evT = -1f; }
        } else if (curT >= cur.duration()) {
            String idle = first("idle");
            if (idle != null) play(idle, true, "idle");
        }
        if (prev != null && prevLoop) {
            float d = prev.duration();
            if (d > 0 && prevT > d) prevT %= d;
        }
        if (fade >= 1f) prev = null;
    }

    public void apply(KnifeRig rig) {
        rig.reset();
        if (cur == null) return;
        if (prev != null && fade < 1f) {
            sampleInto(rig, prev, prevT);
            int[] map = map(cur, rig);
            for (int b = 0; b < map.length; b++) {
                int node = map[b];
                cur.sample(b, curT, sb);
                if (node >= 0) {
                    System.arraycopy(rig.local, node * 7, sa, 0, 7);
                } else {
                    float[] e = rig.extra(cur.bone[b]);          // knife-own bone (weapon_offset, handles…)
                    if (e == null) { rig.setExtra(cur.bone[b], sb); continue; }
                    System.arraycopy(e, 0, sa, 0, 7);
                }
                for (int k = 0; k < 3; k++) sa[k] += (sb[k] - sa[k]) * fade;
                KMath.nlerp(sa, 3, sb, 3, fade, sa, 3);
                if (node >= 0) rig.setLocal(node, sa); else rig.setExtra(cur.bone[b], sa);
            }
        } else {
            sampleInto(rig, cur, curT);
        }
    }

    private void sampleInto(KnifeRig rig, KClip c, float t) {
        int[] map = map(c, rig);
        for (int b = 0; b < map.length; b++) {
            int node = map[b];
            c.sample(b, t, sa);
            if (node >= 0) rig.setLocal(node, sa);
            else rig.setExtra(c.bone[b], sa);                  // the knife's own bones ride the clip too
        }
    }

    private int[] map(KClip c, KnifeRig rig) {
        int[] m = boneMaps.get(c);
        if (m == null) {
            m = new int[c.bone.length];
            for (int i = 0; i < m.length; i++) m[i] = rig.driver.nodeIndex(c.bone[i]);
            boneMaps.put(c, m);
        }
        return m;
    }
}
