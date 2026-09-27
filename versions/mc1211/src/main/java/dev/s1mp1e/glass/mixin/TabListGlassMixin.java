package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.PlayerListHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
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

    @Redirect(
        method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V")
    )
    private void s1mp1e$glassFill(DrawContext ctx, int x0, int y0, int x1, int y1, int color) {
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) {
            ctx.fill(x0, y0, x1, y1, color);
            return;
        }
        if (y1 - y0 <= ROW_MAX_H) {
            // Per-row name stripe -> a softened plain scrim (keep row striping, gentle over glass).
            int a = Math.round((color >>> 24 & 0xFF) * 0.5F) & 0xFF;
            ctx.fill(x0, y0, x1, y1, a << 24 | color & 0xFFFFFF);
        } else {
            // Structural header / list / footer panel -> refracting glass plate + readability scrim.
            HudGlass.glassBoxCtx(ctx, x0, y0, x1, y1, 1.0F);
            ctx.fill(x0, y0, x1, y1, PANEL_SCRIM);
        }
    }
}
