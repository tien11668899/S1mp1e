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
 * The player tab list becomes liquid glass (G2). 1.16.5's {@code PlayerListHud.render} paints its backgrounds with
 * {@code PlayerListHud.fill}: the tall structural panels (header {@code Integer.MIN_VALUE} + the whole player-list
 * {@code Integer.MIN_VALUE}) and, once per row inside the loop, the short name stripe (height exactly 8,
 * {@code getTextBackgroundColor}). Each fill is redirected: the tall panels become refracting glass plates (hotbar
 * corner, R2) with a grey readability scrim, and the short per-row stripe becomes a thinned scrim so the row striping
 * stays visible but gentle over the glass. Ping bars, hearts, skins and names ({@code drawTexture}/text) are untouched.
 * When the glass pipeline is unusable every fill falls back to the untouched vanilla draw.
 *
 * <p>Absolute coords: {@code PlayerListHud} draws at the HUD base with no GL model-view transform, so the glass at the
 * same coords lines up. The list plate is drawn (raw GL) before the row loop, so the names buffer on top of it. The
 * height split uses {@code <= 8} for the row stripe (its height is exactly 8) so even a single-line header / single-row
 * list — height {@code k*9+1 >= 10} — is still glassed, not misread as a stripe.
 */
@Mixin(PlayerListHud.class)
public abstract class TabListGlassMixin {

    /** The per-row name stripe is exactly 8 px tall; structural panels are {@code k*9+1 >= 10}. */
    private static final int ROW_MAX_H = 8;
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
            // Structural header / list panel -> refracting glass plate + readability scrim.
            HudGlass.glassBoxHotbar(x0, y0, x1, y1, 0.85F);
            DrawableHelper.fill(matrices, x0, y0, x1, y1, PANEL_SCRIM);
        }
    }
}
