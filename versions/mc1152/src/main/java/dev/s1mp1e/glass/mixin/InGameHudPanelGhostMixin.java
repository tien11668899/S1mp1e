package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.PanelGhost;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 2 (container glass) — the close-ghost DRAW.
 *
 * <p>Forge counterpart: {@code GlassContainerHandler#onOverlayPost(
 * RenderGameOverlayEvent.Post, type == ALL)} -> {@link PanelGhost#drawGhosts()}.
 * Once the container closes, the still-running HUD pass keeps painting the
 * fading panel rect(s) over the world for {@link PanelGhost#FADE_MS} ms — the one
 * moment the glass panel is visible unobstructed by the vanilla GUI texture.
 *
 * <p>Fabric target: {@code InGameHud.render(float)} ({@code method_1753},
 * descriptor {@code (F)V}) at {@code @At("TAIL")}. {@link PanelGhost#drawGhosts()}
 * internally gates on the pipeline being usable and a backdrop being present, so it
 * is a no-op unless {@link InGameHudMixin} (or any grab site) has captured this
 * frame.
 *
 * <p><b>1.15.2 delta vs 1.16.5:</b> {@code InGameHud.render} is {@code (F)V}, so the
 * injected signature drops the {@code MatrixStack} first arg the 1.16.5 source had.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudPanelGhostMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void s1mp1e$drawPanelGhosts(float tickDelta, CallbackInfo ci) {
        PanelGhost.drawGhosts();
    }
}
