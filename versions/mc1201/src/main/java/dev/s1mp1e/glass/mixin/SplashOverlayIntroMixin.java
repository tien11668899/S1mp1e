package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.BrandIntro;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.resource.ResourceReload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.IntSupplier;

/**
 * Replaces the vanilla red "Mojang Studios" boot loading screen ({@code SplashOverlay}) with the S1mp1e brand intro.
 *
 * <p>1.20.1 port (the 1.20.1 {@code SplashOverlay.render} is line for line the 1.21.1 one: same {@code BRAND_ARGB}
 * supplier, logo {@code drawTexture} overload, {@code renderProgressBar} and {@code reloadCompleteTime}) of
 * LiquidGlass26's {@code LoadingOverlayIntroMixin}. 26.2 hooked the deferred
 * {@code LoadingOverlay.extractRenderState} / {@code isReadyToFadeOut}; this line hooks the immediate
 * {@code SplashOverlay.render(DrawContext, int, int, float)}, whose equivalent of 26.2's {@code fadeOutStart == -1}
 * "still holding" state is {@code reloadCompleteTime == -1} (vanilla only starts its fade-out once the reload is
 * complete). Behaviour is identical to 26.2:
 * <ul>
 *   <li><b>Hold</b> ({@code reloadCompleteTime == -1}): we draw the intro on solid black and cancel vanilla, and keep
 *       the overlay up until the intro has SETTLED <em>and</em> the resource reload is complete — so a fast reload
 *       can't cut the intro short. (26.2 did this by overriding {@code isReadyToFadeOut}; here we simply keep
 *       cancelling + not letting the reload-complete transition run until we're ready.)</li>
 *   <li><b>Fade-out</b>: once ready, we fall through to (a tamed) vanilla — it renders the title behind and drops the
 *       overlay for us — but we {@linkplain #s1mp1e$brandBlack force its brand veil/clear to black} (the Mojang red the
 *       user asked to be rid of), {@linkplain #s1mp1e$noLogo suppress the Mojang logo} and {@linkplain #s1mp1e$noBar
 *       progress bar}, and {@linkplain #s1mp1e$introFade keep the settled mark on top}, fading it out WITH vanilla's
 *       veil so the mark melts into the title with no pop.</li>
 * </ul>
 * When the pipeline / strip is unavailable we do nothing and vanilla shows as normal.
 */
@Mixin(net.minecraft.client.gui.screen.SplashOverlay.class)
public abstract class SplashOverlayIntroMixin {

    /** {@link net.minecraft.util.Util#getMeasuringTimeMs()} the fade-out started, or {@code -1} while holding. */
    @Shadow private long reloadCompleteTime;
    /** The resource reload this overlay is waiting on; {@code isComplete()} gates the hold hand-off. */
    @Shadow @Final private ResourceReload reload;

    /** {@code System.nanoTime()} of the first frame the intro was drawn; 0 until then. The intro clock runs on
     *  nanoseconds: millisecond time steps visibly judder at high refresh rates (a 144 Hz frame is 6.94 ms). */
    @Unique private long s1mp1e$introStart;
    /** This overlay's intro mode: the very first overlay (boot) plays the full intro; any later reload uses the short cut. */
    @Unique private boolean s1mp1e$short;
    /** Set once the first (boot) overlay has claimed the full intro, so subsequent reloads take the short cut. */
    @Unique private static boolean s1mp1e$bootSeen;

    @Unique
    private float s1mp1e$elapsed(long nowNs) {
        return this.s1mp1e$introStart == 0L ? 0.0F : (float) ((nowNs - this.s1mp1e$introStart) / 1.0E9);
    }

    @Unique
    private float s1mp1e$hold() {
        return this.s1mp1e$short ? BrandIntro.HOLD_SHORT : BrandIntro.HOLD_FULL;
    }

    /** Draw the intro on black while holding; hand off to (a tamed) vanilla for the fade-out. */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$introHead(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        dev.s1mp1e.glass.render.ContainerExtras.markReload();   // textures may change: rebuild the keyed copies after
        if (!BrandIntro.ready()) {
            return;   // pipeline/strip not up yet — leave vanilla alone
        }
        long now = System.nanoTime();
        if (this.s1mp1e$introStart == 0L) {
            this.s1mp1e$introStart = now;
            this.s1mp1e$short = s1mp1e$bootSeen;
            s1mp1e$bootSeen = true;
        }
        if (this.reloadCompleteTime == -1L) {
            // Still holding. Only let vanilla begin its fade-out once the intro has settled AND the reload is complete
            // — otherwise keep the overlay up (draw the intro on black, cancel vanilla). A fast reload can't cut it.
            boolean settled = this.s1mp1e$elapsed(now) >= this.s1mp1e$hold();
            if (!settled || !this.reload.isComplete()) {
                int w = context.getScaledWindowWidth();
                int h = context.getScaledWindowHeight();
                context.fill(0, 0, w, h, 0xFF000000);
                context.draw();   // flush the black under the immediate-GL intro
                BrandIntro.draw(context, this.s1mp1e$elapsed(now), this.s1mp1e$short, 1.0F);
                ci.cancel();
            }
            // else: fall through — vanilla begins the fade this frame (tamed to black, no logo/bar); the RETURN
            //       inject draws the mark on top.
        }
        // else: fade-out in progress — fall through, the RETURN inject handles the mark
    }

    /**
     * During the fade-out, keep the settled mark on top of the black veil, fading with it. Vanilla holds an opaque veil
     * for the first second after {@code reloadCompleteTime}, then reveals the screen behind over the next second — the
     * mark tracks that reveal so it melts into the title with no pop.
     */
    @Inject(method = "render", at = @At("RETURN"))
    private void s1mp1e$introFade(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!BrandIntro.ready() || this.reloadCompleteTime == -1L) {
            return;
        }
        float p = (net.minecraft.util.Util.getMeasuringTimeMs() - this.reloadCompleteTime) / 1000.0F; // vanilla's fade progress
        float fo = 1.0F - MathHelper.clamp(p - 1.0F, 0.0F, 1.0F);   // 1 for the first second, then 1 -> 0
        context.draw();   // flush vanilla's (tamed-black) veil + title so the mark composites on top
        BrandIntro.draw(context, this.s1mp1e$elapsed(System.nanoTime()), this.s1mp1e$short, fo);
    }

    /**
     * Every brand colour the splash paints — the fade veils and the {@code _clearColor} — reads the Mojang
     * {@code BRAND_ARGB} supplier; return black instead so the whole thing (boot hold and fade-out) stays black, as
     * requested. One redirect covers all three call sites.
     */
    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Ljava/util/function/IntSupplier;getAsInt()I"))
    private int s1mp1e$brandBlack(IntSupplier brand) {
        return BrandIntro.ready() ? 0xFF000000 : brand.getAsInt();
    }

    /** Suppress the Mojang logo blits (both halves share this drawTexture overload). */
    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Lnet/minecraft/util/Identifier;IIIIFFIIII)V"))
    private void s1mp1e$noLogo(DrawContext context, Identifier tex, int x, int y, int width, int height,
                               float u, float v, int regionW, int regionH, int textureW, int textureH) {
        if (!BrandIntro.ready()) {
            context.drawTexture(tex, x, y, width, height, u, v, regionW, regionH, textureW, textureH);
        }
    }

    /** Drop the vanilla progress bar during the intro (the intro is its own progress). */
    @Inject(method = "renderProgressBar", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$noBar(DrawContext context, int x0, int y0, int x1, int y1, float progress, CallbackInfo ci) {
        if (BrandIntro.ready()) {
            ci.cancel();
        }
    }
}
