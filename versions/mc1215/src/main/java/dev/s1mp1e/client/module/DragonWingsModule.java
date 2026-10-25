package dev.s1mp1e.client.module;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.wings.DragonWingsModel;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Items;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * DragonWings: small Ender Dragon wings on the player's own back instead of the cape — a client-side look, so only this
 * player sees them (and only on themselves: everyone else keeps their cape). Drawn by {@code DragonWingsFeature}; the
 * cape is skipped by {@code CapeWingsMixin} while they show.
 *
 * <p>The wings move with the player: a slow, light beat standing still, quicker while walking and sprinting, wide
 * strong strokes in the air or in creative flight, folded along the back while sneaking. They are hidden while an
 * elytra is worn (it uses the same spot) and when the player is invisible.
 */
public final class DragonWingsModule extends Module {
    public final Setting size = add(Setting.number("Wing size", 1.0D, 0.5D, 1.6D));

    private static DragonWingsModule instance;

    public DragonWingsModule() {
        super("DragonWings", "Visual");
        instance = this;
    }

    /** True when {@code player} is the local player and the wings replace the cape on it this frame. */
    public static boolean showsOn(AbstractClientPlayerEntity player) {
        DragonWingsModule m = instance;
        if (m == null || !m.enabled || player == null || player.isInvisible()) return false;
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p == null || player.getId() != p.getId()) return false;
        // an elytra sits exactly where the wings go
        return !player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
    }

    public static float scale() {
        DragonWingsModule m = instance;
        return m == null ? 1.0F : (float) m.size.doubleValue;
    }

    // ---- motion → wing beat (local player only, advanced once per frame) ----

    private static long lastNanos;
    private static float phase, amplitude = 0.22F, spread = 0.8F, speed = 1.6F;

    /**
     * This frame's pose. The world and the inventory preview both draw the player, so time only advances on the first
     * call of a frame (a call within 2 ms of the last reuses it).
     */
    public static DragonWingsModel.Pose pose(AbstractClientPlayerEntity player) {
        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 0.0F : (now - lastNanos) / 1.0E9F;
        if (lastNanos == 0L || dt > 0.002F) {
            lastNanos = now;
            advance(Math.min(dt, 0.1F), player);
        }
        return new DragonWingsModel.Pose(phase, amplitude, spread);
    }

    private static void advance(float dt, AbstractClientPlayerEntity p) {
        // targets: beat speed (rad/s), stroke amplitude, how far open
        float tSpeed = 1.6F, tAmp = 0.22F, tSpread = 0.8F;
        if (p != null) {
            Vec3d v = p.getVelocity();
            double horiz = Math.sqrt(v.x * v.x + v.z * v.z);
            boolean flying = p.getAbilities().flying;
            boolean airborne = !p.isOnGround() && !p.isTouchingWater() && !p.isClimbing() && !p.hasVehicle();
            if (p.isInSneakingPose()) {
                tSpeed = 1.0F; tAmp = 0.05F; tSpread = 0.0F;          // folded along the back
            } else if (flying) {
                tSpeed = 7.0F; tAmp = 1.0F; tSpread = 1.0F;
            } else if (airborne) {
                // jumping or falling: open wide; falling fast beats hard
                float fall = (float) MathHelper.clamp(-v.y / 0.6D, 0.0D, 1.0D);
                tSpeed = 5.0F + 3.0F * fall; tAmp = 0.75F + 0.25F * fall; tSpread = 1.0F;
            } else if (horiz > 0.01D) {
                float run = (float) MathHelper.clamp(horiz / 0.28D, 0.0D, 1.0D);   // ~0.28 blocks/tick ≈ sprinting
                tSpeed = 2.4F + 2.6F * run; tAmp = 0.3F + 0.25F * run; tSpread = 0.85F + 0.15F * run;
            }
        }
        // ease toward the targets so changes of state never snap
        float k = 1.0F - (float) Math.exp(-6.0F * dt);
        speed += (tSpeed - speed) * k;
        amplitude += (tAmp - amplitude) * k;
        spread += (tSpread - spread) * k;
        phase = (phase + speed * dt) % (MathHelper.PI * 2.0F * 64.0F);
    }
}
