package dev.s1mp1e.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Persists module state to {@code modules.json}.
 *
 * <p>Two locations, picked at runtime:
 * <ul>
 *   <li><b>shared</b> — {@code <.minecraft>/s1mp1e-mods/modules.json}, used whenever the
 *       S1mp1e launcher started the game (its game dir is {@code <.minecraft>/instances/<mc>}),
 *       so 1.8.9, 1.20.1, 1.21.1 and 26.2 all read and write ONE file and stay in sync;</li>
 *   <li><b>per-instance</b> — {@code <gameDir>/config/s1mp1e/modules.json}, the historical
 *       path, used for dev runs and non-launcher launches (Feather).</li>
 * </ul>
 *
 * <p>Gson comes from the game's own classpath. Only the 2.2-era API is used
 * ({@code new JsonParser().parse}, {@code entrySet()}), never the 2.8+ statics, so the
 * very same merge logic compiles unchanged on every version that shares this file.
 *
 * <p>The file is a hint, not a schema: unknown modules, unknown settings and
 * wrong-typed values are skipped individually so a config written by a newer or
 * older build still loads everything it can. Nothing in here throws — a corrupt
 * or missing file simply leaves the compiled-in defaults in place.
 */
public final class S1mp1eConfig {

    private static final String  DIR_NAME   = "s1mp1e";
    private static final String  SHARED_DIR = "s1mp1e-mods";
    private static final String  FILE_NAME  = "modules.json";
    private static final Charset UTF8       = Charset.forName("UTF-8");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Where this build sits among the versions sharing modules.json, and the id it
     * records once it has reconciled with the shared file. Higher rank = newer game
     * version; on the FIRST reconcile the newer version's value wins a conflict, and
     * after every version has reconciled once saves merge mutually (last writer wins).
     * Derived from the version id (see rankOf) so the outcome does not depend on launch order.
     */
    private static final String VERSION_ID   = "26.2";
    private static final int    VERSION_RANK = rankOf(VERSION_ID);   // 26.2 -> 260200

    /** Bookkeeping fields in the shared document; any build that does not know them
     *  simply carries them through untouched (they are plain unknown top-level keys). */
    private static final String KEY_SEED_RANK = "_seedRank";
    private static final String KEY_MIGRATED  = "_migrated";

    /** Rank given to a shared file that already holds settings but predates this bookkeeping:
     *  above every build, so the first build to meet it ADOPTS it instead of seeding over the
     *  user's existing cross-version settings. */
    private static final int PRESCHEME_RANK = Integer.MAX_VALUE;

    /**
     * The last text actually written. save() is called on every settings change
     * — including each frame of a ClickGUI slider drag — so an unchanged
     * document short-circuits before it touches the disk.
     */
    private static String lastWritten;

    /** Key that opens the in-game config GUI, as a GLFW code — the same code space the
     *  shared cross-version modules.json uses. Default RightShift; the launcher and the
     *  in-GUI rebind both persist a new value into this same file. */
    private static int menuKey = GLFW.GLFW_KEY_RIGHT_SHIFT;   // GLFW 344
    public static int getMenuKey() { return menuKey; }
    public static void setMenuKey(int code) { menuKey = code; save(); }

    private S1mp1eConfig() {}

    // ---- paths -----------------------------------------------------------

    private static File gameDir() {
        Minecraft mc = Minecraft.getInstance();
        // getInstance() can be null very early; never NPE.
        return (mc != null && mc.gameDirectory != null) ? mc.gameDirectory : new File(".");
    }

    /**
     * The shared cross-version file, or {@code null} when this launch is not under
     * {@code <.minecraft>/instances/<mc>} (dev runClient, Feather, …).
     */
    private static File sharedFile() {
        File base = gameDir();
        File instances = base.getParentFile();              // instances/<mc> -> instances
        if (instances == null) return null;
        File dotMinecraft = instances.getParentFile();      // instances -> .minecraft
        if (dotMinecraft == null) return null;
        File shared = new File(dotMinecraft, SHARED_DIR);
        return shared.isDirectory() ? new File(shared, FILE_NAME) : null;
    }

    private static File perInstanceFile() {
        return new File(new File(new File(gameDir(), "config"), DIR_NAME), FILE_NAME);
    }

    private static File configFile() {
        File shared = sharedFile();
        return shared != null ? shared : perInstanceFile();
    }

    // ---- load ------------------------------------------------------------

    public static void load() {
        File shared = sharedFile();
        File legacy = perInstanceFile();

        if (shared == null) {
            loadFrom(legacy, true);
            // Drop the write cache rather than seed it: what we just read may be
            // a subset of what we serialise (older build, hand-edited file), so
            // the first save() must be allowed through to normalise the file.
            lastWritten = null;
            return;
        }

        JsonObject root      = readRoot(shared);
        boolean    firstTime = !hasMigrated(root);
        // On its FIRST reconcile the newest build wins conflicts, so a build that outranks
        // everything the file has seen keeps ITS OWN values rather than adopting the file's.
        // Every other case adopts: the shared file is the truth.
        boolean    adopt     = !firstTime || VERSION_RANK < readSeedRank(root);

        // This build's own prior state first (an install that predates the shared folder,
        // including modules only this version has), then the shared file on top.
        if (legacy.isFile()) loadFrom(legacy, true);
        loadFrom(shared, adopt);

        lastWritten = null;

        // Reconcile NOW rather than on whatever edit happens to come first: the rank rules
        // only apply to a build's first save, so getting it out of the way here means the
        // user's first in-game change is an ordinary (mutual) save and therefore sticks.
        if (firstTime) save();
    }

    /**
     * @param adoptModules {@code false} while this build is seeding the shared file on its
     *                     first reconcile — the open key is still taken (it is a user choice,
     *                     never rank-gated), but module values are left at ours.
     */
    private static void loadFrom(File f, boolean adoptModules) {
        Reader reader = null;
        try {
            if (!f.isFile()) return;                 // first run — defaults stand

            reader = new InputStreamReader(new FileInputStream(f), UTF8);
            JsonElement root = new JsonParser().parse(reader);
            if (root == null || !root.isJsonObject()) return;

            JsonElement mk = root.getAsJsonObject().get("menuKey");
            if (mk != null && mk.isJsonPrimitive()) {
                try { menuKey = mk.getAsInt(); } catch (Throwable ignored) {}
            }

            if (!adoptModules) return;
            JsonElement modulesEl = root.getAsJsonObject().get("modules");
            if (modulesEl == null || !modulesEl.isJsonObject()) return;
            JsonObject modules = modulesEl.getAsJsonObject();

            for (Map.Entry<String, JsonElement> e : modules.entrySet()) {
                Module m = ModuleManager.byName(e.getKey());
                if (m == null) continue;             // module removed/renamed — ignore
                JsonElement v = e.getValue();
                if (v == null || !v.isJsonObject()) continue;
                applyModule(m, v.getAsJsonObject());
            }
        } catch (Throwable t) {
            System.out.println("[S1mp1e] config load failed, using defaults: " + t);
        } finally {
            close(reader);
        }
    }

    private static void applyModule(Module m, JsonObject obj) {
        JsonElement en = obj.get("enabled");
        if (en != null && en.isJsonPrimitive()) {
            try {
                // Written straight to the field: ModuleManager.init() fires the
                // enable callbacks once, after every module is registered.
                m.enabled = en.getAsBoolean();
            } catch (Throwable ignored) {
                // non-boolean in the file — keep the default
            }
        }

        JsonElement setEl = obj.get("settings");
        if (setEl == null || !setEl.isJsonObject()) return;
        JsonObject set = setEl.getAsJsonObject();

        for (Map.Entry<String, JsonElement> e : set.entrySet()) {
            Setting s = m.setting(e.getKey());
            if (s == null) continue;                 // setting removed — ignore
            try {
                applySetting(s, e.getValue());
            } catch (Throwable ignored) {
                // wrong type for this setting — keep the default and move on
            }
        }
    }

    private static void applySetting(Setting s, JsonElement v) {
        if (v == null || !v.isJsonPrimitive()) return;
        JsonPrimitive p = v.getAsJsonPrimitive();

        switch (s.type) {
            case BOOL:
                s.boolValue = p.getAsBoolean();
                break;
            case INT:
                s.setInt(p.getAsInt());
                break;
            case DOUBLE:
                s.setDouble(p.getAsDouble());
                break;
            case COLOR:
                s.colorValue = readColor(p, s.colorValue);
                break;
            case MODE:
                s.setMode(p.getAsString());          // silently keeps default if unknown
                break;
            default:
                break;
        }
    }

    /**
     * Colours are written as a packed ARGB int, but a hand-edited file is very
     * likely to hold "#AARRGGBB" instead, so both are accepted.
     */
    private static int readColor(JsonPrimitive p, int fallback) {
        if (p.isNumber()) return p.getAsNumber().intValue();
        if (p.isString()) {
            String raw = p.getAsString().trim();
            if (raw.startsWith("#"))  raw = raw.substring(1);
            if (raw.startsWith("0x") || raw.startsWith("0X")) raw = raw.substring(2);
            try {
                // parsed as long: 0xFFFFFFFF overflows a signed int literal
                return (int) Long.parseLong(raw, 16);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    // ---- save ------------------------------------------------------------

    public static void save() {
        Writer writer = null;
        try {
            String text = GSON.toJson(build());
            if (text.equals(lastWritten)) return;    // nothing changed — no disk hit

            File f = configFile();
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                System.out.println("[S1mp1e] could not create " + dir);
                return;
            }

            // Write to a sibling then swap, so a crash mid-write cannot leave a
            // truncated modules.json behind.
            File tmp = new File(f.getParentFile(), FILE_NAME + ".tmp");
            writer = new OutputStreamWriter(new FileOutputStream(tmp), UTF8);
            writer.write(text);
            writer.close();
            writer = null;

            if (!replace(tmp, f)) return;
            lastWritten = text;
        } catch (Throwable t) {
            System.out.println("[S1mp1e] config save failed: " + t);
        } finally {
            close(writer);
        }
    }

    /** Windows refuses renameTo over an existing file, hence the explicit delete. */
    private static boolean replace(File tmp, File dest) {
        if (dest.exists() && !dest.delete()) {
            System.out.println("[S1mp1e] could not replace " + dest);
            tmp.delete();
            return false;
        }
        if (!tmp.renameTo(dest)) {
            System.out.println("[S1mp1e] could not move " + tmp + " into place");
            tmp.delete();
            return false;
        }
        return true;
    }

    /**
     * MERGE at SETTING level, never replace: modules.json is shared by every version and
     * this build knows only some of the modules and some of each module's settings.
     * Starting from the document on disk keeps unknown top-level fields, unknown modules
     * AND unknown settings intact.
     *
     * <p>Conflict rule for the fields this build DOES know:
     * <ul>
     *   <li>already reconciled once ({@link #VERSION_ID} listed in {@code _migrated}) —
     *       write our value: plain last-writer-wins;</li>
     *   <li>first reconcile and we are the newest build seen so far
     *       ({@code VERSION_RANK >= _seedRank}) — write our value, overriding the older
     *       build's;</li>
     *   <li>first reconcile and a newer build got here first — only fill fields that are
     *       absent, so an older build can add but never downgrade.</li>
     * </ul>
     * {@code menuKey} is deliberately NOT rank-gated: it is a user choice made in the
     * launcher or the in-game rebind, so it is always plain last-writer-wins.
     */
    private static JsonObject build() {
        JsonObject root = readRoot(configFile());

        JsonElement modsEl = root.get("modules");
        JsonObject modules = (modsEl != null && modsEl.isJsonObject())
                ? modsEl.getAsJsonObject() : new JsonObject();

        boolean firstTime    = !hasMigrated(root);
        int     fileSeedRank = readSeedRank(root);
        boolean seeding      = VERSION_RANK >= fileSeedRank;

        List<Module> all = ModuleManager.all();
        for (int i = 0; i < all.size(); i++) {
            Module m = all.get(i);

            JsonElement modEl = modules.get(m.name);
            JsonObject mod = (modEl != null && modEl.isJsonObject())
                    ? modEl.getAsJsonObject() : new JsonObject();

            JsonElement setEl = mod.get("settings");
            JsonObject settings = (setEl != null && setEl.isJsonObject())
                    ? setEl.getAsJsonObject() : new JsonObject();

            put(mod, "enabled", new JsonPrimitive(Boolean.valueOf(m.enabled)), firstTime, seeding);

            for (int j = 0; j < m.settings.size(); j++) {
                Setting s = m.settings.get(j);
                JsonPrimitive v = null;
                switch (s.type) {
                    case BOOL:   v = new JsonPrimitive(Boolean.valueOf(s.boolValue));  break;
                    case INT:    v = new JsonPrimitive(Integer.valueOf(s.intValue));   break;
                    case DOUBLE: v = new JsonPrimitive(Double.valueOf(s.doubleValue)); break;
                    case COLOR:  v = new JsonPrimitive(Integer.valueOf(s.colorValue)); break;
                    case MODE:   if (s.modeValue != null) v = new JsonPrimitive(s.modeValue); break;
                    default: break;
                }
                if (v != null) put(settings, s.name, v, firstTime, seeding);
            }

            mod.add("settings", settings);
            modules.add(m.name, mod);
        }

        root.addProperty("version", Integer.valueOf(1));
        root.addProperty("menuKey", Integer.valueOf(menuKey));
        root.add("modules", modules);

        if (firstTime) {
            JsonArray migrated = readMigrated(root);
            migrated.add(new JsonPrimitive(VERSION_ID));
            root.add(KEY_MIGRATED, migrated);
            root.addProperty(KEY_SEED_RANK, Integer.valueOf(Math.max(fileSeedRank, VERSION_RANK)));
        }
        return root;
    }

    /** Writes {@code value} unless a newer build already owns this field (see {@link #build()}). */
    private static void put(JsonObject obj, String key, JsonElement value,
                            boolean firstTime, boolean seeding) {
        if (!firstTime || seeding || !obj.has(key)) obj.add(key, value);
    }

    /** The whole document currently on disk, or an empty object if missing/unreadable. */
    private static JsonObject readRoot(File f) {
        Reader reader = null;
        try {
            if (!f.isFile()) return new JsonObject();
            reader = new InputStreamReader(new FileInputStream(f), UTF8);
            JsonElement root = new JsonParser().parse(reader);
            if (root != null && root.isJsonObject()) return root.getAsJsonObject();
        } catch (Throwable ignored) {
            // unreadable — fall back to writing only what this build knows
        } finally {
            close(reader);
        }
        return new JsonObject();
    }

    private static int readSeedRank(JsonObject root) {
        try {
            JsonElement e = root.get(KEY_SEED_RANK);
            if (e != null && e.isJsonPrimitive()) return fromStored(e.getAsInt());
        } catch (Throwable ignored) {
            // garbage in the field — fall through
        }
        // A shared file written before this bookkeeping existed already holds the user's
        // real cross-version settings. Rank it above every build so the first one to meet
        // it adopts it, fills in whatever is missing, and takes it from there — nobody's
        // "first reconcile" gets to reset it to defaults.
        JsonElement mods = root.get("modules");
        if (mods != null && mods.isJsonObject() && !mods.getAsJsonObject().entrySet().isEmpty()) {
            return PRESCHEME_RANK;
        }
        return -1;
    }

    /**
     * The rank of a Minecraft version id: {@code major*10000 + minor*100 + patch}, so
     * "1.8.9" = 10809, "1.12.2" = 11202, "1.20.1" = 12001, "26.2" = 260200. Every build
     * derives its own rank from {@link #VERSION_ID} with this one function, so any number of
     * versions order correctly with no table to keep in sync.
     */
    static int rankOf(String id) {
        int[] p = new int[3];
        String[] parts = id.split("\\.");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            int v = 0;
            String s = parts[i];
            for (int k = 0; k < s.length() && Character.isDigit(s.charAt(k)); k++) {
                v = v * 10 + (s.charAt(k) - '0');
                if (v > 99) { v = 99; break; }
            }
            p[i] = v;
        }
        return p[0] * 10000 + p[1] * 100 + p[2];
    }

    /**
     * Maps a stored {@code _seedRank} onto the version-derived scale. The first K-1 builds
     * wrote a small table index (1.8.9=0, 1.20.1=1, 1.21.1=2, 26.2=3) or 99 for an adopted
     * pre-bookkeeping file; anything else below the scale is treated the same as 99 (adopt).
     */
    private static int fromStored(int v) {
        if (v < 0) return -1;
        if (v >= 10000) return v;
        switch (v) {
            case 0:  return 10809;    // 1.8.9
            case 1:  return 12001;    // 1.20.1
            case 2:  return 12101;    // 1.21.1
            case 3:  return 260200;   // 26.2
            default: return PRESCHEME_RANK;
        }
    }

    private static JsonArray readMigrated(JsonObject root) {
        JsonElement e = root.get(KEY_MIGRATED);
        if (e != null && e.isJsonArray()) return e.getAsJsonArray();
        return new JsonArray();
    }

    private static boolean hasMigrated(JsonObject root) {
        JsonArray a = readMigrated(root);
        for (int i = 0; i < a.size(); i++) {
            try {
                JsonElement e = a.get(i);
                if (e != null && e.isJsonPrimitive() && VERSION_ID.equals(e.getAsString())) return true;
            } catch (Throwable ignored) {
                // non-string entry — not us
            }
        }
        return false;
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
            // nothing useful to do on a failed close
        }
    }
}
