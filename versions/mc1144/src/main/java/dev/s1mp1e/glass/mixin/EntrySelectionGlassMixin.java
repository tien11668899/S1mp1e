package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.gui.AllGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.client.render.Tessellator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * #4 — the selected row of every list was vanilla's box (outline colour + black inset). It becomes a glass highlight on
 * the button material with the hotbar corner — brighter while the list has focus.
 *
 * <p>1.14.4 (javap-verified) paints the selection box INLINE in {@code renderList(IIIIF)V} as two raw
 * {@code Tessellator} quads: {@code disableTexture()}, {@code color4f(grey/white border)}, build +
 * {@code Tessellator.draw()} (ordinal 0), then {@code color4f(black)}, build + {@code Tessellator.draw()} (ordinal 1),
 * then {@code enableTexture()}. Both draws are redirected: when the glass button program is up, the built-but-undrawn
 * quad is dropped with {@code getBuffer().end()} (the next {@code begin()} clears the buffer — the proven 1.14.4 idiom,
 * see {@code EntryListGlassMixin}; 1.14.4's {@code BufferBuilder} has NO {@code popData()}, unlike 1.15.2) so neither
 * vanilla quad shows, and the glass capsule is drawn in place of the border quad.
 *
 * <p>Fixed pipeline: our raw-GL glass needs texture ON, so {@code enableTexture()} + {@code color4f(1,1,1,1)} around the
 * capsule; vanilla's own {@code enableTexture()} after the inner quad restores the normal row-drawing state.
 *
 * <p>Row-top local is slot 12 on 1.14.4 ({@code m = y + index * itemHeight + headerHeight}; same slot
 * {@code ListMotionMixin} eases, so the glass capsule glides with the selection).
 */
@Mixin(EntryListWidget.class)
public abstract class EntrySelectionGlassMixin {

    @Shadow protected int left;
    @Shadow protected int width;
    @Shadow @Final protected int itemHeight;
    @Shadow public abstract int getRowWidth();
    @Shadow protected abstract boolean isFocused();

    @Unique private boolean s1mp1e$glassSel;

    /** The border quad: replace both vanilla quads with one glass capsule (or fall back to the vanilla draw). */
    @Redirect(method = "renderList(IIIIF)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/Tessellator;draw()V", ordinal = 0))
    private void s1mp1e$selBorder(Tessellator self, @Local(index = 12) int y) {
        if (!(GlassProgram.ensureReady() && GlassProgram.btnUsable())) { self.draw(); s1mp1e$glassSel = false; return; }
        s1mp1e$glassSel = true;
        self.getBuffer().end();                   // built but never drawn; the next begin() clears it (no popData on 1.14.4)
        float x0 = this.left + this.width / 2f - getRowWidth() / 2f;
        float x1 = this.left + this.width / 2f + getRowWidth() / 2f;
        float y0 = y - 2, y1 = y + this.itemHeight - 2;
        // the block ran under disableTexture(): put normal textured GUI state back for our raw-GL glass, then leave the
        // state vanilla expects for the (discarded) inner quad + its closing enableTexture().
        GlStateManager.enableTexture();
        GlStateManager.color4f(1f, 1f, 1f, 1f);
        AllGlass.capsule(x0, y0, x1, y1, AllGlass.hotbarCorner(x1 - x0, y1 - y0), isFocused() ? 0.81f : 0.55f, 1f);
        GlStateManager.color4f(1f, 1f, 1f, 1f);
    }

    /** The inner black quad: discarded when the capsule replaced the box; restore the white colour vanilla left black. */
    @Redirect(method = "renderList(IIIIF)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/Tessellator;draw()V", ordinal = 1))
    private void s1mp1e$selInner(Tessellator self) {
        if (!s1mp1e$glassSel) { self.draw(); return; }
        self.getBuffer().end();
        GlStateManager.color4f(1f, 1f, 1f, 1f);
    }
}
