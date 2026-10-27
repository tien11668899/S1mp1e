package dev.s1mp1e.o.client.module;

import dev.s1mp1e.o.client.Module;
import dev.s1mp1e.o.client.Setting;

/**
 * Particles (ported from 26.3): one switch for every particle in the game — block cracks, crits, potion swirls, smoke,
 * rain splashes, explosions, mod particles — because they all enter the world through {@code ParticleManager.add}
 * ({@code ParticleLimitMixin}). Off = vanilla.
 *
 * <ul>
 *   <li>{@code Reduced}: keeps an even share of them ({@code Keep}, default a third) — every n-th particle by a running
 *       counter, not by chance, so a stream thins out evenly instead of flickering. Emitters (crit / enchant bursts,
 *       {@code ParticleManager.addEmitter}) still start; only the particles they throw are thinned, so the share is not
 *       applied twice.</li>
 *   <li>{@code None}: no particles at all, emitters included.</li>
 * </ul>
 * Purely visual and client-side; nothing about the world, the server or other players changes.
 */
public final class ParticlesModule extends Module {

    private static ParticlesModule instance;

    public final Setting amount = add(Setting.mode("Amount", "Reduced", "Reduced", "None"));
    public final Setting keep = add(Setting.number("Keep", 33.0D, 5.0D, 90.0D));

    private int counter;

    public ParticlesModule() { super("Particles", "Visual"); instance = this; }

    /**
     * Whether the particle about to be added should be dropped. {@code emitter} is a tracking emitter (it spawns the
     * real particles over the next ticks). Client thread only.
     */
    public static boolean drop(boolean emitter) {
        ParticlesModule m = instance;
        if (m == null || !m.enabled) return false;
        if ("None".equals(m.amount.modeValue)) return true;
        if (emitter) return false;
        double share = Math.max(0.05D, Math.min(0.9D, m.keep.doubleValue / 100.0D));
        // keep the particle when the running total crosses the next whole step: exactly `share` of them, evenly spaced
        int before = (int) Math.floor(m.counter * share);
        m.counter++;
        int after = (int) Math.floor(m.counter * share);
        if (m.counter >= 1_000_000) m.counter = 0;
        return after == before;
    }
}
