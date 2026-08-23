package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.PanelGhost;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.util.math.MatrixStack;
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
 * <p>Fabric target: {@code InGameHud.render(MatrixStack, float)} ({@code method_1753},
 * {@code (Lnet/minecraft/client/util/math/MatrixStack;F)V}) at {@code @At("TAIL")}.
 * {@link PanelGhost#drawGhosts()} internally gates on the pipeline being usable and
 * a backdrop being present, so it is a no-op unless {@link InGameHudMixin} (or any
 * grab site) has captured this frame — the same dependency the Forge port had on
 * {@code GlassHudHandler}'s {@code Pre(ALL)} grab.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudPanelGhostMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void s1mp1e$drawPanelGhosts(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        PanelGhost.drawGhosts();
    }
}
