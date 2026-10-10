package dev.s1mp1e.o.knife;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.lwjgl.opengl.GL11C;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Glove skins (CS2 glove paint kits) composited per hand on the GPU ({@link GloveCompositor}) from the pack's
 * {@code skins/gloves/}. Result = glove material name -> texture, used in place of the glove's neutral colour map.
 */
public final class GloveSkins {

    private GloveSkins() {}

    private static JsonObject catalog;
    private static boolean catalogTried;
    private static final Map<String, Map<String, Integer>> CACHE = new HashMap<>();
    private static final ArrayDeque<String> LRU = new ArrayDeque<>();
    private static final Map<String, Boolean> FAILED = new HashMap<>();
    private static final int KEEP = 3;

    static Path root() {
        return KnifePack.root().resolve("skins").resolve("gloves");
    }

    public static synchronized JsonObject catalog() {
        if (!catalogTried) {
            catalogTried = true;
            try {
                Path p = root().resolve("catalog.json");
                if (Files.exists(p)) catalog = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Throwable t) {
                System.out.println("[S1mp1e] glove skin catalog unreadable: " + t);
            }
        }
        return catalog;
    }

    /** The skins of one glove model (catalog order), each {skin, zh, en, wear_min, wear_max}. */
    public static List<JsonObject> skinsFor(String glove) {
        List<JsonObject> out = new ArrayList<>();
        JsonObject c = catalog();
        if (c == null || !c.has("gloves")) return out;
        JsonObject g = c.getAsJsonObject("gloves");
        if (!g.has(glove)) return out;
        for (JsonElement e : g.getAsJsonArray(glove)) out.add(e.getAsJsonObject());
        return out;
    }

    /** All skin names across every glove (for the persisted setting's option list). */
    public static List<String> allSkinNames(Path gloveCatalog) {
        List<String> out = new ArrayList<>();
        try {
            if (!Files.exists(gloveCatalog)) return out;
            JsonObject g = JsonParser.parseString(Files.readString(gloveCatalog, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("gloves");
            for (Map.Entry<String, JsonElement> e : g.entrySet())
                for (JsonElement s : e.getValue().getAsJsonArray()) out.add(s.getAsJsonObject().get("skin").getAsString());
        } catch (Throwable ignored) {}
        return out;
    }

    public static JsonObject info(String glove, String skin) {
        for (JsonObject o : skinsFor(glove)) if (o.get("skin").getAsString().equals(skin)) return o;
        return null;
    }

    public static String displayName(String glove, String skin, boolean chinese) {
        JsonObject o = info(glove, skin);
        if (o == null) return skin;
        return o.get(chinese && o.has("zh") ? "zh" : "en").getAsString();
    }

    public static float[] wearRange(String glove, String skin) {
        JsonObject o = info(glove, skin);
        if (o == null) return new float[] { 0f, 1f };
        return new float[] { o.has("wear_min") ? o.get("wear_min").getAsFloat() : 0f,
                o.has("wear_max") ? o.get("wear_max").getAsFloat() : 1f };
    }

    /** Whether this selection is still being decoded / composited (the locker shows a progress shimmer). */
    public static boolean pending(String glove, String skin, float wear) {
        if (skin == null || "vanilla".equals(skin) || info(glove, skin) == null) return false;
        float[] r = wearRange(glove, skin);
        wear = Math.max(r[0], Math.min(r[1], wear));
        String key = glove + "|" + skin + "|" + Math.round(wear * 1000f);
        return !CACHE.containsKey(key) && !FAILED.containsKey(key);
    }

    private record Job(JsonObject entry, java.util.concurrent.CompletableFuture<Map<String, SkinCompositor.Decoded>> inputs) {}

    private static final Map<String, Job> PENDING = new HashMap<>();
    /** glove -> the last skin texture map shown, kept on screen while the next one is being prepared */
    private static final Map<String, Map<String, Integer>> LAST = new HashMap<>();

    /**
     * material name -> composited texture for this glove + skin. Never blocks on disk: the inputs are decoded on
     * a worker, and until they're ready the previously shown skin of this glove (or none) is returned.
     * Render thread. Null = no skin (vanilla / not offered / failed / not ready yet).
     */
    public static Map<String, Integer> skin(String glove, String skin, float wear) {
        if (skin == null || "vanilla".equals(skin) || info(glove, skin) == null) { LAST.remove(glove); return null; }
        float[] r = wearRange(glove, skin);
        wear = Math.max(r[0], Math.min(r[1], wear));
        String key = glove + "|" + skin + "|" + Math.round(wear * 1000f);
        Map<String, Integer> m = CACHE.get(key);
        if (m != null) { LAST.put(glove, m); return m; }
        if (FAILED.containsKey(key)) return null;
        Job job = PENDING.get(key);
        if (job == null) {
            try {
                Path entryPath = root().resolve(glove).resolve(skin + ".json");
                JsonObject entry = JsonParser.parseString(Files.readString(entryPath, StandardCharsets.UTF_8)).getAsJsonObject();
                List<String> files = new ArrayList<>();
                for (Map.Entry<String, JsonElement> e : entry.getAsJsonObject("samplers").entrySet())
                    files.add(e.getValue().getAsJsonObject().get("file").getAsString());
                PENDING.put(key, new Job(entry, SkinLoader.decodeAll(root(), files)));
            } catch (Throwable t) {
                System.out.println("[S1mp1e] glove skin entry unreadable " + key + ": " + t);
                FAILED.put(key, Boolean.TRUE);
                return null;
            }
            return LAST.get(glove);
        }
        if (!job.inputs().isDone()) return LAST.get(glove);
        PENDING.remove(key);
        Map<String, SkinCompositor.Decoded> pre = null;
        try {
            pre = job.inputs().join();
            long t0 = System.nanoTime();
            Map<String, Integer> texs = GloveCompositor.compositeAll(root(), job.entry(), wear, pre);
            m = new HashMap<>();
            int prev = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            for (Map.Entry<String, Integer> h : texs.entrySet()) {
                int id = h.getValue();
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, id);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
                KnifeDraw.skipSrgbDecode();                            // SRGB8_ALPHA8 composite, gamma-space pipeline
                JsonArray mats = job.entry().getAsJsonObject("hands").getAsJsonObject(h.getKey()).getAsJsonArray("materials");
                for (JsonElement mat : mats) m.put(mat.getAsString(), id);
            }
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prev);
            CACHE.put(key, m);
            LRU.addLast(key);
            while (LRU.size() > KEEP) {
                Map<String, Integer> old = CACHE.remove(LRU.removeFirst());
                if (old != null) for (Integer id : new java.util.HashSet<>(old.values()))
                    if (LAST.get(glove) != old) GL11C.glDeleteTextures(id);
            }
            LAST.put(glove, m);
            System.out.printf("[S1mp1e] composited glove %s %s wear %.3f (GPU %.0f ms)%n", glove, skin, wear, (System.nanoTime() - t0) / 1e6);
            return m;
        } catch (Throwable t) {
            System.out.println("[S1mp1e] glove composite failed for " + key + ": " + t);
            FAILED.put(key, Boolean.TRUE);
            return null;
        } finally {
            SkinLoader.free(pre);
        }
    }
}
