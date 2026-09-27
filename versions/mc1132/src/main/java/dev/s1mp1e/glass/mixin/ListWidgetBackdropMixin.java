package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.MenuBackdrop;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.ListWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla list-screen backgrounds → liquid glass (the 1.8.9 checklist's V-4 list half, and what
 * 1.21 vanilla does natively): world select, server list, controls, languages, resource packs, … replace
 * their tiled dirt with the blurred title panorama (no world) or the blurred world (in-game), dim the row
 * body, and re-blit the same blurred strips over the header/footer so overflowing rows still clip. The
 * 1.13.2 port of mc1144's {@code EntryListWidgetBackdropMixin}.
 *
 * <p><b>1.13.2 seams (javap-verified, legacy yarn 1.13.2+build.604-v2).</b> Every 1.13.2 list renders
 * through {@code net.minecraft.client.gui.widget.ListWidget.render(int,int,float)}: {@code EntryListWidget}
 * extends it and does not override {@code render}. The interior dirt is a single
 * {@code POSITION_TEXTURE_COLOR} quad spanning {@code [xStart, yStart]}–{@code [xEnd, yEnd]}, flushed by
 * the FIRST {@code Tessellator.draw()} in the method (ordinal 0, offset 315 — before {@code renderHeader} at
 * 368 and the row pass {@code method_6704} at 379; every later {@code draw()} belongs to the edge shading and
 * the scrollbar). So {@code shift = AFTER} on that call lands exactly once per frame, with the tessellator
 * idle, after the dirt and before any row. The header/footer strips come from
 * {@code renderHoleBackground(int,int,int,int)}, called twice (offsets 397 and 415) AFTER the row pass so
 * they still mask overflowing rows; HEAD-cancelling it and re-blitting the cached backdrop keeps that
 * masking while dropping the dirt. Bounds: {@code protected int yStart/yEnd/xStart/xEnd}
 * (1.14.4's {@code top/bottom/left/right}).
 *
 * <p><b>Backdrop source.</b> No world → {@link MenuBackdrop#panoramaTex()}, the same frozen panorama
 * {@code ScreenMenuBackdropMixin} already painted the rest of the screen with, so the list area matches it
 * seamlessly. In world → {@link SceneCapture#texture()}, which holds the composite grabbed during
 * {@code Screen.renderBackground} (world + vanilla's darkening gradient) or, for a screen that never calls
 * it, the HUD's render-HEAD grab (world only) — either way it does NOT contain the list's own dirt or rows,
 * so re-blitting it over the strips can never smear the rows into the background.
 *
 * <p>With no panorama, no blur program or no backdrop, both hooks bow out and vanilla's dirt draws.
 * Everything is wrapped so a failure falls back to vanilla rather than crashing, and the strip hook only
 * fires when the interior hook actually ran this frame — a subclass that overrides {@code render} without
 * calling {@code super} simply keeps its own look. The body dim and hairline use
 * {@link GlassWidgets#drawRect}, never {@code DrawableHelper.fill} (which ends with {@code disableBlend()}).
 */
@Mixin(ListWidget.class)
public abstract class ListWidgetBackdropMixin {

    /** Row-body dim, so the entries read over the blur (matches the 1.14.4/1.16.5 ports). */
    @Unique private static final int s1mp1e$BODY_DIM = 0x80000000;
    /** 1 px hairline on the strip edge that faces the rows. */
    @Unique private static final int s1mp1e$SEPARATOR = 0x33FFFFFF;

    @Shadow protected int yStart;
    @Shadow protected int yEnd;
    @Shadow protected int xStart;
    @Shadow protected int xEnd;

    /** The backdrop texture drawn for the current list frame, remembered so the strip hook re-blits it. */
    @Unique private int s1mp1e$listTex;
    @Unique private boolean s1mp1e$listActive;

    @Inject(method = "render(IIF)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/render/Tessellator;draw()V",
                     ordinal = 0,
                     shift = At.Shift.AFTER))
    private void s1mp1e$listBackdrop(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        this.s1mp1e$listActive = false;
        this.s1mp1e$listTex = 0;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            int tex;
            if (mc == null) return;
            if (mc.world == null) {
                if (!MenuBackdrop.ready()) return;
                tex = MenuBackdrop.panoramaTex();
            } else {
                if (!GlassProgram.ensureReady() || !GlassProgram.blurUsable()) return;
                if (!SceneCapture.hasBackdrop()) return;
                tex = SceneCapture.texture();
            }
            if (tex == 0) return;

            MenuBackdrop.drawTexture(tex, MenuBackdrop.RADIUS, MenuBackdrop.DIM, this.yStart, this.yEnd);
            // Darken the list body so the rows read over the blur. GlassWidgets.drawRect, never
            // DrawableHelper.fill: fill ENDS with disableBlend(), which would strip blending from
            // the rows vanilla draws immediately after (hard rule 5).
            GlassWidgets.drawRect(this.xStart, this.yStart, this.xEnd, this.yEnd, s1mp1e$BODY_DIM);

            this.s1mp1e$listTex = tex;
            this.s1mp1e$listActive = true;
        } catch (Throwable ignored) {
            this.s1mp1e$listActive = false;
            this.s1mp1e$listTex = 0;
        }
    }

    @Inject(method = "renderHoleBackground(IIII)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$holeBackground(int stripTop, int stripBottom, int alphaTop, int alphaBottom,
                                       CallbackInfo ci) {
        try {
            if (!this.s1mp1e$listActive || this.s1mp1e$listTex == 0) return;
            MenuBackdrop.drawTexture(this.s1mp1e$listTex, MenuBackdrop.RADIUS, MenuBackdrop.DIM,
                    stripTop, stripBottom);
            // Hairline on the edge facing the rows: the header strip's bottom, the footer's top.
            if (stripBottom <= this.yStart) {
                GlassWidgets.drawRect(this.xStart, stripBottom - 1, this.xEnd, stripBottom, s1mp1e$SEPARATOR);
            } else if (stripTop >= this.yEnd) {
                GlassWidgets.drawRect(this.xStart, stripTop, this.xEnd, stripTop + 1, s1mp1e$SEPARATOR);
            }
            ci.cancel();
        } catch (Throwable ignored) {
            // not cancelled -> vanilla draws its dirt strip
        }
    }
}
