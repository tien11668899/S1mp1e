package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.BrandIntro;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * World-loading screen (the singleplayer chunk-load screen). Vanilla draws the coloured chunk grid + a "loading
 * terrain" percentage on the dirt/panorama background. Here it is replaced entirely by the seamless brand loop
 * ("S1mp1e" melts into the mark and back, forever) on pure black — it runs for exactly as long as the world takes,
 * and the client closes the screen itself once the world is ready.
 *
 * <p>1.21.1 port of LiquidGlass26's {@code LevelLoadingScreenGlassMixin} world-entry branch (mode 2). 26.2 cancelled
 * the deferred {@code extractRenderState}; here we cancel the immediate {@code render(MatrixStack, ...)} at HEAD (so
 * the vanilla background, chunk grid and text never draw) and paint black + the loop instead. If the intro pipeline
 * isn't ready we do nothing and vanilla shows as normal.
 */
@Mixin(net.minecraft.client.gui.screen.LevelLoadingScreen.class)   // 1.20.1 package
public abstract class LevelLoadingScreenGlassMixin {

    @Unique private long s1mp1e$introStart;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$loop(MatrixStack context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!BrandIntro.ready(BrandIntro.MODE_LOOP)) {
            return;   // pipeline/strip not up yet — leave vanilla alone
        }
        long now = System.nanoTime();   // ns clock, sampled once per frame: ms steps judder at high refresh rates
        if (this.s1mp1e$introStart == 0L) {
            this.s1mp1e$introStart = now;
        }
        net.minecraft.client.util.Window s1mp1e$win = net.minecraft.client.MinecraftClient.getInstance().getWindow();
        int w = s1mp1e$win.getScaledWidth();
        int h = s1mp1e$win.getScaledHeight();
        net.minecraft.client.gui.DrawableHelper.fill(context, 0, 0, w, h, 0xFF000000);
        dev.s1mp1e.glass.render.GuiFlush.flush();   // flush the black under the immediate-GL loop
        BrandIntro.draw(context, (float) ((now - this.s1mp1e$introStart) / 1.0E9), BrandIntro.MODE_LOOP, 1.0F);
        ci.cancel();
    }
}
