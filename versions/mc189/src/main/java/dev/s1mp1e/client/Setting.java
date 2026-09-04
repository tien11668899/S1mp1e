package dev.s1mp1e.client;

/**
 * A single tunable value on a {@link Module}.
 *
 * <p>Deliberately NOT generic: one concrete class with a {@link Type} tag. A
 * {@code Setting<T>} hierarchy would force the ClickGUI and the config writer to
 * either reflect on type parameters (erased at runtime) or run an instanceof
 * ladder anyway — the tag makes both call sites a plain switch and keeps the
 * whole thing serialisable without a Gson type adapter.
 *
 * <p>Only the field matching {@link #type} carries meaning; the rest stay at
 * their zero value. Readers must consult {@link #type} first.
 */
public final class Setting {

    public enum Type { BOOL, INT, DOUBLE, COLOR, MODE }

    public final String name;
    public final Type   type;

    public boolean boolValue;
    public int     intValue;
    public double  doubleValue;

    /** Packed ARGB, i.e. {@code 0xAARRGGBB}. Alpha 0xFF is fully opaque. */
    public int colorValue;

    public String   modeValue;
    public String[] modes;

    /** Inclusive bounds for {@link Type#INT} and {@link Type#DOUBLE}; else 0. */
    public double min, max;

    private Setting(String name, Type type) {
        this.name = name;
        this.type = type;
    }

    public static Setting bool(String name, boolean def) {
        Setting s = new Setting(name, Type.BOOL);
        s.boolValue = def;
        return s;
    }

    public static Setting integer(String name, int def, int min, int max) {
        Setting s = new Setting(name, Type.INT);
        s.min = min;
        s.max = max;
        s.intValue = (int) clamp(def, min, max);
        return s;
    }

    public static Setting number(String name, double def, double min, double max) {
        Setting s = new Setting(name, Type.DOUBLE);
        s.min = min;
        s.max = max;
        s.doubleValue = clamp(def, min, max);
        return s;
    }

    public static Setting color(String name, int argbDef) {
        Setting s = new Setting(name, Type.COLOR);
        s.colorValue = argbDef;
        return s;
    }

    public static Setting mode(String name, String def, String... options) {
        Setting s = new Setting(name, Type.MODE);
        s.modes = (options == null || options.length == 0) ? new String[] { def } : options;
        s.modeValue = s.isValidMode(def) ? def : s.modes[0];
        return s;
    }

    // ---- guarded writers -------------------------------------------------
    // The ClickGUI and the config loader both feed untrusted numbers in here
    // (a dragged slider overshoots, an edited JSON file lies), so clamping and
    // mode validation live on the setting rather than at every call site.

    public void setInt(int v)       { intValue    = (int) clamp(v, min, max); }
    public void setDouble(double v) { doubleValue = clamp(v, min, max); }

    /** Ignores a value that is not one of {@link #modes} — keeps the old one. */
    public void setMode(String v) {
        if (isValidMode(v)) modeValue = v;
    }

    /** Advances to the next option, wrapping. Used by the ClickGUI click-cycle. */
    public void cycleMode() {
        if (modes == null || modes.length == 0) return;
        int i = indexOfMode(modeValue);
        modeValue = modes[(i + 1) % modes.length];
    }

    public boolean isValidMode(String v) {
        return indexOfMode(v) >= 0;
    }

    private int indexOfMode(String v) {
        if (v == null || modes == null) return -1;
        for (int i = 0; i < modes.length; i++) {
            if (v.equals(modes[i])) return i;
        }
        return -1;
    }

    /** 0..1 position of the current value inside [min,max]; 0 for non-numerics. */
    public double normalised() {
        if (max <= min) return 0.0;
        double v = (type == Type.INT) ? intValue : doubleValue;
        return clamp((v - min) / (max - min), 0.0, 1.0);
    }

    private static double clamp(double v, double lo, double hi) {
        if (hi <= lo) return v;          // unbounded (BOOL/COLOR/MODE) — pass through
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
