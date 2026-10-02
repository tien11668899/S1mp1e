package dev.s1mp1e.glass.mixin;

import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * World-select rows: on hover (or in touch mode) vanilla lays a bright translucent whitish plate over the world icon so
 * the join arrow reads on it — that is the "白色覆蓋" the user disliked. Replace it with a soft dark scrim, which still
 * gives the (white) join / marked-join glyph contrast on a bright thumbnail without the white wash. The 1.21.1
 * counterpart of 26.2's {@code WorldListEntryGlassMixin}.
 *
 * <p>1.20.1 / 1.21.1 path (verified with javap on the 1.20.1 class too: one {@code MatrixStack.fill(IIIII)} in {@code render}): {@code WorldListWidget$Entry.render} (1.16.5 name of the entry class) draws the icon, then — when hovered or
 * on a touchscreen — exactly one {@code net.minecraft.client.gui.DrawableHelper.fill(context, x, y, x+32, y+32, 0x9F909090)} plate under the join glyph, then the
 * join {@code drawGuiTexture}. That single {@code fill} is redirected to a soft dark scrim. (The join glyph itself stays
 * vanilla here; the SF-Symbol swap is a separate, larger port.)
 */
@Mixin(targets = "net.minecraft.client.gui.screen.world.WorldListWidget$Entry")
public abstract class WorldEntryScrimMixin {

    /** Soft dark, not the vanilla whitish plate. */
    private static final int S1MP1E_SCRIM = 0x66000000;

    @Redirect(method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawableHelper;fill(Lnet/minecraft/client/util/math/MatrixStack;IIIII)V"))
    private void s1mp1e$iconHoverScrim(MatrixStack context, int x0, int y0, int x1, int y1, int color) {
        net.minecraft.client.gui.DrawableHelper.fill(context, x0, y0, x1, y1, S1MP1E_SCRIM);
    }
}
