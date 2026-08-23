package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.ui.GlassTooltip;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 4 (glass tooltip morph+crossfade) — the per-frame GHOST driver. 1.13.2
 * (Legacy Fabric) port of the 1.16.5 mixin of the same name.
 *
 * <p>Forge counterpart: {@code GlassTooltipHandler#onOverlayPost(
 * RenderGameOverlayEvent.Post, type == ALL)} -> {@link GlassTooltip#ghostPass()}.
 * Its sole job is to run {@code ghostPass} exactly once per frame so the panel
 * decays to 0 once tooltips stop, resetting the morph state machine.
 *
 * <h3>1.13.2 tier delta vs the 1.16.5 source</h3>
 * Fabric target {@code InGameHud.render}, descriptor {@code (F)V} in 1.13.2 (no
 * MatrixStack) — intermediary {@code method_9420}, named {@code render} — at
 * {@code @At("TAIL")}. The HUD renders once per frame before the current screen
 * draws its tooltips, so {@code ghostPass} sees the flag the previous frame's
 * {@code draw} set.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudTooltipGhostMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void s1mp1e$tooltipGhost(float tickDelta, CallbackInfo ci) {
        GlassTooltip.ghostPass();
    }
}
