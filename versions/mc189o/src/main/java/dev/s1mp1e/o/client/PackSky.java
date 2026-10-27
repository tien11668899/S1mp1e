package dev.s1mp1e.o.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.Minecraft;
import net.minecraft.resource.Identifier;

/**
 * Sky and fog colours chosen by a resource pack: a pack with {@code assets/s1mp1e/clean_sky.json}
 * ({@code {"sky": "#8CC4F4", "fog": "#E2EFFA"}}, the S1mp1e Clean pack has one) replaces the biome sky colour and the
 * overworld's base fog colour; vanilla's day/night brightness, rain, thunder and sunsets still apply on top. Without
 * such a pack everything stays vanilla. Re-read on every resource reload ({@code MinecraftMixin}).
 *
 * <p>All keys are optional. The S1mp1e Mono pack (黑白風) adds {@code "sunset"}, which recolours the sunrise and sunset
 * glow to that colour at vanilla's brightness ({@code PackSunsetMixin}), and {@code "mono": true}, which also turns the
 * leaf tints the game hard-codes (spruce, birch, the default) grey ({@code PackFoliageMixin}), which a pack's colour
 * maps cannot reach. {@code "skybox": true} (packs ported from Bedrock) draws the six faces
 * {@code assets/s1mp1e/textures/sky/cubemap_0..5.png} in place of the overworld sky ({@code PackSkyboxMixin}); the fog
 * then keeps its colour through the night, like the sky box does.
 */
public final class PackSky {
    private PackSky() {}

    public static volatile boolean active;
    public static volatile boolean hasSky, hasFog;
    /** Packed RGB of the sky. */
    public static volatile int sky;
    public static volatile float fogR, fogG, fogB;
    public static volatile boolean hasSunset, mono, skybox;
    public static volatile float sunsetR, sunsetG, sunsetB;

    /** The sky box faces in Bedrock's cubemap order: north, east, south, west, up, down. */
    public static final Identifier[] SKYBOX = new Identifier[6];

    static {
        for (int i = 0; i < 6; i++) SKYBOX[i] = new Identifier("s1mp1e", "textures/sky/cubemap_" + i + ".png");
    }

    public static void reload() {
        boolean was = active;
        active = false;
        hasSky = hasFog = hasSunset = mono = skybox = false;
        InputStream in = null;
        try {
            in = Minecraft.getInstance().getResourceManager().getResource(new Identifier("s1mp1e", "clean_sky.json")).asStream();
            JsonObject o = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            StringBuilder log = new StringBuilder("[S1mp1e] pack sky:");
            if (o.has("sky")) {
                sky = rgb(o.get("sky").getAsString());
                hasSky = true;
                log.append(String.format(" sky #%06X", sky));
            }
            if (o.has("fog")) {
                int f = rgb(o.get("fog").getAsString());
                fogR = ((f >> 16) & 255) / 255f;
                fogG = ((f >> 8) & 255) / 255f;
                fogB = (f & 255) / 255f;
                hasFog = true;
                log.append(String.format(" fog #%06X", f));
            }
            if (o.has("sunset")) {
                int u = rgb(o.get("sunset").getAsString());
                sunsetR = ((u >> 16) & 255) / 255f;
                sunsetG = ((u >> 8) & 255) / 255f;
                sunsetB = (u & 255) / 255f;
                hasSunset = true;
                log.append(", sunset recoloured");
            }
            mono = o.has("mono") && o.get("mono").getAsBoolean();
            skybox = o.has("skybox") && o.get("skybox").getAsBoolean();
            active = true;
            System.out.println(log.append(mono ? ", mono" : "").append(skybox ? ", sky box" : ""));
        } catch (FileNotFoundException e) {
            if (was) System.out.println("[S1mp1e] pack sky: none (vanilla sky)");
        } catch (Throwable t) {
            System.out.println("[S1mp1e] pack sky unreadable, vanilla sky: " + t);
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    /** {@code rgb} as a grey of the same luminance (the mono pack's leaf tints). */
    public static int grey(int rgb) {
        int l = Math.round(0.299f * ((rgb >> 16) & 255) + 0.587f * ((rgb >> 8) & 255) + 0.114f * (rgb & 255));
        return (l << 16) | (l << 8) | l;
    }

    private static int rgb(String s) {
        s = s.trim();
        if (s.startsWith("#")) s = s.substring(1);
        return Integer.parseInt(s, 16) & 0xFFFFFF;
    }
}
