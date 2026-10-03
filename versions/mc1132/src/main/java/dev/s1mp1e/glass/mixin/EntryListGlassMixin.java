package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassScreens;
import dev.s1mp1e.glass.render.MenuBackdrop;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.widget.ListWidget;
import net.minecraft.client.render.Tessellator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Every {@code ListWidget} (world / server / pack lists, key binds, statistics, ...) loses vanilla's dirt: the
 * list interior shows the blurred menu backdrop (the title panorama without a world, the blurred live frame in a
 * world) under a 50 % dim, the header / footer bands the same backdrop with a hairline, and there are no edge shadows.
 * The statistics lists sit on {@link GlassScreens#statsPlate their own plate} instead.
 *
 * <p><b>1.13.2.</b> A list has no background switches yet (1.16's {@code field_26846} / {@code field_26847}, which
 * the 1.16.5 line overrides): {@code ListWidget.render} (javap-read) always
 * <ol>
 *   <li>builds the dirt slab and draws it — {@code Tessellator.draw()} #0;</li>
 *   <li>after the rows, paints the two "holes" above / below the list with {@code renderHoleBackground} (dirt);</li>
 *   <li>draws the two 4 px edge shadows — {@code Tessellator.draw()} #1 and #2 (#3..#5 are the scrollbar).</li>
 * </ol>
 * So the three steps are wrapped: when the backdrop was drawn, the already built dirt / shadow quads are dropped
 * ({@code BufferBuilder.end()} without a draw; the next {@code begin} clears the buffer) and the holes are painted
 * from the same backdrop. If the backdrop is not available the list stays exactly vanilla.
 *
 * <p><b>Lists that do not span the screen.</b> The resource pack screen has two lists side by side (and draws its
 * own background first). A full-screen backdrop per list let the second list wipe the first, so a list narrower than
 * the screen keeps the screen's background: its body is only dimmed, and the two masks are a 1:1 copy (blur radius 0)
 * of what was behind the list before its rows were drawn, confined to the list's columns.
 */
@Mixin(ListWidget.class)
public abstract class EntryListGlassMixin {

    @Shadow protected int yStart;
    @Shadow protected int yEnd;
    @Shadow protected int xStart;
    @Shadow protected int xEnd;

    @Unique private int s1mp1e$listTex;
    /** Our backdrop replaced the dirt slab this frame. */
    @Unique private boolean s1mp1e$listActive;
    /** This is the statistics list on its glass plate this frame. */
    @Unique private boolean s1mp1e$statsActive;
    /**
     * The list does not span the screen (the two lists of the resource pack screen): the screen drew its own
     * background, so the list must not lay a full-screen backdrop (the second list's would wipe the first list).
     */
    @Unique private boolean s1mp1e$narrow;
    /** The owning screen has drawn its own (already dimmed) background: no second dim, no list scrim. */
    @Unique private boolean s1mp1e$keepBg;

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
        this.s1mp1e$narrow = false;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            // 1.13.2: the snooper page has already painted (and, in a world, darkened + blurred) its background
            this.s1mp1e$keepBg = mc.world != null
                    && mc.currentScreen instanceof net.minecraft.client.gui.screen.SnooperScreen;
            if (this.s1mp1e$keepBg || this.xStart > 0 || this.xEnd < mc.field_19944.method_18321()) {
                // keep the screen's own background: remember what is behind the list now (before its rows), for the
                // header / footer masks, and only dim the list body
                if (GlassProgram.ensureReady() && GlassProgram.blurUsable()) {
                    SceneCapture.grabNow();
                    tex = SceneCapture.texture();
                    ours = tex != 0;
                    this.s1mp1e$narrow = ours;
                }
            } else if (mc.world == null) {
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
        if (!this.s1mp1e$keepBg) {
            DrawableHelper.fill(this.xStart, this.yStart, this.xEnd, this.yEnd, 0x80000000);
        }
    }

    @WrapOperation(method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/widget/ListWidget;renderHoleBackground(IIII)V"))
    private void s1mp1e$listStrips(ListWidget self, int y0, int y1, int alphaTop, int alphaBottom,
                                   Operation<Void> original) {
        try {
            if (this.s1mp1e$statsActive) {
                // both bands at once, on the first of the two calls (statsStrips consumes the plate state)
                if (y1 <= this.yStart) GlassScreens.statsStrips(this.xStart, this.xEnd, this.yStart, this.yEnd);
                return;
            }
            if (this.s1mp1e$listActive && this.s1mp1e$listTex != 0 && this.s1mp1e$narrow) {
                // 1:1 copy of the screen background over the rows that scrolled past the edge, in the list's columns
                MenuBackdrop.drawTexture(this.s1mp1e$listTex, 0f, 0f, this.xStart, y0, this.xEnd, y1);
                if (y1 <= this.yStart) DrawableHelper.fill(this.xStart, this.yStart - 1, this.xEnd, this.yStart, 0x33FFFFFF);
                else DrawableHelper.fill(this.xStart, this.yEnd, this.xEnd, this.yEnd + 1, 0x33FFFFFF);
                return;
            }
            if (this.s1mp1e$listActive && this.s1mp1e$listTex != 0) {
                MenuBackdrop.drawTexture(this.s1mp1e$listTex, MenuBackdrop.RADIUS, MenuBackdrop.DIM, y0, y1);
                if (y1 <= this.yStart) DrawableHelper.fill(this.xStart, this.yStart - 1, this.xEnd, this.yStart, 0x33FFFFFF);
                else DrawableHelper.fill(this.xStart, this.yEnd, this.xEnd, this.yEnd + 1, 0x33FFFFFF);
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
