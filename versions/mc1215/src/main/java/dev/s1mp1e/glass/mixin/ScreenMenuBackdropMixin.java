package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.MenuBackdrop;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 5 (menu-backdrop blur) — the DRAW.
 *
 * <p>Forge counterpart: LiquidGlass26's {@code MenuBackdrop.draw()} replaced the
 * tiled dirt on world-less screens with the blurred title panorama; the Forge line
 * called it where the dirt would be drawn. In 1.17.1 (as 1.16.5) every world-less
 * screen paints its background via {@code Screen.renderBackgroundTexture(int)} —
 * the tiled {@code OPTIONS_BACKGROUND_TEXTURE} (dirt).
 *
 * <p>Fabric target: {@code Screen.renderBackgroundTexture(int)} — intermediary
 * {@code method_25434}, descriptor {@code (I)V} (verified against yarn
 * 1.17.1+build.65 — identical to 1.16.5) — at {@code @At("HEAD")},
 * {@code cancellable = true}. {@link MenuBackdrop#draw()} returns {@code true} once
 * a panorama frame has been captured and the blur program is usable, and we cancel
 * the vanilla dirt; while it returns {@code false} the vanilla dirt draws — exactly
 * the helper's "false -> caller draws the dirt" contract.
 *
 * <p><b>1.17.1 core-profile status: TRIGGER wired, BACKDROP-BLUR does not render
 * yet.</b> {@link MenuBackdrop} is STUBBED on 1.17.1 (its immediate-mode
 * full-screen quad + fixed-function enables are illegal under the OpenGL 3.2 core
 * profile), so {@link MenuBackdrop#draw()} always returns {@code false} and the
 * vanilla dirt is left intact — no visual change, no GL error. Visual-TODO: pending
 * a core-profile VAO/BLUR-program rewrite of MenuBackdrop (CORE_PROFILE_SPEC §7).
 * This injection stays because it is harmless and becomes live the instant
 * MenuBackdrop is un-stubbed.
 */
@Mixin(Screen.class)
public abstract class ScreenMenuBackdropMixin {

    // 1.21: the old Screen.renderBackgroundTexture(DrawContext) is gone —
    // renderBackgroundTexture is now a STATIC blit helper
    // (DrawContext,Identifier,int,int,float,float,int,int). The per-screen background
    // entry point is renderBackground(DrawContext,int,int,float) (method_25420),
    // which is where the world-less panorama/dirt is painted. Retarget HEAD there and
    // cancel it when MenuBackdrop draws the blurred panorama instead. (MenuBackdrop is
    // STUBBED -> draw() returns false -> this is a resolving no-op until un-stubbed.)
    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$menuBackdrop(DrawContext context, int mouseX, int mouseY,
                                     float delta, CallbackInfo ci) {
        if (MenuBackdrop.draw()) {
            ci.cancel();
        }
    }
}
