package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.PanelGhost;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 2 (container glass) — the close-ghost DRAW. 1.13.2 port of the mc1144 mixin.
 *
 * <p>Forge counterpart: {@code GlassContainerHandler#onOverlayPost(
 * RenderGameOverlayEvent.Post, type == ALL)} -> {@link PanelGhost#drawGhosts()}.
 * Once the container closes, the still-running HUD pass keeps painting the fading
 * panel rect(s) over the world for {@link PanelGhost#FADE_MS} ms — the one moment the
 * glass panel is visible unobstructed by the vanilla GUI texture.
 *
 * <p>Target: {@code InGameHud.render(F)V} (named; exactly one return) at
 * {@code @At("TAIL")}. The draw was skipped on 1.13.2 while the hotbar mixin had no HUD
 * grab; {@code InGameHudMixin} grabs the scene at {@code render} HEAD again, so the ghost
 * has its backdrop. {@link PanelGhost#drawGhosts()} gates internally (no-op once the
 * fade has finished; {@code GlassRenderer.panel} needs a usable pipeline and a backdrop).
 * Wrapped so a failure never breaks the HUD.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudPanelGhostMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void s1mp1e$drawPanelGhosts(float tickDelta, CallbackInfo ci) {
        try {
            PanelGhost.drawGhosts();
        } catch (Throwable ignored) {
            // a failed ghost simply isn't drawn
        }
    }
}
