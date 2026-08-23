package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.ScreenFade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 1 (screen-change dissolve) — the DRAW half, in-world branch.
 *
 * <p>Forge counterpart: {@code GlassScreenFadeHandler#onOverlayPost(
 * RenderGameOverlayEvent.Post)} guarded by {@code type == ALL &&
 * currentScreen == null} — when the player is back in the world (no screen), the
 * dissolve is drawn above the HUD and the finished frame snapshotted. Same order
 * and same guard here.
 *
 * <p>Fabric target: {@code InGameHud.render(MatrixStack, float)} at
 * {@code @At("TAIL")}; intermediary {@code method_1753}, descriptor
 * {@code (Lnet/minecraft/client/util/math/MatrixStack;F)V} (verified against
 * yarn 1.17.1+build.65 — identical to 1.16.5). {@code currentScreen == null}
 * keeps this branch and {@link ScreenFadeMixin}'s screen branch mutually
 * exclusive — exactly the two Forge Post handlers.
 *
 * <p><b>1.17.1 core-profile status: TRIGGER wired, DRAW is a no-op.</b> The suite
 * is byte-for-byte the 1.16.5 line, but {@link ScreenFade} is STUBBED on 1.17.1
 * (its immediate-mode blit is illegal under the OpenGL 3.2 core profile), so
 * {@link ScreenFade#draw()} / {@link ScreenFade#captureFrame()} do nothing and a
 * screen change is a hard cut. Visual-TODO: pending a core-profile VAO rewrite of
 * ScreenFade (CORE_PROFILE_SPEC §7). This injection stays because it is harmless
 * (two no-op calls) and becomes live the instant ScreenFade is un-stubbed.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudFadeMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void s1mp1e$fadeDrawHud(DrawContext context, float tickDelta, CallbackInfo ci) {
        if (MinecraftClient.getInstance().currentScreen != null) return;
        ScreenFade.draw();
        ScreenFade.captureFrame();
    }
}
