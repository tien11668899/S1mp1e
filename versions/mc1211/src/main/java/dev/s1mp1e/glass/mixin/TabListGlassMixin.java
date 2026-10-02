package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.TabListFade;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.PlayerListHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The player tab list becomes liquid glass (G2).
 *
 * <p>1.21.1 path (verified with javap): {@code PlayerListHud.render} paints its backgrounds with four
 * {@code context.fill} calls in insertion order — header, the player-list panel, the per-row name
 * stripe (once per row, height ~9), footer. Each fill is redirected: the tall structural panels become
 * a refracting glass plate (raw-GL, drawn immediately) with a grey readability scrim on top (deferred
 * {@code fill}, so it lands over the glass and under the names), while the short per-row stripe becomes
 * a thinned scrim so the row striping stays gentle over the glass. The ping bars, hearts and names
 * (blitSprite / text) are deferred and stay untouched and on top. Falls back to the untouched vanilla
 * fill when the glass pipeline is not usable.
 *
 * <p>The world backdrop grabbed at {@code InGameHud.render} HEAD is what the plates refract (no mid-HUD
 * grab -> no self-sampling, R4).
 */
@Mixin(PlayerListHud.class)
public abstract class TabListGlassMixin {

    /** Rects taller than this are structural panels; the short one is the per-row name stripe. */
    private static final int ROW_MAX_H = 10;
    /** Grey readability scrim under the names on the structural panels. */
    private static final int PANEL_SCRIM = 0x66101018;

    /**
     * <b>Appear / disappear fade (G2).</b> Vanilla pops the whole list in on key-press and out on release. Its companion
     * {@code TabListGateMixin} keeps this overlay rendering through a ~150 ms fade-out and drives {@link TabListFade#alpha()};
     * the glass plates, scrims, names and header/footer all take that one alpha, so the panels + text fade as a whole in both
     * directions. (Player heads and ping icons are drawn through helper classes not reachable from this method, so they wink
     * with the gate at the very end rather than fading — a minor edge artifact.)
     */
    private static int s1mp1e$fadeArgb(int argb, float f) {
        if (f >= 1F) return argb;
        int a = Math.round((argb >>> 24 & 0xFF) * f) & 0xFF;
        return a << 24 | argb & 0xFFFFFF;
    }

    @Redirect(
        method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V")
    )
    private void s1mp1e$glassFill(DrawContext ctx, int x0, int y0, int x1, int y1, int color) {
        float f = TabListFade.alpha();
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) {
            ctx.fill(x0, y0, x1, y1, s1mp1e$fadeArgb(color, f));
            return;
        }
        if (f <= 0.004F) return;
        if (y1 - y0 <= ROW_MAX_H) {
            // Per-row name stripe -> a softened plain scrim (keep row striping, gentle over glass).
            int a = Math.round((color >>> 24 & 0xFF) * 0.5F * f) & 0xFF;
            ctx.fill(x0, y0, x1, y1, a << 24 | color & 0xFFFFFF);
        } else {
            // Structural header / list / footer panel -> refracting glass plate + readability scrim.
            HudGlass.glassBoxCtx(ctx, x0, y0, x1, y1, f);
            ctx.fill(x0, y0, x1, y1, s1mp1e$fadeArgb(PANEL_SCRIM, f));
        }
    }

    /** Player names + objective scores follow the fade. */
    @ModifyArg(
        method = "render",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/OrderedText;III)I"),
        index = 4
    )
    private int s1mp1e$fadeOrdered(int color) {
        return s1mp1e$fadeArgb(color, TabListFade.alpha());
    }

    /** Header / footer lines follow the fade. */
    @ModifyArg(
        method = "render",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)I"),
        index = 4
    )
    private int s1mp1e$fadeText(int color) {
        return s1mp1e$fadeArgb(color, TabListFade.alpha());
    }
}
