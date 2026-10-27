package dev.s1mp1e.o.client.module;

import dev.s1mp1e.o.client.Module;
import dev.s1mp1e.o.client.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.living.player.ClientPlayerEntity;
import net.minecraft.client.entity.living.player.LocalClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

/**
 * DragonWings (ported from 26.3): small Ender Dragon wings on the player's own back instead of the cape — a client-side
 * look, so only this player sees them (and only on themselves: everyone else keeps their cape). Drawn by
 * {@code DragonWingsLayer}; the cape is skipped by {@code CapeLayerWingsMixin} while they show.
 *
 * <p>The wings move with the player: a slow, light beat standing still, quicker while walking and sprinting, wide
 * strong strokes in the air or in creative flight, folded along the back while sneaking. Hidden while the player is
 * invisible. (1.8.9 has no elytra, so there is nothing else to make room for.)
 */
public final class DragonWingsModule extends Module {
    public final Setting size = add(Setting.number("Wing size", 1.0D, 0.5D, 1.6D));

    private static DragonWingsModule instance;

    public DragonWingsModule() {
        super("DragonWings", "Visual");
        instance = this;
    }

    /** True when {@code p} is the local player and the wings replace the cape on it this frame. */
    public static boolean showsOn(ClientPlayerEntity p) {
        DragonWingsModule m = instance;
        if (m == null || !m.enabled || p == null || p.isInvisible()) return false;
        return p == Minecraft.getInstance().player;
    }

    public static float scale() {
        DragonWingsModule m = instance;
        return m == null ? 1.0F : (float) m.size.doubleValue;
    }

    // ---- motion → wing beat (local player only, advanced once per frame) ----

    private static long lastNanos;
    private static float phase, amplitude = 0.22F, spread = 0.8F, speed = 1.6F;

    /**
     * This frame's pose as {phase, amplitude, spread}. The world and the inventory preview both draw the player, so
     * time only advances on the first call of a frame (a call within 2 ms of the last reuses it).
     */
    public static float[] pose(ClientPlayerEntity p) {
        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 0.0F : (now - lastNanos) / 1.0E9F;
        if (lastNanos == 0L || dt > 0.002F) {
            lastNanos = now;
            advance(Math.min(dt, 0.1F), p);
        }
        return new float[]{phase, amplitude, spread};
    }

    private static void advance(float dt, ClientPlayerEntity player) {
        LocalClientPlayerEntity p = Minecraft.getInstance().player;
        // targets: beat speed (rad/s), stroke amplitude, how far open
        float tSpeed = 1.6F, tAmp = 0.22F, tSpread = 0.8F;
        if (p != null) {
            double horiz = Math.sqrt(p.velocityX * p.velocityX + p.velocityZ * p.velocityZ);
            boolean flying = p.abilities.flying;
            boolean airborne = !p.onGround && !p.isInWater() && !p.isClimbing() && p.vehicle == null;
            if (player.isSneaking()) {
                tSpeed = 1.0F; tAmp = 0.05F; tSpread = 0.0F;          // folded along the back
            } else if (flying) {
                tSpeed = 7.0F; tAmp = 1.0F; tSpread = 1.0F;
            } else if (airborne) {
                // jumping or falling: open wide; falling fast beats hard
                float fall = MathHelper.clamp((float) (-p.velocityY / 0.6D), 0.0F, 1.0F);
                tSpeed = 5.0F + 3.0F * fall; tAmp = 0.75F + 0.25F * fall; tSpread = 1.0F;
            } else if (horiz > 0.01D) {
                float run = MathHelper.clamp((float) (horiz / 0.28D), 0.0F, 1.0F);   // ~0.28 blocks/tick ≈ sprinting
                tSpeed = 2.4F + 2.6F * run; tAmp = 0.3F + 0.25F * run; tSpread = 0.85F + 0.15F * run;
            }
        }
        // ease toward the targets so changes of state never snap
        float k = 1.0F - (float) Math.exp(-6.0F * dt);
        speed += (tSpeed - speed) * k;
        amplitude += (tAmp - amplitude) * k;
        spread += (tSpread - spread) * k;
        phase = (phase + speed * dt) % ((float) Math.PI * 2.0F * 64.0F);
    }
}
