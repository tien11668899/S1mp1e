package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.gui.AllGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.widget.ListWidget;
import net.minecraft.client.render.Tessellator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * #4 — the selected row of every list was vanilla's box (grey outline + black inset). It becomes a glass highlight on
 * the button material with the hotbar corner.
 *
 * <p><b>1.13.2 (javap-verified).</b> The selection box is painted INLINE in {@code method_6704(IIIIF)V} (renderList),
 * but — unlike 1.14.4's TWO {@code Tessellator.draw()} (border then inner) — 1.13.2 builds BOTH the grey border quad
 * and the black inset quad into one {@code BufferBuilder} (8 vertices) and flushes them with a SINGLE
 * {@code Tessellator.draw()} (ordinal 0, offset 454 in the disassembly), between a {@code disableTexture()} (offset
 * 147) and {@code enableTexture()} (offset 457). So the one draw is redirected: when the glass button program is up,
 * the built-but-undrawn quads are dropped with {@code getBuffer().end()} (the next {@code begin()} clears the buffer —
 * the proven 1.13.2/1.14.4 idiom, see {@code EntryListGlassMixin}; 1.13.2's {@code BufferBuilder} has NO
 * {@code popData()}) and the glass capsule is drawn in its place.
 *
 * <p>Vanilla's selection x-range is {@code xStart + width/2 ∓ getRowWidth()/2} and its y-range {@code y-2 ..
 * y+entryHeight-2} (the outer border quad; the inset is 1 px smaller). The row-top local is slot 10 (the same slot
 * {@code ListMotionMixin} eases between rows), read with {@code @Local(index = 10)} so the capsule glides with the
 * selection. 1.13.2 lists have no keyboard focus ({@code method_18417} returns {@code visible}, not a focus flag), so
 * the selected row always gets the focused lift .81.
 *
 * <p>Fixed pipeline: our raw-GL glass needs texture ON, so {@code enableTexture()} + {@code color(1,1,1,1)} around
 * the capsule; vanilla's own {@code enableTexture()} after the block restores the normal row-drawing state.
 */
@Mixin(ListWidget.class)
public abstract class EntrySelectionGlassMixin {

    @Shadow protected int xStart;
    @Shadow protected int width;
    @Shadow @Final protected int entryHeight;
    @Shadow public abstract int getRowWidth();

    /** The selection box (one Tessellator.draw of both quads): replace it with one glass capsule, or fall back. */
    @Redirect(method = "method_6704", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/Tessellator;draw()V", ordinal = 0))
    private void s1mp1e$selBox(Tessellator self, @Local(index = 10) int y) {
        if (!(GlassProgram.ensureReady() && GlassProgram.btnUsable())) { self.draw(); return; }
        self.getBuffer().end();                   // built but never drawn; the next begin() clears it (no popData on 1.13.2)
        float x0 = this.xStart + this.width / 2f - getRowWidth() / 2f;
        float x1 = this.xStart + this.width / 2f + getRowWidth() / 2f;
        float y0 = y - 2, y1 = y + this.entryHeight - 2;
        // the block ran under disableTexture(): put normal textured GUI state back for our raw-GL glass; vanilla's own
        // enableTexture() right after this draw restores what it expects for the following rows.
        GlStateManager.enableTexture();
        GlStateManager.color(1f, 1f, 1f, 1f);
        AllGlass.capsule(x0, y0, x1, y1, AllGlass.hotbarCorner(x1 - x0, y1 - y0), 0.81f, 1f);
        GlStateManager.color(1f, 1f, 1f, 1f);
    }
}
