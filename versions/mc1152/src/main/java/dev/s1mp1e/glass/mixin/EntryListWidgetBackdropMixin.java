package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.MenuBackdrop;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.EntryListWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla list-screen backgrounds → liquid glass (the 1.8.9 checklist's V-4 list half, and what
 * 1.21 vanilla does natively): world select, server list, the options sub-lists, resource packs, …
 * replace their tiled dirt with the blurred title panorama (no world) or the blurred world
 * (in-game), dim the row body, and re-blit the same blurred strips over the header/footer so
 * overflowing rows still clip.
 *
 * <p><b>Seams (javap-confirmed on 1.15.2).</b> {@code EntryListWidget.render(int,int,float)} draws
 * the interior dirt as a single {@code POSITION_TEXTURE_COLOR} quad spanning
 * {@code [left, top]}–{@code [right, bottom]} and flushes it with the FIRST
 * {@code Tessellator.draw()} in the method (ordinal 0, at offset 313 — before
 * {@code renderHeader} at 350 and {@code renderList} at 361; every later {@code draw()} belongs to
 * the scrollbar). So {@code shift = AFTER} on that call lands exactly once per frame, with the
 * tessellator idle, after the dirt and before any row. The header/footer strips come from
 * {@code renderHoleBackground(int,int,int,int)}, called twice AFTER {@code renderList} so they
 * still mask overflowing rows; HEAD-cancelling it and re-blitting the cached backdrop keeps that
 * masking while dropping the dirt.
 *
 * <p><b>Backdrop source.</b> No world → {@link MenuBackdrop#panoramaTex()}, the same frozen
 * panorama {@code ScreenMenuBackdropMixin} already painted the rest of the screen with, so the
 * list area matches it seamlessly. In world → {@link SceneCapture#texture()}, which at this point
 * holds the composite grabbed during {@code Screen.renderBackground} (world + vanilla's darkening
 * gradient) and therefore does NOT contain the list's own dirt or rows: re-blitting it over the
 * strips can never smear the rows into the background.
 *
 * <p>With no panorama, no blur program or no backdrop, both hooks bow out and vanilla's dirt
 * draws. Everything is wrapped so a failure falls back to vanilla rather than crashing, and the
 * strip hook only fires when the interior hook actually ran this frame — a subclass that overrides
 * {@code render} without calling {@code super} (some mod list screens) simply keeps its own look.
 *
 * <p>The legacy {@code ListWidget} is out of scope: on 1.15.2 it is used only by the Realms proxies.
 */
@Mixin(EntryListWidget.class)
public abstract class EntryListWidgetBackdropMixin {

    /** Row-body dim, so the entries read over the blur (matches the 1.16.5/1.19 ports). */
    @Unique private static final int s1mp1e$BODY_DIM = 0x80000000;
    /** 1 px hairline on the strip edge that faces the rows. */
    @Unique private static final int s1mp1e$SEPARATOR = 0x33FFFFFF;

    @Shadow protected int top;
    @Shadow protected int bottom;
    @Shadow protected int left;
    @Shadow protected int right;

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
            // Feature (A): the statistics screen keeps this generic blurred backdrop for the header /
            // footer strips + list body; StatsListGlassMixin then lays a refracting glass plate + grey
            // scrim over the LIST BODY only, injected right before renderList so it draws over this
            // backdrop and under the rows (deterministic order — no reliance on mixin apply order).
            if (mc.world == null) {
                if (!MenuBackdrop.ready()) return;
                tex = MenuBackdrop.panoramaTex();
            } else {
                if (!GlassProgram.ensureReady() || !GlassProgram.blurUsable()) return;
                if (!SceneCapture.hasBackdrop()) return;
                tex = SceneCapture.texture();
            }
            if (tex == 0) return;

            MenuBackdrop.drawTexture(tex, MenuBackdrop.RADIUS, MenuBackdrop.DIM, this.top, this.bottom);
            // Darken the list body so the rows read over the blur. GlassWidgets.drawRect, never
            // DrawableHelper.fill: fill ENDS with disableBlend(), which would strip blending from
            // the rows vanilla draws immediately after (hard rule 5).
            GlassWidgets.drawRect(this.left, this.top, this.right, this.bottom, s1mp1e$BODY_DIM);

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
            if (stripBottom <= this.top) {
                GlassWidgets.drawRect(this.left, stripBottom - 1, this.right, stripBottom, s1mp1e$SEPARATOR);
            } else if (stripTop >= this.bottom) {
                GlassWidgets.drawRect(this.left, stripTop, this.right, stripTop + 1, s1mp1e$SEPARATOR);
            }
            ci.cancel();
        } catch (Throwable ignored) {
            // not cancelled -> vanilla draws its dirt strip
        }
    }
}
