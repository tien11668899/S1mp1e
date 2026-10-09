package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.AppleScroller;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.client.util.math.MatrixStack;
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
 * blurred panorama for #5/#26 — is already handled on this line by {@code EntryListGlassMixin}; this mixin only
 * replaces the grey scrollbar.)
 *
 * <p>1.16.5 (javap-verified) draws the whole list with raw {@code Tessellator} quads. The scrollbar block in
 * {@code render} is guarded by {@code if (getMaxScroll() > 0)} — the only {@code getMaxScroll} call in {@code render}
 * (ordinal 0) — so redirecting that to 0 while the glass button program is up makes vanilla draw no track/knob. At
 * {@code render} RETURN the {@link AppleScroller} is drawn at vanilla's exact knob rect (same
 * {@code getScrollbarPositionX}/knob-height/knob-y maths, offset 1251-1340) so dragging stays 1:1 and nothing shows
 * when the list fits. Vanilla's drag handling ({@code updateScrollingState}) is untouched.
 */
@Mixin(EntryListWidget.class)
public abstract class EntryListScrollerMixin {

    @Shadow protected int top;
    @Shadow protected int bottom;
    @Shadow private boolean scrolling;
    @Shadow public abstract double getScrollAmount();
    @Shadow public abstract int getMaxScroll();
    @Shadow protected abstract int getMaxPosition();
    @Shadow protected abstract int getScrollbarPositionX();

    @Unique private int s1mp1e$mx, s1mp1e$my;

    @Unique private static boolean s1mp1e$scroller() {
        return GlassProgram.ensureReady() && GlassProgram.btnUsable();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$pre(MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        this.s1mp1e$mx = mouseX;
        this.s1mp1e$my = mouseY;
    }

    /** The {@code if (getMaxScroll() > 0)} scrollbar guard: 0 while the glass scroller replaces it, so vanilla draws none. */
    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/widget/EntryListWidget;getMaxScroll()I", ordinal = 0))
    private int s1mp1e$suppressBar(EntryListWidget<?> self) {
        int real = this.getMaxScroll();
        return s1mp1e$scroller() ? 0 : real;
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void s1mp1e$post(MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!s1mp1e$scroller()) return;
        int maxScroll = this.getMaxScroll();
        if (maxScroll <= 0) return;
        int listH = this.bottom - this.top;
        int knobH = (int) ((float) (listH * listH) / (float) this.getMaxPosition());
        knobH = MathHelper.clamp(knobH, 32, listH - 8);
        int knobY = (int) this.getScrollAmount() * (listH - knobH) / maxScroll + this.top;
        if (knobY < this.top) knobY = this.top;
        float right = this.getScrollbarPositionX() + 6;
        boolean hover = AppleScroller.near(this.s1mp1e$mx, this.s1mp1e$my, right, this.top, this.bottom);
        AppleScroller.draw(matrices, this, right, this.top, this.bottom, knobY, knobH,
                this.getScrollAmount(), hover, this.scrolling, 1.0F);
    }
}
