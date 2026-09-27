package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.ui.GlassTooltip;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 4 (glass tooltip morph+crossfade) — the per-frame GHOST driver.
 *
 * <p>Forge counterpart: {@code GlassTooltipHandler#onOverlayPost(RenderGameOverlayEvent.Post,
 * type == ALL)} → {@link GlassTooltip#ghostPass()}. Its sole job is to run {@code ghostPass}
 * exactly once per frame so the panel decays to 0 once tooltips stop, resetting the morph state
 * machine — without it the next tooltip would morph in from the previous one's stale box.
 *
 * <p>Fabric target: {@code InGameHud.render(float)} ({@code render(F)V}, class_329) at
 * {@code @At("TAIL")}; 1.14.4 predates MatrixStack, so the handler takes only {@code tickDelta}.
 * The HUD renders once per frame BEFORE the current screen draws its tooltips — the same ordering
 * the Forge overlay Post had relative to the screen, which is why {@code ghostPass} checks the flag
 * the previous frame's {@code draw} set.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudTooltipGhostMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void s1mp1e$tooltipGhost(float tickDelta, CallbackInfo ci) {
        try {
            GlassTooltip.ghostPass();
        } catch (Throwable ignored) {
            // a failed ghost pass must never take the HUD down with it
        }
    }
}
