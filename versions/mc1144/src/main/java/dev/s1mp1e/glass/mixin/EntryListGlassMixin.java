package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassScreens;
import dev.s1mp1e.glass.render.MenuBackdrop;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.client.render.Tessellator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Every {@code EntryListWidget} (world / server / pack lists, key binds, statistics, ...) loses vanilla's dirt: the
 * list interior shows the blurred menu backdrop (the title panorama without a world, the blurred live frame in a
 * world) under a 50 % dim, the header / footer bands the same backdrop with a hairline, and there are no edge shadows.
 * The statistics lists sit on {@link GlassScreens#statsPlate their own plate} instead.
 *
 * <p><b>1.14.4.</b> A list has no background switches yet (1.16's {@code field_26846} / {@code field_26847}, which
 * the 1.16.5 line overrides): {@code EntryListWidget.render} (javap-read) always
 * <ol>
 *   <li>builds the dirt slab and draws it — {@code Tessellator.draw()} #0;</li>
 *   <li>after the rows, paints the two "holes" above / below the list with {@code renderHoleBackground} (dirt);</li>
 *   <li>draws the two 4 px edge shadows — {@code Tessellator.draw()} #1 and #2 (#3..#5 are the scrollbar).</li>
 * </ol>
 * So the three steps are wrapped: when the backdrop was drawn, the already built dirt / shadow quads are dropped
 * ({@code BufferBuilder.end()} without a draw; the next {@code begin} clears the buffer) and the holes are painted
 * from the same backdrop. If the backdrop is not available the list stays exactly vanilla.
 */
@Mixin(EntryListWidget.class)
public abstract class EntryListGlassMixin {

    @Shadow protected int top;
    @Shadow protected int bottom;
    @Shadow protected int left;
    @Shadow protected int right;

    @Unique private int s1mp1e$listTex;
    /** Our backdrop replaced the dirt slab this frame. */
    @Unique private boolean s1mp1e$listActive;
    /** This is the statistics list on its glass plate this frame. */
    @Unique private boolean s1mp1e$statsActive;

    @Unique
    private static void s1mp1e$drop(Tessellator tessellator) {
        tessellator.getBuffer().end();           // built but never drawn; begin() clears it
    }

    @WrapOperation(method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Tessellator;draw()V", ordinal = 0))
    private void s1mp1e$listInterior(Tessellator tessellator, Operation<Void> original) {
        this.s1mp1e$listActive = false;
        this.s1mp1e$statsActive = false;
        this.s1mp1e$listTex = 0;
        if (GlassScreens.isStatsList(this)) {    // the plate is already there (StatsListGlassMixin): no dirt, no dim
            this.s1mp1e$statsActive = true;
            s1mp1e$drop(tessellator);
            return;
        }
        boolean ours = false;
        int tex = 0;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.world == null) {
                // raw immediate-mode quad: independent of the tessellator buffer that is still open here
                ours = MenuBackdrop.draw();
                tex = MenuBackdrop.panoramaTex();
            } else if (GlassProgram.ensureReady() && GlassProgram.blurUsable()) {
                ours = MenuBackdrop.drawLive(MenuBackdrop.RADIUS, MenuBackdrop.DIM);
                tex = SceneCapture.texture();
            }
        } catch (Throwable t) {
            ours = false;
        }
        if (!ours || tex == 0) {
            original.call(tessellator);
            return;
        }
        s1mp1e$drop(tessellator);
        this.s1mp1e$listTex = tex;
        this.s1mp1e$listActive = true;
        DrawableHelper.fill(this.left, this.top, this.right, this.bottom, 0x80000000);
    }

    @WrapOperation(method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/widget/EntryListWidget;renderHoleBackground(IIII)V"))
    private void s1mp1e$listStrips(EntryListWidget<?> self, int y0, int y1, int alphaTop, int alphaBottom,
                                   Operation<Void> original) {
        try {
            if (this.s1mp1e$statsActive) {
                // both bands at once, on the first of the two calls (statsStrips consumes the plate state)
                if (y1 <= this.top) GlassScreens.statsStrips(this.left, this.right, this.top, this.bottom);
                return;
            }
            if (this.s1mp1e$listActive && this.s1mp1e$listTex != 0) {
                MenuBackdrop.drawTexture(this.s1mp1e$listTex, MenuBackdrop.RADIUS, MenuBackdrop.DIM, y0, y1);
                if (y1 <= this.top) DrawableHelper.fill(this.left, this.top - 1, this.right, this.top, 0x33FFFFFF);
                else DrawableHelper.fill(this.left, this.bottom, this.right, this.bottom + 1, 0x33FFFFFF);
                return;
            }
        } catch (Throwable t) {
            return;
        }
        original.call(self, y0, y1, alphaTop, alphaBottom);
    }

    @WrapOperation(method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Tessellator;draw()V", ordinal = 1))
    private void s1mp1e$topShadow(Tessellator tessellator, Operation<Void> original) {
        if (this.s1mp1e$listActive || this.s1mp1e$statsActive) s1mp1e$drop(tessellator);
        else original.call(tessellator);
    }

    @WrapOperation(method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Tessellator;draw()V", ordinal = 2))
    private void s1mp1e$bottomShadow(Tessellator tessellator, Operation<Void> original) {
        if (this.s1mp1e$listActive || this.s1mp1e$statsActive) s1mp1e$drop(tessellator);
        else original.call(tessellator);
    }
}
