package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.AllGlass;
import dev.s1mp1e.client.gui.AppleScroller;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.MinecraftClient;
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
 * Every list page (worlds, servers, packs, languages, controls, stats, …). This is the 1.18.2 port of the 1.20.1 line's
 * {@code EntryListScrollerMixin} + {@code EntryListBackgroundGlassMixin}, but 1.18.2 draws the list background, the
 * top/bottom shadow strips AND the scrollbar with RAW {@code Tessellator} quads (not {@code DrawableHelper.fill} /
 * {@code drawTexture}), so the 1.20.1 fill/drawTexture redirects do not apply. Instead (javap-verified against 1.18.2):
 * <ul>
 *   <li><b>#1 scrollbar → macOS overlay scroller.</b> The scrollbar block is guarded by {@code if (getMaxScroll() > 0)}
 *       (the only {@code getMaxScroll} call in {@code render}); that guard is redirected to 0 while the glass button
 *       program is up, so vanilla draws no track/knob, and {@link AppleScroller} is drawn at {@code render} RETURN at
 *       exactly vanilla's knob rect (same {@code getScrollbarPositionX} / knob-height / knob-y maths) so dragging stays
 *       1:1 and nothing shows when the list fits.</li>
 *   <li><b>#5 / #26 list chrome.</b> The dirt band and the top/bottom shadow strips are gated by the private
 *       {@code renderBackground} / {@code renderHorizontalShadows} booleans; with the glass pipeline up and NO world
 *       loaded they are forced off (the rows sit on the blurred panorama like 26.2 / 1.21.1). A NARROW list (under 70 %
 *       of the screen — the resource-pack columns) additionally gets a glass pane + 0x30 grey scrim where the band was.
 *       With a world loaded the chrome is left vanilla (spec #26).</li>
 * </ul>
 */
@Mixin(EntryListWidget.class)
public abstract class EntryListScrollerMixin {

    @Shadow protected int left;
    @Shadow protected int right;
    @Shadow protected int top;
    @Shadow protected int bottom;
    @Shadow protected int width;
    @Shadow private boolean scrolling;
    @Shadow private boolean renderBackground;
    @Shadow private boolean renderHorizontalShadows;
    @Shadow public abstract double getScrollAmount();
    @Shadow public abstract int getMaxScroll();
    @Shadow protected abstract int getMaxPosition();
    @Shadow protected abstract int getScrollbarPositionX();

    @Unique private int s1mp1e$mx, s1mp1e$my;
    @Unique private boolean s1mp1e$narrowPane;

    @Unique private static boolean s1mp1e$glass() {
        return GlassProgram.ensureReady() && GlassProgram.usable();
    }

    @Unique private static boolean s1mp1e$scroller() {
        return GlassProgram.ensureReady() && GlassProgram.btnUsable();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$pre(MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        this.s1mp1e$mx = mouseX;
        this.s1mp1e$my = mouseY;
        this.s1mp1e$narrowPane = false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (s1mp1e$glass() && mc.world == null) {
            // world-less menu: drop the dirt band + shadow strips so the rows sit on the blurred panorama (#26)
            this.renderBackground = false;
            this.renderHorizontalShadows = false;
            boolean narrow = (this.right - this.left) < mc.getWindow().getScaledWidth() * 0.7f;
            this.s1mp1e$narrowPane = narrow;
            // #5 narrow list (resource-pack columns): glass pane + grey scrim where the dark band was — drawn HERE, before
            // the header and rows, so the rows sit on it (drawing it at RETURN would cover them).
            if (narrow && this.bottom > this.top && this.right > this.left) {
                AllGlass.pane(matrices, this.left, this.top, this.right, this.bottom, 1f, 0x30000000);
            }
        }
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
        // #1 macOS overlay scroller at vanilla's knob rect (same maths)
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
