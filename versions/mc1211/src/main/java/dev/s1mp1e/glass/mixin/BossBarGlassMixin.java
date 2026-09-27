package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Boss bar = the S1mp1e slider look (G3): the health fill becomes the config slider's blue
 * ({@code 0x0A84FF}) as a capsule with fully round ends; under it a liquid-glass capsule of the same
 * shape {@link #MARGIN} px larger on every side (concentric), with TRUE semicircle ends.
 *
 * <p>1.21.1 path (verified with javap): {@code BossBarHud.renderBossBar(DrawContext, int x, int y,
 * BossBar, int width, Identifier[] a, Identifier[] b)} is called twice per bar by the 4-arg overload —
 * first with the BACKGROUND texture arrays (full width) and then, if progress &gt; 0, with the PROGRESS
 * arrays (health width). We inject at HEAD and distinguish by the first array's identity: the
 * background call becomes the glass capsule, the progress call becomes the blue fill on top, and each
 * vanilla draw is cancelled. Multiple stacked bars each get their own call, so their capsules stay
 * independent.
 *
 * <p>The normal glass shader caps its corner at a quarter of the short side, so a true semicircle end
 * needs the full-capsule glass program (LENS, corner 1.0) — {@link HudGlass#capsuleCtx}. Every surface
 * is frame-primary and refracts the world backdrop grabbed at {@code InGameHud.render} HEAD (no
 * mid-HUD grab -> no self-sampling, R4).
 */
@Mixin(BossBarHud.class)
public abstract class BossBarGlassMixin {

    @Shadow @Final private static Identifier[] BACKGROUND_TEXTURES;
    @Shadow @Final private static Identifier[] NOTCHED_BACKGROUND_TEXTURES;
    @Shadow @Final private static int HEIGHT;

    /** The config slider's fill blue (iOS systemBlue), opaque. */
    private static final int FILL_ARGB = 0xFF0A84FF;
    /** Glass capsule reaches this far past the fill on every side (concentric). */
    private static final int MARGIN = 2;
    /** LENS opacity (26.2 0xE6) and frost (26.2 0x80 = 0.5). */
    private static final float GLASS_OPACITY = 0xE6 / 255f;
    private static final float GLASS_FROST   = 0x80 / 255f;

    @Inject(
        method = "renderBossBar(Lnet/minecraft/client/gui/DrawContext;IILnet/minecraft/entity/boss/BossBar;I[Lnet/minecraft/util/Identifier;[Lnet/minecraft/util/Identifier;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void s1mp1e$glassBar(DrawContext ctx, int x, int y, BossBar bar, int width,
                                 Identifier[] a, Identifier[] b, CallbackInfo ci) {
        if (!GlassProgram.ensureReady()) return;   // pipeline down -> vanilla boss bar
        int h = HEIGHT;
        boolean background = (a == BACKGROUND_TEXTURES || a == NOTCHED_BACKGROUND_TEXTURES);
        if (background) {
            HudGlass.capsuleCtx(ctx, x - MARGIN, y - MARGIN, x + width + MARGIN, y + h + MARGIN,
                                GLASS_OPACITY, GLASS_FROST);
        } else if (width > 0) {
            HudGlass.roundFillCtx(ctx, x, y, x + width, y + h, h * 0.5f, FILL_ARGB);
        }
        ci.cancel();
    }
}
