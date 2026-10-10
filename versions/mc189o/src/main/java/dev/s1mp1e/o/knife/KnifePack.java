package dev.s1mp1e.o.knife;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The user's CS2 knife pack in {@code <gameDir>/s1mp1e-knives/} (built from THEIR OWN CS2 install by
 * cs2-knives/pipeline — no Valve asset ships inside this mod). Everything loads lazily and is cached;
 * a missing/broken pack simply disables the feature (vanilla sword renders).
 */
public final class KnifePack {

    private KnifePack() {}

    private static JsonObject meta;
    private static boolean metaTried;
    private static final Map<String, KMesh> MESHES = new HashMap<>();
    private static final Map<String, KClip> CLIPS = new HashMap<>();
    private static final Map<String, Integer> TEXTURES = new HashMap<>();
    private static final Map<String, Boolean> FAILED = new HashMap<>();

    private static Path root;

    /**
     * The knife pack (built from the user's own CS2). One copy is shared by every game version: the instance's own
     * {@code <gameDir>/s1mp1e-knives} if present, else the launcher root's {@code .minecraft/s1mp1e-knives}
     * ({@code <gameDir>/../..} for {@code .minecraft/instances/<ver>}), so 26.2 / 26.3 / ... don't each keep 0.8 GB.
     */
    public static synchronized Path root() {
        if (root == null) {
            Path game = Minecraft.getInstance().gameDir.toPath().toAbsolutePath().normalize();
            Path own = game.resolve("s1mp1e-knives");
            root = own;
            if (!java.nio.file.Files.exists(own.resolve("pack.json"))) {
                for (Path up = game.getParent(); up != null; up = up.getParent()) {
                    Path shared = up.resolve("s1mp1e-knives");
                    if (java.nio.file.Files.exists(shared.resolve("pack.json"))) { root = shared; break; }
                    if (up.getFileName() != null && up.getFileName().toString().equalsIgnoreCase(".minecraft")) break;
                }
            }
        }
        return root;
    }

    public static synchronized boolean available() {
        if (!metaTried) {
            metaTried = true;
            try {
                Path p = root().resolve("pack.json");
                if (Files.exists(p)) {
                    meta = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
                    System.out.println("[S1mp1e] CS2 knife pack loaded: " + meta.getAsJsonObject("knives").size() + " knives");
                } else {
                    System.out.println("[S1mp1e] no CS2 knife pack at " + p);
                }
            } catch (Throwable t) {
                System.out.println("[S1mp1e] CS2 knife pack unreadable: " + t);
                meta = null;
            }
        }
        return meta != null;
    }

    public static boolean hasKnife(String knife) {
        return available() && meta.getAsJsonObject("knives").has(knife);
    }

    public static boolean hasArms(String arms) {
        return available() && meta.getAsJsonObject("arms").has(arms);
    }

    /** Mesh by pack-relative path (e.g. "arms/weapon_arms.s1m"), or null. */
    public static synchronized KMesh mesh(String rel) {
        KMesh m = MESHES.get(rel);
        if (m != null || FAILED.containsKey(rel)) return m;
        try {
            m = KMesh.load(root().resolve(rel));
            MESHES.put(rel, m);
        } catch (Throwable t) {
            System.out.println("[S1mp1e] knife mesh " + rel + " failed: " + t);
            FAILED.put(rel, Boolean.TRUE);
        }
        return m;
    }

    public static KMesh armsMesh(String arms) {
        if (!hasArms(arms)) arms = "default";
        if (!hasArms(arms)) return null;
        return mesh(meta.getAsJsonObject("arms").getAsJsonObject(arms).get("mesh").getAsString());
    }

    public static KMesh knifeMesh(String knife) {
        if (!hasKnife(knife)) return null;
        return mesh(meta.getAsJsonObject("knives").getAsJsonObject(knife).get("mesh").getAsString());
    }

    public static List<String> clipNames(String knife) {
        List<String> out = new ArrayList<>();
        if (!hasKnife(knife)) return out;
        out.addAll(meta.getAsJsonObject("knives").getAsJsonObject(knife).getAsJsonObject("clips").keySet());
        return out;
    }

    public static synchronized KClip clip(String knife, String name) {
        String key = knife + "/" + name;
        KClip c = CLIPS.get(key);
        if (c != null || FAILED.containsKey(key)) return c;
        try {
            JsonObject clips = meta.getAsJsonObject("knives").getAsJsonObject(knife).getAsJsonObject("clips");
            if (!clips.has(name)) { FAILED.put(key, Boolean.TRUE); return null; }
            c = KClip.load(name, root().resolve(clips.getAsJsonObject(name).get("file").getAsString()));
            CLIPS.put(key, c);
        } catch (Throwable t) {
            System.out.println("[S1mp1e] knife clip " + key + " failed: " + t);
            FAILED.put(key, Boolean.TRUE);
        }
        return c;
    }

    /**
     * GL texture id for a pack texture file (uploaded on first use; render thread): bilinear + mipmapped, wrapping —
     * CS2 UVs tile, and nearest filtering shimmers on 2K textures. 0 when missing.
     */
    public static int texture(String file) {
        if (file == null || file.isEmpty()) return 0;
        Integer id = TEXTURES.get(file);
        if (id != null) return id;
        if (FAILED.containsKey("tex:" + file)) return 0;
        try (InputStream in = Files.newInputStream(root().resolve("tex").resolve(file))) {
            java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(in);
            int w = bi.getWidth(), h = bi.getHeight();
            java.nio.ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
            try {
                int[] row = new int[w];
                for (int y = 0; y < h; y++) {
                    bi.getRGB(0, y, w, 1, row, 0, w);
                    for (int x = 0; x < w; x++) {
                        int c = row[x];
                        buf.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >> 24));
                    }
                }
                buf.flip();
                int prev = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
                int tex = GL11C.glGenTextures();
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex);
                GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
                GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1);
                GL11C.glPixelStorei(GL12C.GL_UNPACK_ROW_LENGTH, 0);
                GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, 0);
                GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
                GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, w, h, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, buf);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR_MIPMAP_LINEAR);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
                GL30C.glGenerateMipmap(GL11C.GL_TEXTURE_2D);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prev);
                TEXTURES.put(file, tex);
                return tex;
            } finally {
                MemoryUtil.memFree(buf);
            }
        } catch (Throwable t) {
            System.out.println("[S1mp1e] knife texture " + file + " failed: " + t);
            FAILED.put("tex:" + file, Boolean.TRUE);
            return 0;
        }
    }
}
