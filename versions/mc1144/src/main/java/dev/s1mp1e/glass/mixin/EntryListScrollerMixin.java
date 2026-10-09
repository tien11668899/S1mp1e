package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.gui.AppleScroller;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * #1 — every list page's scrollbar → macOS overlay scroller. (The list CHROME — dirt band / header-footer strips /
 * blurred panorama / narrow-list glass pane for #5/#26 — is handled by {@code EntryListGlassMixin}; this mixin only
 * replaces the grey scrollbar.)
 *
 * <p>1.14.4 (javap-verified) draws the whole list with raw {@code Tessellator} quads. The scrollbar block in
 * {@code render(IIF)V} is guarded by {@code if (getMaxScroll() > 0)} — the only {@code getMaxScroll} call in
 * {@code render} (ordinal 0; a private method on 1.14.4, so an {@code invokespecial}) — so redirecting that to 0 while
 * the glass button program is up makes vanilla draw no track/knob (its {@code Tessellator.draw()} calls #3..#5 are inside
 * that block and never run). At {@code render} RETURN the {@link AppleScroller} is drawn at vanilla's exact knob rect
 * (same {@code getScrollbarPosition}/knob-height/knob-y maths) so dragging stays 1:1 and nothing shows when the list
 * fits. Vanilla's drag handling ({@code updateScrollingState}) is untouched. Identical to the 1.15.2 mixin except
 * {@code RenderSystem} → {@code GlStateManager}.
 */
@Mixin(EntryListWidget.class)
public abstract class EntryListScrollerMixin {

    @Shadow protected int top;
    @Shadow protected int bottom;
    @Shadow private boolean scrolling;
    @Shadow public abstract double getScrollAmount();
    @Shadow private int getMaxScroll() { throw new AssertionError(); }   // 1.14.4: private
    @Shadow protected abstract int getMaxPosition();
    @Shadow protected abstract int getScrollbarPosition();

    @Unique private int s1mp1e$mx, s1mp1e$my;

    @Unique private static boolean s1mp1e$scroller() {
        return GlassProgram.ensureReady() && GlassProgram.btnUsable();
    }

    @Inject(method = "render(IIF)V", at = @At("HEAD"))
    private void s1mp1e$pre(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        this.s1mp1e$mx = mouseX;
        this.s1mp1e$my = mouseY;
    }

    /** The {@code if (getMaxScroll() > 0)} scrollbar guard: 0 while the glass scroller replaces it, so vanilla draws none. */
    @Redirect(method = "render(IIF)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/widget/EntryListWidget;getMaxScroll()I", ordinal = 0))
    private int s1mp1e$suppressBar(EntryListWidget<?> self) {
        int real = this.getMaxScroll();
        return s1mp1e$scroller() ? 0 : real;
    }

    @Inject(method = "render(IIF)V", at = @At("RETURN"))
    private void s1mp1e$post(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!s1mp1e$scroller()) return;
        int maxScroll = this.getMaxScroll();
        if (maxScroll <= 0) return;
        int listH = this.bottom - this.top;
        int knobH = (int) ((float) (listH * listH) / (float) this.getMaxPosition());
        knobH = MathHelper.clamp(knobH, 32, listH - 8);
        int knobY = (int) this.getScrollAmount() * (listH - knobH) / maxScroll + this.top;
        if (knobY < this.top) knobY = this.top;
        float right = this.getScrollbarPosition() + 6;
        boolean hover = AppleScroller.near(this.s1mp1e$mx, this.s1mp1e$my, right, this.top, this.bottom);
        AppleScroller.draw(this, right, this.top, this.bottom, knobY, knobH,
                this.getScrollAmount(), hover, this.scrolling, 1.0F);
        GlStateManager.enableTexture();
        GlStateManager.color4f(1f, 1f, 1f, 1f);
    }
}
