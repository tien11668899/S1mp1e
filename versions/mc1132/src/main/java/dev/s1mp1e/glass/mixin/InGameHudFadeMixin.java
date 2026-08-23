package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.ScreenFade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 1 (screen-change dissolve) — the DRAW half, in-world branch. 1.13.2 (Legacy
 * Fabric) port of the 1.16.5 mixin of the same name.
 *
 * <p>Forge counterpart: {@code GlassScreenFadeHandler#onOverlayPost(
 * RenderGameOverlayEvent.Post)} guarded by {@code type == ALL &&
 * currentScreen == null} — back in the world (no screen), the dissolve is drawn
 * above the HUD and the finished frame snapshotted. Same order and same guard here.
 *
 * <p>In 1.13.2 the dropped hotbar mixin ({@code InGameHudMixin}, whose item render is
 * unmapped) no longer carries the in-world fade tail, so this standalone mixin is the
 * sole in-world branch — no double-draw (unlike 1.16.5, which registered both).
 *
 * <h3>1.13.2 tier delta vs the 1.16.5 source</h3>
 * Fabric target {@code InGameHud.render}. In 1.13.2 (pre-MatrixStack) its descriptor
 * is {@code (F)V} — just {@code tickDelta} — intermediary {@code method_9420}, named
 * {@code render}, at {@code @At("TAIL")}. {@code ScreenFade} keeps its own captured
 * texture (it does not depend on {@code SceneCapture}), so it works even though the
 * HUD-pass grab site was dropped with the hotbar mixin.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudFadeMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void s1mp1e$fadeDrawHud(float tickDelta, CallbackInfo ci) {
        if (MinecraftClient.getInstance().currentScreen != null) return;
        ScreenFade.draw();
        ScreenFade.captureFrame();
    }
}
