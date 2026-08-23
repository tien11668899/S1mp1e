package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.ui.GlassTooltip;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 4 (glass tooltip morph+crossfade) — the per-frame GHOST driver.
 *
 * <p>Forge counterpart: {@code GlassTooltipHandler#onOverlayPost(
 * RenderGameOverlayEvent.Post, type == ALL)} -> {@link GlassTooltip#ghostPass()}.
 * Its sole job is to run {@code ghostPass} exactly once per frame so the panel
 * decays to 0 once tooltips stop, resetting the morph state machine.
 *
 * <p>Fabric target: {@code InGameHud.render(MatrixStack, float)} ({@code method_1753},
 * {@code (Lnet/minecraft/client/util/math/MatrixStack;F)V}; verified against yarn
 * 1.17.1+build.65) at {@code @At("TAIL")}. The HUD renders once per frame BEFORE
 * the current screen draws its tooltips — the same ordering the Forge overlay Post
 * had relative to the screen, which is why {@code ghostPass} checks the flag the
 * previous frame's {@code draw} set.
 *
 * <p><b>1.17.1 status: FULLY FUNCTIONAL.</b> {@link GlassTooltip} is core-legal
 * (frosted panels via {@link dev.s1mp1e.glass.render.GlassRenderer}, text via
 * {@code TextRenderer}, depth toggled through {@code RenderSystem}) and is ported
 * verbatim into this line's {@code ui} package — no stub.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudTooltipGhostMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void s1mp1e$tooltipGhost(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        GlassTooltip.ghostPass();
    }
}
