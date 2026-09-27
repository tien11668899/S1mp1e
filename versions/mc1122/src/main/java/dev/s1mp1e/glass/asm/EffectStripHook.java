package dev.s1mp1e.glass.asm;

import dev.s1mp1e.glass.hook.GlassEffectHandler;
import net.minecraft.client.renderer.InventoryEffectRenderer;

/**
 * Called from the head of
 * {@code InventoryEffectRenderer.drawActivePotionEffects()} by the coremod.
 *
 * <p>Feature (F) draws the whole effect display (glass strip + icons + names +
 * durations) earlier in the frame, from
 * {@link GlassEffectHandler#onBackgroundDrawn}, so the container tooltip stays on
 * top of it (rule R1). When that happened this frame, vanilla's own box drawer
 * must not run — this returns {@code true} and the spliced early-return skips it.
 *
 * <p>When the glass pipeline is down (no shader / no backdrop) the handler draws
 * nothing and this returns {@code false}, so vanilla's boxes render as usual.
 */
public final class EffectStripHook {

    private EffectStripHook() {}

    public static boolean handled(InventoryEffectRenderer screen) {
        try {
            return GlassEffectHandler.handledThisFrame(screen);
        } catch (Throwable t) {
            return false;
        }
    }
}
