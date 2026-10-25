package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Proof-of-render for the 1.16.5 line: grab the panorama at the title screen's
 * render HEAD, then draw a single frosted-glass panel over it at TAIL. Confirms
 * SceneCapture + GlassRenderer + all four GLSL-120 programs run in the 1.16.5
 * GL context. This is also the seed of the glass main-menu work.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$grab(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        SceneCapture.grab();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void s1mp1e$panel(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!SceneCapture.hasBackdrop()) return;
        int w = mc.getWindow().getScaledWidth();
        int h = mc.getWindow().getScaledHeight();
        // a centred rounded glass panel — refraction samples the panorama behind
        int pw = 160, ph = 90;
        int x0 = (w - pw) / 2, y0 = (h - ph) / 2 + 10;
        GlassRenderer.panel(x0, y0, x0 + pw, y0 + ph, 1.0f);
    }
}
