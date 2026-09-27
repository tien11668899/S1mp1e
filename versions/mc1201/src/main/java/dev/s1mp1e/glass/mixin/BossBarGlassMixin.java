package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.entity.boss.BossBar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Boss bar = the S1mp1e slider look (G3): the health fill becomes the config slider's blue
 * ({@code 0x0A84FF}) as a capsule with fully round ends; under it a liquid-glass capsule of the same
 * shape {@link #MARGIN} px larger on every side (concentric), with TRUE semicircle ends.
 *
 * <p>1.20.1 path (verified by decompile): unlike 1.21.1's sprite-array API, 1.20.1's
 * {@code BossBarHud} draws from a single {@code bars.png} via
 * {@code renderBossBar(DrawContext, int x, int y, BossBar, int width, int height)} — called TWICE per
 * bar by the 4-arg overload: first with {@code (width = 182, height = 0)} (the full-width background
 * row of the atlas) and then, if progress &gt; 0, with {@code (width = healthPx, height = 5)} (the
 * progress row). We inject at HEAD and distinguish by the {@code height} argument: {@code height == 0}
 * becomes the glass capsule, the progress call becomes the blue fill on top, and each vanilla draw is
 * cancelled. Multiple stacked bars each get their own call, so their capsules stay independent. The
 * {@code height} param is only the atlas row selector; the on-screen bar is always {@link #BAR_H} px
 * tall.
 *
 * <p>The normal glass shader caps its corner at a quarter of the short side, so a true semicircle end
 * needs the full-capsule glass program (LENS, corner 1.0) — {@link HudGlass#capsuleCtx}. Every surface
 * is frame-primary and refracts the world backdrop grabbed at {@code InGameHud.render} HEAD (no
 * mid-HUD grab -> no self-sampling, R4).
 */
@Mixin(BossBarHud.class)
public abstract class BossBarGlassMixin {

    /** The config slider's fill blue (iOS systemBlue), opaque. */
    private static final int FILL_ARGB = 0xFF0A84FF;
    /** Glass capsule reaches this far past the fill on every side (concentric). */
    private static final int MARGIN = 2;
    /** On-screen boss-bar pixel height (vanilla draws the bar 5 px tall). */
    private static final int BAR_H = 5;
    /** LENS opacity (26.2 0xE6) and frost (26.2 0x80 = 0.5). */
    private static final float GLASS_OPACITY = 0xE6 / 255f;
    private static final float GLASS_FROST   = 0x80 / 255f;

    @Inject(
        method = "renderBossBar(Lnet/minecraft/client/gui/DrawContext;IILnet/minecraft/entity/boss/BossBar;II)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void s1mp1e$glassBar(DrawContext ctx, int x, int y, BossBar bossBar, int width, int height,
                                 CallbackInfo ci) {
        if (!GlassProgram.ensureReady()) return;   // pipeline down -> vanilla boss bar
        if (height == 0) {
            // Background row (full 182 px width) -> the glass capsule under-layer.
            HudGlass.capsuleCtx(ctx, x - MARGIN, y - MARGIN, x + width + MARGIN, y + BAR_H + MARGIN,
                                GLASS_OPACITY, GLASS_FROST);
        } else if (width > 0) {
            // Progress row (health width) -> the blue capsule fill with round ends, on top.
            HudGlass.roundFillCtx(ctx, x, y, x + width, y + BAR_H, BAR_H * 0.5f, FILL_ARGB);
        }
        ci.cancel();
    }
}
