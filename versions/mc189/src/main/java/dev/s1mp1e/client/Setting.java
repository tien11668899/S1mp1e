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

    // ---- factory defaults, captured so the config GUI can reset a setting ----
    // Only the field matching {@link #type} is meaningful (same rule as the value
    // fields). Set once by the factory; never mutated.
    public final boolean defBool;
    public final int     defInt;
    public final double  defDouble;
    public final int     defColor;
    public final String  defMode;

    private Setting(String name, Type type,
                    boolean defBool, int defInt, double defDouble, int defColor, String defMode) {
        this.name = name;
        this.type = type;
        this.defBool = defBool;
        this.defInt = defInt;
        this.defDouble = defDouble;
        this.defColor = defColor;
        this.defMode = defMode;
    }

    public static Setting bool(String name, boolean def) {
        Setting s = new Setting(name, Type.BOOL, def, 0, 0.0, 0, null);
        s.boolValue = def;
        return s;
    }

    public static Setting integer(String name, int def, int min, int max) {
        int d = (int) clamp(def, min, max);
        Setting s = new Setting(name, Type.INT, false, d, 0.0, 0, null);
        s.min = min;
        s.max = max;
        s.intValue = d;
        return s;
    }

    public static Setting number(String name, double def, double min, double max) {
        double d = clamp(def, min, max);
        Setting s = new Setting(name, Type.DOUBLE, false, 0, d, 0, null);
        s.min = min;
        s.max = max;
        s.doubleValue = d;
        return s;
    }

    public static Setting color(String name, int argbDef) {
        Setting s = new Setting(name, Type.COLOR, false, 0, 0.0, argbDef, null);
        s.colorValue = argbDef;
        return s;
    }

    public static Setting mode(String name, String def, String... options) {
        String[] opts = (options == null || options.length == 0) ? new String[] { def } : options;
        // Validate the default against the option list (mirror isValidMode without an instance yet).
        String d = def;
        boolean ok = false;
        for (int i = 0; i < opts.length; i++) { if (opts[i].equals(def)) { ok = true; break; } }
        if (!ok) d = opts[0];
        Setting s = new Setting(name, Type.MODE, false, 0, 0.0, 0, d);
        s.modes = opts;
        s.modeValue = d;
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

    /** Advances to the PREVIOUS option, wrapping. Used by the ClickGUI right-click. */
    public void cycleModeBack() {
        if (modes == null || modes.length == 0) return;
        int i = indexOfMode(modeValue);
        if (i < 0) i = 0;
        modeValue = modes[(i - 1 + modes.length) % modes.length];
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

    /** Restore the value the factory was given. Used by the config GUI's reset. */
    public void reset() {
        switch (type) {
            case BOOL:   boolValue  = defBool;  break;
            case INT:    setInt(defInt);        break;
            case DOUBLE: setDouble(defDouble);  break;
            case COLOR:  colorValue = defColor; break;
            case MODE:   setMode(defMode);      break;
        }
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
