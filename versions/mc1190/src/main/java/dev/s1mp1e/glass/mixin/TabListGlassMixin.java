package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The player tab list becomes liquid glass (G2). {@code PlayerListHud.render} paints its backgrounds with
 * {@code DrawableHelper.fill}: the tall structural panels (header / player-list / footer) and, once per row, the short
 * name stripe (height 8). Each fill is redirected: the tall panels become refracting glass plates (hotbar corner, R2)
 * with a grey readability scrim, and the short per-row stripe becomes a thinned scrim so the row striping stays visible
 * but gentle over the glass. The ping bars, hearts, skins and names ({@code drawTexture} / text) are untouched so they
 * stay readable. When the glass pipeline is unusable every fill falls back to the untouched vanilla draw.
 *
 * <p>Absolute coords: {@code PlayerListHud} draws with no matrix translate (the passed pose is at the GUI base), so the
 * raw-GL glass at the same coords lines up. The list plate is drawn (raw GL) before the row loop, so the names buffer
 * on top of it.
 */
@Mixin(PlayerListHud.class)
public abstract class TabListGlassMixin {

    /** Rects taller than this are structural panels; the short one is the per-row name stripe. */
    private static final int ROW_MAX_H = 10;
    /** Grey readability scrim under the names on the structural panels. */
    private static final int PANEL_SCRIM = 0x66101018;

    @Redirect(method = "render",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/gui/hud/PlayerListHud;fill(Lnet/minecraft/client/util/math/MatrixStack;IIIII)V"))
    private void s1mp1e$glassFill(MatrixStack matrices, int x0, int y0, int x1, int y1, int color) {
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) {
            DrawableHelper.fill(matrices, x0, y0, x1, y1, color);
            return;
        }
        if (y1 - y0 <= ROW_MAX_H) {
            // Per-row name stripe -> a softened scrim (keep row striping, gentle over glass).
            int a = Math.round((color >>> 24 & 0xFF) * 0.5F) & 0xFF;
            DrawableHelper.fill(matrices, x0, y0, x1, y1, (a << 24) | (color & 0xFFFFFF));
        } else {
            // Structural header / list / footer panel -> refracting glass plate + readability scrim.
            HudGlass.glassBoxHotbar(x0, y0, x1, y1, 0.85F);
            DrawableHelper.fill(matrices, x0, y0, x1, y1, PANEL_SCRIM);
        }
    }
}
