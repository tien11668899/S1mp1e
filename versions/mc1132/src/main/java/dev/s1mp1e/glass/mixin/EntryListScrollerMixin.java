package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.gui.AppleScroller;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.widget.ListWidget;
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
 * <p><b>1.13.2 (javap-verified).</b> Unlike the 1.14.4 sibling (which targets {@code EntryListWidget}), the scrollbar
 * is drawn in the BASE class {@code ListWidget.render(IIF)V}: the whole block is guarded by
 * {@code if (getMaxScroll() > 0)} — the only {@code getMaxScroll} call in {@code render} (ordinal 0; PUBLIC on 1.13.2,
 * so an {@code invokevirtual}, unlike 1.14.4's private one) — so redirecting that to 0 while the glass button program
 * is up makes vanilla draw no track/knob (its three scrollbar {@code Tessellator.draw()} calls are inside that block
 * and never run). At {@code render} RETURN the {@link AppleScroller} is drawn at vanilla's exact knob rect (the same
 * {@code o = yEnd - yStart}, {@code knobH = o*o/getMaxPosition()} clamp(32, o-8),
 * {@code knobY = (int)scroll*(o-knobH)/maxScroll + yStart} maths, offsets 745..1315 in the disassembly), so dragging
 * stays 1:1 and nothing shows when the list fits. Vanilla's drag handling is untouched. 1.13.2 deltas vs 1.14.4:
 * {@code top/bottom}→{@code yStart/yEnd}; the drag flag is {@code dragging} (not {@code scrolling});
 * {@code getScrollAmount()} returns {@code int}; {@code getMaxScroll()} is public.
 */
@Mixin(ListWidget.class)
public abstract class EntryListScrollerMixin {

    @Shadow protected int yStart;
    @Shadow protected int yEnd;
    @Shadow private boolean dragging;
    @Shadow public abstract int getScrollAmount();
    @Shadow public abstract int getMaxScroll();
    @Shadow protected abstract int getMaxPosition();
    @Shadow protected abstract int getScrollbarPosition();

    @Unique private int s1mp1e$mx, s1mp1e$my;

    @Unique private static boolean s1mp1e$scroller() {
        return GlassProgram.ensureReady() && GlassProgram.btnUsable();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$pre(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        this.s1mp1e$mx = mouseX;
        this.s1mp1e$my = mouseY;
    }

    /** The {@code if (getMaxScroll() > 0)} scrollbar guard: 0 while the glass scroller replaces it, so vanilla draws none. */
    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/widget/ListWidget;getMaxScroll()I", ordinal = 0))
    private int s1mp1e$suppressBar(ListWidget self) {
        int real = this.getMaxScroll();
        return s1mp1e$scroller() ? 0 : real;
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void s1mp1e$post(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!s1mp1e$scroller()) return;
        int maxScroll = this.getMaxScroll();
        if (maxScroll <= 0) return;
        int listH = this.yEnd - this.yStart;
        int knobH = (int) ((float) (listH * listH) / (float) this.getMaxPosition());
        knobH = MathHelper.clamp(knobH, 32, listH - 8);
        int knobY = this.getScrollAmount() * (listH - knobH) / maxScroll + this.yStart;
        if (knobY < this.yStart) knobY = this.yStart;
        float right = this.getScrollbarPosition() + 6;
        boolean hover = AppleScroller.near(this.s1mp1e$mx, this.s1mp1e$my, right, this.yStart, this.yEnd);
        AppleScroller.draw(this, right, this.yStart, this.yEnd, knobY, knobH,
                this.getScrollAmount(), hover, this.dragging, 1.0F);
        GlStateManager.enableTexture();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }
}
