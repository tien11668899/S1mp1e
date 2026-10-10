package dev.s1mp1e.client.knife;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.Library;
import dev.s1mp1e.client.module.KnifeModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Plays the CS2 knife SFX (deploy / inspect / slash / swing / hit / backstab) that the pack carries as plain
 * PCM WAV in {@code s1mp1e-knives/sounds/}. The files are the user's own CS2 audio (never bundled in the jar), so
 * they can't go through the resource-registry sound system — instead a decoded clip is streamed straight onto one
 * of the game's own OpenAL channels, acquired from {@link SoundEngine}'s {@link ChannelAccess} (which the engine
 * already ticks and releases for us). First-person, listener-relative, no attenuation — exactly like a viewmodel.
 */
public final class KnifeSounds {

    private KnifeSounds() {}

    private record Clip(byte[] pcm, AudioFormat format) {}

    private static JsonObject manifest;
    private static boolean manifestTried;
    private static final Map<String, Clip> CACHE = new HashMap<>();
    private static final Map<String, Boolean> MISSING = new HashMap<>();

    private static final boolean LOG = System.getenv("S1MP1E_SND_LOG") != null;
    private static boolean reflectTried;
    private static ChannelAccess channelAccess;
    private static Field soundEngineLoaded;
    private static SoundEngine soundEngine;

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

    // ---- event entry points ------------------------------------------------------------------------------
    // The manifest (v2, pipeline/export_sounds.py) holds CS:GO's own knife sound events: "events" name ->
    // {files, vol[min,max], pitch[min,max]} from game_sounds_weapons.txt; "knives" knife -> {deploy, clips:
    // {clip: [[t, event]...]}} = the CS:GO-defined events of each animation at CS2's clip times; "code" the
    // events CS:GO fires from weapon code (deploy / slash / hit / stab / hitwall).

    /** CS:GO mix: the loudest knife sound (hit/stab, 0.6) plays at full volume, everything else in proportion. */
    private static final float MIX = 1f / 0.6f;

    /** Draw started: CS:GO's generic deploy sound, unless this knife has its own draw sound in the animation. */
    public static void deploy(String knife) {
        JsonObject k = knife(knife);
        if (k != null && k.has("deploy") && !k.get("deploy").getAsBoolean()) return;
        playCode("deploy");
    }

    /**
     * An attack actually swung. CS:GO: primary on a player = Weapon_Knife.Hit, secondary on a player =
     * Weapon_Knife.Stab, either on the world = HitWall, a miss = Slash.
     */
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
            stream(clip, vol, pitch);
        } catch (Throwable t) {
            // audio must never take the knife (or the game) down
        }
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static float categoryVolume() {
        try {
            Minecraft mc = Minecraft.getInstance();
            float master = mc.options.getSoundSourceVolume(SoundSource.MASTER);
            float players = mc.options.getSoundSourceVolume(SoundSource.PLAYERS);
            return master * players;
        } catch (Throwable t) {
            return 1f;
        }
    }

    private static void stream(Clip clip, float vol, float pitch) {
        ChannelAccess ca = channels();
        if (ca == null) { if (LOG) System.out.println("[S1mp1e] knife snd: no channel access"); return; }
        ca.createHandle(Library.Pool.STREAMING).thenAccept(handle -> {
            if (handle == null) return;
            handle.execute(channel -> {
                try {
                    channel.attachBufferStream(new WavStream(clip));
                    channel.setRelative(true);
                    channel.disableAttenuation();
                    channel.setVolume(Math.min(1f, vol));
                    channel.setPitch(pitch);
                    channel.play();
                } catch (Throwable ignored) {
                    channel.stop();
                }
            });
        });
    }

    private static synchronized ChannelAccess channels() {
        if (!reflectTried) {
            reflectTried = true;
            try {
                SoundManager sm = Minecraft.getInstance().getSoundManager();
                Field seF = findField(SoundManager.class, SoundEngine.class);
                seF.setAccessible(true);
                soundEngine = (SoundEngine) seF.get(sm);
                Field caF = findField(SoundEngine.class, ChannelAccess.class);
                caF.setAccessible(true);
                channelAccess = (ChannelAccess) caF.get(soundEngine);
                soundEngineLoaded = findBooleanField(SoundEngine.class, "loaded");
                if (soundEngineLoaded != null) soundEngineLoaded.setAccessible(true);
            } catch (Throwable t) {
                System.out.println("[S1mp1e] knife sound channel access unavailable: " + t);
            }
        }
        // don't push onto a library that isn't initialised yet
        try {
            if (soundEngineLoaded != null && soundEngine != null && !soundEngineLoaded.getBoolean(soundEngine)) return null;
        } catch (Throwable ignored) {}
        return channelAccess;
    }

    private static Field findField(Class<?> owner, Class<?> type) {
        for (Field f : owner.getDeclaredFields()) if (type.isAssignableFrom(f.getType())) return f;
        throw new IllegalStateException("no " + type.getSimpleName() + " field on " + owner.getSimpleName());
    }

    private static Field findBooleanField(Class<?> owner, String name) {
        try { return owner.getDeclaredField(name); } catch (Throwable t) { return null; }
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
        AudioFormat fmt = new AudioFormat(rate, bits, channels, true, false);   // signed, little-endian
        return new Clip(pcm, fmt);
    }

    private static int beId(byte[] d, int o) { return chunk((char) d[o], (char) d[o + 1], (char) d[o + 2], (char) d[o + 3]); }
    private static int chunk(char a, char b, char c, char e) { return (a << 24) | (b << 16) | (c << 8) | e; }
    private static int leShort(byte[] d, int o) { return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8); }
    private static int leInt(byte[] d, int o) { return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8) | ((d[o + 2] & 0xFF) << 16) | ((d[o + 3] & 0xFF) << 24); }

    /** Finite PCM stream over a cached clip (returns null at EOF, as {@link Channel} expects). */
    private static final class WavStream implements AudioStream {
        private final Clip clip;
        private final int frame;
        private int pos;

        WavStream(Clip clip) {
            this.clip = clip;
            this.frame = Math.max(1, clip.format.getChannels() * (clip.format.getSampleSizeInBits() / 8));
        }

        @Override public AudioFormat getFormat() { return clip.format; }

        @Override public ByteBuffer read(int want) {
            int remain = clip.pcm.length - pos;
            if (remain <= 0) return null;                       // EOF: Channel stops pumping
            int n = Math.min(want, remain);
            n -= n % frame;                                     // keep whole sample frames
            if (n <= 0) return null;
            ByteBuffer bb = MemoryUtil.memAlloc(n);             // Channel memFrees after uploading
            bb.put(clip.pcm, pos, n);
            bb.flip();
            pos += n;
            return bb;
        }

        @Override public void close() {}
    }

    /** Clear decoded cache (e.g. after the pack is rebuilt). */
    public static synchronized void clearCache() {
        CACHE.clear();
        MISSING.clear();
        manifest = null;
        manifestTried = false;
    }
}
