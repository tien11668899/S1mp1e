package dev.s1mp1e.o.knife;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.s1mp1e.o.client.module.KnifeModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sound.SoundCategory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Plays the CS2 knife SFX (deploy / inspect / slash / swing / hit / backstab) that the pack carries as plain PCM WAV
 * in {@code s1mp1e-knives/sounds/}. The files are the user's own CS2 audio (never bundled in the jar). 1.8.9's sound
 * engine ({@code net.minecraft.client.sound.system.SoundManager}) has no public path to push a raw decoded buffer, so
 * each clip streams straight out of the JVM through {@code javax.sound.sampled}: one short-lived daemon writes the PCM
 * onto a {@link SourceDataLine} whose sample rate is scaled by the event's pitch (so pitch comes for free) with the
 * gain set from the CS:GO mix times the player's Minecraft sound sliders. First-person, no attenuation — like a
 * viewmodel. Exactly the same manifest format as every other version (pipeline/export_sounds.py).
 */
public final class KnifeSounds {

    private KnifeSounds() {}

    /** Parsed WAV: interleaved signed-16-bit-LE PCM, its channel count and base sample rate. */
    private record Clip(byte[] pcm, int channels, int rate, int bits) {}

    private static JsonObject manifest;
    private static boolean manifestTried;
    private static final Map<String, Clip> CACHE = new HashMap<>();
    private static final Map<String, Boolean> MISSING = new HashMap<>();
    private static final boolean LOG = System.getenv("S1MP1E_SND_LOG") != null;

    /** A tiny pool of daemons: a burst of events never spawns an unbounded thread fan-out. */
    private static final ExecutorService PLAYERS = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "S1mp1e knife sound");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private static Path root() { return KnifePack.root().resolve("sounds"); }

    private static synchronized JsonObject manifest() {
        if (!manifestTried) {
            manifestTried = true;
            try {
                Path p = root().resolve("manifest.json");
                if (Files.exists(p)) manifest = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Throwable t) {
                System.out.println("[S1mp1e] knife sound manifest unreadable: " + t);
            }
        }
        return manifest;
    }

    public static boolean available() {
        JsonObject m = manifest();
        return m != null && m.has("knives");
    }

    // ---- event entry points (identical contract to the 26.2 version) ------------------------------------
    /** CS:GO mix: the loudest knife sound (hit/stab, 0.6) plays at full volume, everything else in proportion. */
    private static final float MIX = 1f / 0.6f;

    /** Draw started: CS:GO's generic deploy sound, unless this knife has its own draw sound in the animation. */
    public static void deploy(String knife) {
        JsonObject k = knife(knife);
        if (k != null && k.has("deploy") && !k.get("deploy").getAsBoolean()) return;
        playCode("deploy");
    }

    /** An attack swung: primary on a player = Hit, secondary on a player = Stab, on the world = HitWall, miss = Slash. */
    public static void attack(boolean heavy, boolean target, boolean wall) {
        playCode(target ? (heavy ? "stab" : "hit") : wall ? "hitwall" : "slash");
    }

    /** Fire the animation's sound events whose time falls in (from, to]; {@code from < 0} includes t = 0. */
    public static void clipEvents(String knife, String clip, float from, float to) {
        try {
            JsonObject k = knife(knife);
            if (k == null || !k.has("clips")) return;
            JsonObject clips = k.getAsJsonObject("clips");
            if (!clips.has(clip)) return;
            for (JsonElement e : clips.getAsJsonArray(clip)) {
                JsonArray a = e.getAsJsonArray();
                float t = a.get(0).getAsFloat();
                if (t > from && t <= to || (from < 0f && t <= 0f)) playEvent(a.get(1).getAsString());
            }
        } catch (Throwable ignored) {}
    }

    private static JsonObject knife(String knife) {
        JsonObject m = manifest();
        if (m == null || knife == null || !m.has("knives")) return null;
        JsonObject ks = m.getAsJsonObject("knives");
        return ks.has(knife) ? ks.getAsJsonObject(knife) : null;
    }

    private static void playCode(String kind) {
        JsonObject m = manifest();
        if (m == null || !m.has("code")) return;
        JsonObject code = m.getAsJsonObject("code");
        if (code.has(kind)) playEvent(code.get(kind).getAsString());
    }

    // ---- playback ---------------------------------------------------------------------------------------

    private static void playEvent(String event) {
        try {
            KnifeModule mod = KnifeModule.get();
            if (mod == null || !mod.sounds.boolValue) return;
            JsonObject m = manifest();
            if (m == null || !m.has("events")) return;
            JsonObject events = m.getAsJsonObject("events");
            if (!events.has(event)) return;
            JsonObject e = events.getAsJsonObject(event);
            JsonArray files = e.getAsJsonArray("files");
            if (files.isEmpty()) return;
            ThreadLocalRandom r = ThreadLocalRandom.current();
            String rel = files.get(r.nextInt(files.size())).getAsString();
            Clip clip = load(rel);
            if (clip == null) return;

            JsonArray v = e.getAsJsonArray("vol"), p = e.getAsJsonArray("pitch");
            float base = lerp(v.get(0).getAsFloat(), v.get(1).getAsFloat(), r.nextFloat());
            float pitch = lerp(p.get(0).getAsFloat(), p.get(1).getAsFloat(), r.nextFloat()) / 100f;
            float vol = Math.min(1f, base * MIX) * (float) mod.soundVolume.doubleValue * categoryVolume();
            if (LOG) System.out.printf("[S1mp1e] knife snd %s -> %s  vol=%.2f pitch=%.2f%n", event, rel, vol, pitch);
            if (vol <= 0.001f) return;
            stream(clip, vol, Math.max(0.1f, pitch));
        } catch (Throwable t) {
            // audio must never take the knife (or the game) down
        }
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static float categoryVolume() {
        try {
            var o = Minecraft.getInstance().options;
            return o.getSoundCategoryVolume(SoundCategory.MASTER) * o.getSoundCategoryVolume(SoundCategory.PLAYERS);
        } catch (Throwable t) {
            return 1f;
        }
    }

    /** Push the clip onto a line at {@code rate*pitch} with the gain from {@code vol}; returns immediately. */
    private static void stream(Clip clip, float vol, float pitch) {
        try {
            PLAYERS.execute(() -> {
                SourceDataLine line = null;
                try {
                    AudioFormat fmt = new AudioFormat(clip.rate * pitch, clip.bits, clip.channels, true, false);
                    DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
                    if (!AudioSystem.isLineSupported(info)) return;
                    line = (SourceDataLine) AudioSystem.getLine(info);
                    line.open(fmt);
                    if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                        FloatControl g = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                        float db = (float) (20.0 * Math.log10(Math.max(1e-4f, Math.min(1f, vol))));
                        g.setValue(Math.max(g.getMinimum(), Math.min(g.getMaximum(), db)));
                    }
                    line.start();
                    line.write(clip.pcm, 0, clip.pcm.length);
                    line.drain();
                } catch (Throwable ignored) {
                } finally {
                    if (line != null) try { line.stop(); line.close(); } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    // ---- WAV decode (RIFF / PCM) ------------------------------------------------------------------------

    private static synchronized Clip load(String rel) {
        Clip c = CACHE.get(rel);
        if (c != null) return c;
        if (MISSING.containsKey(rel)) return null;
        try {
            byte[] wav = Files.readAllBytes(root().resolve(rel));
            c = parseWav(wav);
            CACHE.put(rel, c);
            return c;
        } catch (Throwable t) {
            MISSING.put(rel, Boolean.TRUE);
            System.out.println("[S1mp1e] knife sound load failed " + rel + ": " + t);
            return null;
        }
    }

    private static Clip parseWav(byte[] d) {
        if (d.length < 44 || d[0] != 'R' || d[1] != 'I' || d[2] != 'F' || d[3] != 'F') throw new IllegalArgumentException("not RIFF");
        int channels = 1, rate = 22050, bits = 16;
        byte[] pcm = null;
        int i = 12;
        while (i + 8 <= d.length) {
            int id = beId(d, i);
            int sz = leInt(d, i + 4);
            int body = i + 8;
            if (id == chunk('f', 'm', 't', ' ')) {
                channels = leShort(d, body + 2);
                rate = leInt(d, body + 4);
                bits = leShort(d, body + 14);
            } else if (id == chunk('d', 'a', 't', 'a')) {
                int len = Math.min(sz, d.length - body);
                pcm = new byte[len];
                System.arraycopy(d, body, pcm, 0, len);
            }
            i = body + sz + (sz & 1);
        }
        if (pcm == null) throw new IllegalArgumentException("no data chunk");
        return new Clip(pcm, channels, rate, bits);
    }

    private static int beId(byte[] d, int o) { return chunk((char) d[o], (char) d[o + 1], (char) d[o + 2], (char) d[o + 3]); }
    private static int chunk(char a, char b, char c, char e) { return (a << 24) | (b << 16) | (c << 8) | e; }
    private static int leShort(byte[] d, int o) { return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8); }
    private static int leInt(byte[] d, int o) { return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8) | ((d[o + 2] & 0xFF) << 16) | ((d[o + 3] & 0xFF) << 24); }

    /** Clear decoded cache (e.g. after the pack is rebuilt). */
    public static synchronized void clearCache() {
        CACHE.clear();
        MISSING.clear();
        manifest = null;
        manifestTried = false;
    }
}
