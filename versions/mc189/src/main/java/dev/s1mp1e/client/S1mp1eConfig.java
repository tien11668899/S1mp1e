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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import net.minecraft.client.Minecraft;

/**
 * Persists module state to {@code .minecraft/config/s1mp1e/modules.json}.
 *
 * <p>Gson is used directly rather than added as a dependency: vanilla 1.8.9
 * already ships it (2.2.4) and loads it on the game classpath — verified by the
 * imports in {@code net.minecraft.client.renderer.block.model.ModelBlockDefinition}
 * and {@code net.minecraft.client.main.Main}. Only the 2.2.4-era API is used
 * ({@code new JsonParser().parse}, {@code entrySet()}), never the 2.8+ statics.
 *
 * <p>The file is a hint, not a schema: unknown modules, unknown settings and
 * wrong-typed values are skipped individually so a config written by a newer or
 * older build still loads everything it can. Nothing in here throws — a corrupt
 * or missing file simply leaves the compiled-in defaults in place.
 */
public final class S1mp1eConfig {

    private static final String  DIR_NAME  = "s1mp1e";
    private static final String  FILE_NAME = "modules.json";
    private static final Charset UTF8      = Charset.forName("UTF-8");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * The last text actually written. save() is called on every settings change
     * — including each frame of a ClickGUI slider drag — so an unchanged
     * document short-circuits before it touches the disk.
     */
    private static String lastWritten;

    private S1mp1eConfig() {}

    // ---- paths -----------------------------------------------------------

    private static File configFile() {
        File base;
        Minecraft mc = Minecraft.getMinecraft();
        // getMinecraft() is a plain static field read and is null until the
        // Minecraft constructor runs; a coremod-time call must not NPE.
        base = (mc != null && mc.mcDataDir != null) ? mc.mcDataDir : new File(".");
        return new File(new File(new File(base, "config"), DIR_NAME), FILE_NAME);
    }

    // ---- load ------------------------------------------------------------

    public static void load() {
        Reader reader = null;
        try {
            File f = configFile();
            if (!f.isFile()) return;                 // first run — defaults stand

            reader = new InputStreamReader(new FileInputStream(f), UTF8);
            JsonElement root = new JsonParser().parse(reader);
            if (root == null || !root.isJsonObject()) return;

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

            // Drop the write cache rather than seed it: what we just read may be
            // a subset of what we serialise (older build, hand-edited file), so
            // the first save() must be allowed through to normalise the file.
            lastWritten = null;
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

    private static JsonObject build() {
        JsonObject modules = new JsonObject();

        List<Module> all = ModuleManager.all();
        for (int i = 0; i < all.size(); i++) {
            Module m = all.get(i);

            JsonObject settings = new JsonObject();
            for (int j = 0; j < m.settings.size(); j++) {
                Setting s = m.settings.get(j);
                switch (s.type) {
                    case BOOL:   settings.addProperty(s.name, Boolean.valueOf(s.boolValue));  break;
                    case INT:    settings.addProperty(s.name, Integer.valueOf(s.intValue));   break;
                    case DOUBLE: settings.addProperty(s.name, Double.valueOf(s.doubleValue)); break;
                    case COLOR:  settings.addProperty(s.name, Integer.valueOf(s.colorValue)); break;
                    case MODE:   settings.addProperty(s.name, s.modeValue);                   break;
                    default: break;
                }
            }

            JsonObject mod = new JsonObject();
            mod.addProperty("enabled", Boolean.valueOf(m.enabled));
            mod.add("settings", settings);
            modules.add(m.name, mod);
        }

        JsonObject root = new JsonObject();
        root.addProperty("version", Integer.valueOf(1));
        root.add("modules", modules);
        return root;
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
