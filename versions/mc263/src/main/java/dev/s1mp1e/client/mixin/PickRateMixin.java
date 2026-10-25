package dev.s1mp1e.client.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.x picks the crosshair target (a block ray plus an entity search along the view) on every rendered frame. At
 * 900+ FPS that was 14 % of the render thread (JFR, 26.3 Vulkan, 4K) for a result no screen can show faster than its
 * refresh rate. Pick at most every {@value #INTERVAL_NS} ns (300 per second, above a 240 Hz display): the target the
 * outline, the crosshair and a click use is then at most 3.3 ms old. A new level or no target yet picks at once.
 * Experiment, off by default: interleaved runs showed no gain (887/890 with it, 944/925 without), the frame rate was
 * held by the display path (FastSync), not this. {@code S1MP1E_PICK_LIMIT=1} turns it on.
 */
@Mixin(Minecraft.class)
public abstract class PickRateMixin {
    @Unique private static final long INTERVAL_NS = 3_333_333L;
    @Unique private static final boolean EVERY_FRAME = !"1".equals(System.getenv("S1MP1E_PICK_LIMIT"));
    @Unique private static long s1mp1e$lastPick;
    @Unique private static Object s1mp1e$pickLevel;

    @Inject(method = "pick(F)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void s1mp1e$pickAtDisplayRate(float partialTick, CallbackInfo ci) {
        if (EVERY_FRAME) return;
        Minecraft mc = (Minecraft) (Object) this;
        long now = System.nanoTime();
        if (mc.level == s1mp1e$pickLevel && mc.hitResult != null && now - s1mp1e$lastPick < INTERVAL_NS) {
            ci.cancel();
            return;
        }
        s1mp1e$lastPick = now;
        s1mp1e$pickLevel = mc.level;
    }
}
