package dev.s1mp1e.client.knife;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;

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
    private static final Map<String, Identifier> TEXTURES = new HashMap<>();
    private static final Map<String, Boolean> FAILED = new HashMap<>();
    private static GpuSampler sampler;

    private static Path root;

    /**
     * The knife pack (built from the user's own CS2). One copy is shared by every game version: the instance's own
     * {@code <gameDir>/s1mp1e-knives} if present, else the launcher root's {@code .minecraft/s1mp1e-knives}
     * ({@code <gameDir>/../..} for {@code .minecraft/instances/<ver>}), so 26.2 / 26.3 / ... don't each keep 0.8 GB.
     */
    public static synchronized Path root() {
        if (root == null) {
            Path game = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize();
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

    /** Texture id for a pack texture file name (registers it on first use; render thread). */
    public static Identifier texture(String file) {
        if (file == null || file.isEmpty()) return null;
        Identifier id = TEXTURES.get(file);
        if (id != null || FAILED.containsKey("tex:" + file)) return id;
        try (InputStream in = Files.newInputStream(root().resolve("tex").resolve(file))) {
            NativeImage img = NativeImage.read(in);
            String path = "knife/" + file.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
            id = Identifier.fromNamespaceAndPath("s1mp1e", path);
            Minecraft.getInstance().getTextureManager().register(id, new SmoothTexture(path, img));
            TEXTURES.put(file, id);
        } catch (Throwable t) {
            System.out.println("[S1mp1e] knife texture " + file + " failed: " + t);
            FAILED.put("tex:" + file, Boolean.TRUE);
        }
        return id;
    }

    /** Bilinear, wrapping sampler: CS2 UVs tile and the vanilla nearest filter shimmers on 2K textures. */
    private static final class SmoothTexture extends DynamicTexture {
        SmoothTexture(String label, NativeImage image) {
            super(() -> "s1mp1e:" + label, image);
            if (sampler == null) {
                sampler = RenderSystem.getDevice().createSampler(AddressMode.REPEAT, AddressMode.REPEAT,
                        FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty());
            }
            this.sampler = sampler;
        }
    }
}
