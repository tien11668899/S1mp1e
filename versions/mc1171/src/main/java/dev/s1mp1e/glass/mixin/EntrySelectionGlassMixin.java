package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.gui.AllGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.util.math.MatrixStack;
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
 * <p>1.18.2 has NO {@code drawSelectionHighlight} method (that was extracted in 1.19.2): {@code renderList} paints the
 * selected row's box INLINE as two raw {@code Tessellator} quads (javap-verified — a grey/white border quad then a black
 * inner quad, their colour set by {@code RenderSystem.setShaderColor}). Both {@code Tessellator.draw()} calls are
 * redirected: when the glass button program is up, the buffer is finished WITHOUT the GPU draw ({@code getBuffer().end()}
 * clears the "building" state so the next {@code begin()} does not throw) so neither vanilla quad shows, and the glass
 * capsule is drawn in place of the border quad. {@code ListMotionMixin} eases the row-top local (slot 13) at the
 * {@code disableTexture} that opens this block, so reading it with {@code @Local} keeps the capsule gliding exactly like
 * the vanilla box did. Row span from the widget's own {@code left}/{@code width} (1.18.2 lists are not ClickableWidgets),
 * matching vanilla's {@code left + (width ∓ rowWidth)/2}. Vanilla draws these quads in absolute screen coords ignoring
 * the MatrixStack, so the capsule bakes an identity {@link MatrixStack}.
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
    @Redirect(method = "renderList", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/Tessellator;draw()V", ordinal = 0))
    private void s1mp1e$selBorder(Tessellator self, @Local(index = 13) int y) {
        if (!(GlassProgram.ensureReady() && GlassProgram.btnUsable())) { self.draw(); s1mp1e$glassSel = false; return; }
        s1mp1e$glassSel = true;
        // finish + DISCARD vanilla's border quad: end() only queues the built data — popData() takes it back out. Without
        // it the next Tessellator draw renders OUR queued quad with ITS shader and every later draw is off by one (the
        // selection drew as a tan box with the pack icon's texture, list icons vanished, text turned into colour blocks).
        self.getBuffer().end();
        self.getBuffer().popData();
        float x0 = this.left + this.width / 2f - getRowWidth() / 2f;
        float x1 = this.left + this.width / 2f + getRowWidth() / 2f;
        float y0 = y - 2, y1 = y + this.itemHeight - 2;
        // vanilla opened this block with disableTexture() + the POSITION shader: drawing our raw-GL glass inside that state
        // broke the rows drawn after it (pack icons gone, titles garbled, a tan box). Draw it in normal GUI state and put
        // vanilla's state back for the (discarded) inner quad and its closing enableTexture().
        RenderSystem.enableTexture();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        AllGlass.capsule(new MatrixStack(), x0, y0, x1, y1, AllGlass.hotbarCorner(x1 - x0, y1 - y0),
                isFocused() ? 0.81f : 0.55f, 1f);
        RenderSystem.setShader(net.minecraft.client.render.GameRenderer::getPositionShader);
        RenderSystem.disableTexture();
    }

    /** The inner black quad: discarded when the capsule replaced the box; restore the white shader colour vanilla left black. */
    @Redirect(method = "renderList", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/Tessellator;draw()V", ordinal = 1))
    private void s1mp1e$selInner(Tessellator self) {
        if (!s1mp1e$glassSel) { self.draw(); return; }
        self.getBuffer().end();
        self.getBuffer().popData();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }
}
