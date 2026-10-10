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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Knife finishes ("skins"): the CS2 finish catalog from the pack, and composited colour textures cached per
 * knife/paint/wear. A composite runs on the GPU once per selection (see {@link SkinCompositor}).
 */
public final class KnifeSkins {

    private KnifeSkins() {}

    /** A ready skin texture and the knife colour texture (file stem) it replaces. */
    public record Skin(int texture, String base) {}

    private static JsonObject catalog;
    private static boolean catalogTried;
    private static final Map<String, Skin> CACHE = new HashMap<>();
    private static final ArrayDeque<String> LRU = new ArrayDeque<>();
    private static final Map<String, Boolean> FAILED = new HashMap<>();
    private static final int KEEP = 6;

    static Path root() {
        return KnifePack.root().resolve("skins");
    }

    public static synchronized JsonObject catalog() {
        if (!catalogTried) {
            catalogTried = true;
            try {
                Path p = root().resolve("catalog.json");
                if (Files.exists(p)) catalog = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Throwable t) {
                System.out.println("[S1mp1e] knife skin catalog unreadable: " + t);
            }
        }
        return catalog;
    }

    /** Paint kit name for a knife + finish key, or null (vanilla / not offered for that knife). */
    public static String paintFor(String knife, String finish) {
        JsonObject c = catalog();
        if (c == null || finish == null || "vanilla".equals(finish)) return null;
        JsonArray arr = c.getAsJsonObject("knives").getAsJsonArray(knife);
        if (arr == null) return null;
        for (JsonElement e : arr) {
            JsonObject o = e.getAsJsonObject();
            if (finish.equals(o.get("finish").getAsString()) && !o.get("paint").isJsonNull()) return o.get("paint").getAsString();
        }
        return null;
    }

    /** CS2 wear range of a finish (e.g. Fade 0..0.08). */
    public static float[] wearRange(String finish) {
        JsonObject c = catalog();
        if (c == null) return new float[] { 0f, 1f };
        JsonObject f = c.getAsJsonObject("finishes").getAsJsonObject(finish);
        if (f == null) return new float[] { 0f, 1f };
        return new float[] { f.get("wear_min").getAsFloat(), f.get("wear_max").getAsFloat() };
    }

    public static String displayName(String finish, boolean chinese) {
        JsonObject c = catalog();
        if (c == null) return finish;
        JsonObject f = c.getAsJsonObject("finishes").getAsJsonObject(finish);
        return f == null ? finish : f.get(chinese ? "zh" : "en").getAsString();
    }

    /**
     * The composited skin for this selection, compositing it now if needed (render thread). Null = vanilla or
     * unavailable (the knife's own texture is used).
     */
    private record Job(JsonObject entry, java.util.concurrent.CompletableFuture<Map<String, SkinCompositor.Decoded>> inputs) {}

    private static final Map<String, Job> PENDING = new HashMap<>();
    /** knife -> skin last shown, kept on screen while the next one is being prepared */
    private static final Map<String, Skin> LAST = new HashMap<>();

    /** Whether this selection is still being decoded / composited (the locker shows a progress shimmer). */
    public static boolean pending(String knife, String finish, float wear) {
        String paint = paintFor(knife, finish);
        if (paint == null) return false;
        float[] r = wearRange(finish);
        wear = Math.max(r[0], Math.min(r[1], wear));
        String key = knife + "|" + paint + "|" + Math.round(wear * 1000f) + "|" + seed();
        return !CACHE.containsKey(key) && !FAILED.containsKey(key);
    }

    /** The selected CS2 paint seed (pattern template). */
    static int seed() {
        dev.s1mp1e.o.client.module.KnifeModule m = dev.s1mp1e.o.client.module.KnifeModule.get();
        return m == null ? 0 : m.seed.intValue;
    }

    public static Skin skin(String knife, String finish, float wear) {
        String paint = paintFor(knife, finish);
        if (paint == null) { LAST.remove(knife); return null; }
        float[] r = wearRange(finish);
        wear = Math.max(r[0], Math.min(r[1], wear));
        String key = knife + "|" + paint + "|" + Math.round(wear * 1000f) + "|" + seed();
        Skin s = CACHE.get(key);
        if (s != null) { LAST.put(knife, s); return s; }
        if (FAILED.containsKey(key)) return null;
        Job job = PENDING.get(key);
        if (job == null) {                       // decode inputs off-thread; keep showing the previous look
            try {
                Path entryPath = root().resolve(knife).resolve(paint + ".json");
                JsonObject entry = JsonParser.parseString(Files.readString(entryPath, StandardCharsets.UTF_8)).getAsJsonObject();
                java.util.List<String> files = new java.util.ArrayList<>();
                for (Map.Entry<String, com.google.gson.JsonElement> e : entry.getAsJsonObject("samplers").entrySet())
                    files.add(e.getValue().getAsString());
                PENDING.put(key, new Job(entry, SkinLoader.decodeAll(root(), files)));
            } catch (Throwable t) {
                System.out.println("[S1mp1e] skin entry unreadable " + key + ": " + t);
                FAILED.put(key, Boolean.TRUE);
                return null;
            }
            return LAST.get(knife);
        }
        if (!job.inputs().isDone()) return LAST.get(knife);
        PENDING.remove(key);
        Map<String, SkinCompositor.Decoded> pre = null;
        try {
            pre = job.inputs().join();
            JsonObject entry = job.entry();
            int combo = entry.get("combo").getAsInt();
            int style = entry.has("style") ? entry.get("style").getAsInt() : combo % 9;
            int size = Math.min(2048, entry.get("size").getAsInt());
            long t0 = System.nanoTime();
            int tex = SkinCompositor.composite(root(), SeedRoll.apply(entry, seed()), style, wear, size, pre);
            int prev = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex);              // knife UVs tile like CS2's sampler
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
            KnifeDraw.skipSrgbDecode();                                 // SRGB8_ALPHA8 composite, gamma-space pipeline
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prev);
            s = new Skin(tex, entry.get("base").getAsString());
            CACHE.put(key, s);
            LRU.addLast(key);
            while (LRU.size() > KEEP) {
                String old = LRU.removeFirst();
                Skin o = CACHE.remove(old);
                if (o != null && LAST.get(knife) != o) GL11C.glDeleteTextures(o.texture());
            }
            LAST.put(knife, s);
            System.out.printf("[S1mp1e] composited %s %s wear %.3f seed %d (GPU %.0f ms)%n", knife, paint, wear, seed(), (System.nanoTime() - t0) / 1e6);
            return s;
        } catch (Throwable t) {
            System.out.println("[S1mp1e] skin composite failed for " + key + ": " + t);
            FAILED.put(key, Boolean.TRUE);
            return null;
        } finally {
            SkinLoader.free(pre);
        }
    }
}
