package dev.s1mp1e.glass.mixin;

import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Menu-list motion for every {@link EntryListWidget} (world / server / language / resource-pack / key-binding lists,
 * option lists, stats, social, …) — the 1.17.1 port of the 1.20.1 / 1.21.1 lines' mixin (same {@code EntryListWidget}
 * methods where 1.17.1 has them, javap-checked: {@code mouseScrolled(DDD)}, the private {@code scroll(I)},
 * {@code renderList(MatrixStack,IIIIF)}; 1.17.1 has no {@code renderEntry} / {@code drawSelectionHighlight} yet — the
 * row draw and the selection box are inline in {@code renderList} and hooked there; the per-frame step hooks
 * {@code render}), itself the counterpart of 26.2's {@code ScrollAreaSmoothMixin} +
 * {@code AbstractWidgetScrollStepMixin} + the selection-box half of {@code SelectionListGlideMixin}.
 *
 * <ul>
 *   <li><b>Smooth wheel scrolling.</b> Vanilla's {@code mouseScrolled} jumps straight to
 *       {@code scrollAmount - vertical * itemHeight/2}; that single {@code setScrollAmount} is redirected into a target,
 *       notches keep adding to the target (a fast flick travels further), and the real scroll amount eases toward it one
 *       step per frame ({@code τ ≈ 85 ms}, no overshoot). Rendering, hit-testing and entry positions all read the one
 *       real scroll amount, so a click mid-glide always lands on what is drawn under the cursor.</li>
 *   <li><b>Keyboard scrolling.</b> {@code scroll(int)} (arrow-key navigation keeping the selection in view) glides the
 *       same way.</li>
 *   <li><b>Gliding selection box.</b> Vanilla draws the selected row's highlight right before that row,
 *       so it teleports when the selection changes. Here the highlight's top edge is eased in CONTENT coordinates
 *       (so it stays glued to the content while the list scrolls but glides between rows on a selection change) and the
 *       vanilla call is issued at that eased position — the box itself is still vanilla's, only its {@code y} is
 *       animated.</li>
 *   <li><b>Entries arriving later</b> (the world list after its async load, LAN servers, a search refill …) cascade
 *       in — each rises 6 px and fades in on a critically damped spring, 30 ms apart. Entries already there on the
 *       list's first frame are left alone.</li>
 * </ul>
 *
 * <p>Any {@code setScrollAmount} that is not our own per-frame step (a scrollbar drag, programmatic centring, clamping
 * after a resize) cancels the glide, so every non-wheel path keeps vanilla's exact behaviour. Pure {@code double}/
 * {@code float} maths — no glass pipeline needed — so it works even when the shaders are unavailable.
 */
@Mixin(EntryListWidget.class)
public abstract class ListMotionMixin {

    /** Wheel/keyboard scroll ease time constant (26.2's {@code ScrollAreaSmoothMixin.LG_TAU}). */
    @Unique private static final double S1MP1E_SCROLL_TAU = 0.085;
    /** Selection-box glide time constant (26.2's {@code SelectionListGlideMixin.LG_TAU}). */
    @Unique private static final float S1MP1E_SEL_TAU = 0.07f;

    @Shadow public abstract double getScrollAmount();
    @Shadow public abstract void setScrollAmount(double amount);
    @Shadow public abstract int getMaxScroll();

    // ---- smooth scrolling -------------------------------------------------

    @Unique private double s1mp1e$scrollTarget = Double.NaN;
    @Unique private long s1mp1e$scrollNs;
    @Unique private boolean s1mp1e$stepping;

    /** Wheel: aim at a target instead of setting the amount outright. */
    @Redirect(method = "mouseScrolled",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/widget/EntryListWidget;setScrollAmount(D)V"))
    private void s1mp1e$wheel(EntryListWidget<?> self, double requested) {
        s1mp1e$glideTo(requested);
    }

    /** Arrow-key navigation glides just like the wheel. */
    @Redirect(method = "scroll(I)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/widget/EntryListWidget;setScrollAmount(D)V"))
    private void s1mp1e$keyboard(EntryListWidget<?> self, double requested) {
        s1mp1e$glideTo(requested);
    }

    /** Any direct set that is not our own step (drag, centring, clamp) cancels the glide. */
    @Inject(method = "setScrollAmount", at = @At("HEAD"))
    private void s1mp1e$externalSet(double amount, CallbackInfo ci) {
        if (!s1mp1e$stepping) { s1mp1e$scrollTarget = Double.NaN; s1mp1e$scrollNs = 0L; }
    }

    @Unique
    private void s1mp1e$glideTo(double requested) {
        double cur = getScrollAmount();
        double base = Double.isNaN(s1mp1e$scrollTarget) ? cur : s1mp1e$scrollTarget;
        double t = base + (requested - cur);
        int max = getMaxScroll();
        if (Double.isNaN(s1mp1e$scrollTarget)) s1mp1e$scrollNs = System.nanoTime();
        s1mp1e$scrollTarget = Math.max(0.0, Math.min(max, t));
    }

    /** Step the pending scroll once per frame, before the list draws / hit-tests this frame. */
    @Inject(method = "render", at = @At("HEAD"))   // a list is not a ClickableWidget here; it draws in render
    private void s1mp1e$stepScroll(MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (Double.isNaN(s1mp1e$scrollTarget)) return;
        long now = System.nanoTime();
        double dt = s1mp1e$scrollNs == 0L ? 1.0 / 60.0 : Math.min(0.05, (now - s1mp1e$scrollNs) / 1.0e9);
        s1mp1e$scrollNs = now;
        double tgt = Math.max(0.0, Math.min(getMaxScroll(), s1mp1e$scrollTarget));
        double cur = getScrollAmount();
        double next = cur + (tgt - cur) * (1.0 - Math.exp(-dt / S1MP1E_SCROLL_TAU));
        boolean done = Math.abs(tgt - next) < 0.35;
        if (done) next = tgt;
        s1mp1e$stepping = true;
        try {
            setScrollAmount(next);
        } finally {
            s1mp1e$stepping = false;
        }
        if (done) { s1mp1e$scrollTarget = Double.NaN; s1mp1e$scrollNs = 0L; }
    }

    // ---- gliding selection box --------------------------------------------

    @Unique private double s1mp1e$selEasedContentY = Double.NaN;   // eased box top, content coordinates
    @Unique private long s1mp1e$selNs;

    /**
     * 1.17.1 has no {@code renderEntry} / {@code drawSelectionHighlight}: {@code renderList} paints the selected row's
     * box inline (two tessellator quads) from the local {@code m = y + index * itemHeight + headerHeight} (slot 13,
     * javap-checked; that local feeds nothing but those quads). Right before the box is drawn — the
     * {@code RenderSystem.disableTexture()} call that opens the block, reached only for the selected row — the local
     * is replaced by its eased value: eased in CONTENT coordinates ({@code m + scroll}) so the box glides between rows
     * on a selection change but stays glued to the content while scrolling. The box itself is still vanilla's.
     */
    @org.spongepowered.asm.mixin.injection.ModifyVariable(method = "renderList",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;disableTexture()V", ordinal = 0, remap = false),
            index = 13)
    private int s1mp1e$glidingSelection(int y) {
        double scroll = getScrollAmount();
        double targetContent = y + scroll;
        long now = System.nanoTime();
        if (Double.isNaN(s1mp1e$selEasedContentY)) {
            s1mp1e$selEasedContentY = targetContent;                 // first selection: appear in place
        } else {
            double dt = s1mp1e$selNs == 0L ? 1.0 / 60.0 : Math.min(0.05, (now - s1mp1e$selNs) / 1.0e9);
            s1mp1e$selEasedContentY += (targetContent - s1mp1e$selEasedContentY)
                    * (1.0 - Math.exp(-dt / S1MP1E_SEL_TAU));
            if (Math.abs(targetContent - s1mp1e$selEasedContentY) < 0.4) s1mp1e$selEasedContentY = targetContent;
        }
        s1mp1e$selNs = now;
        return (int) Math.round(s1mp1e$selEasedContentY - scroll);
    }

    // ---- entries arriving later cascade in --------------------------------

    /** Critically damped spring rate of an arriving entry (~0.3 s settle) and how far it rises (26.2 values). */
    @Unique private static final float S1MP1E_ENTER_W = 18.0f;
    @Unique private static final float S1MP1E_ENTER_RISE = 6.0f;
    @Unique private final java.util.IdentityHashMap<Object, Long> s1mp1e$born = new java.util.IdentityHashMap<>();
    @Unique private boolean s1mp1e$primed;

    @Shadow public abstract java.util.List<?> children();

    /**
     * Stamp entries the first time they are seen. Those present on the list's very first frame count as settled; an
     * entry that turns up later (the world list after its async load, LAN servers, a search refill …) gets a birth time,
     * staggered 30 ms per entry within one batch.
     */
    @Inject(method = "renderList", at = @At("HEAD"))
    private void s1mp1e$trackEntries(MatrixStack matrices, int x, int y, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        java.util.List<?> ch = children();
        if (!s1mp1e$primed) {
            for (Object e : ch) s1mp1e$born.put(e, Long.MIN_VALUE);
            s1mp1e$primed = true;
            return;
        }
        long now = System.nanoTime();
        int k = 0;
        for (Object e : ch) {
            if (!s1mp1e$born.containsKey(e)) { s1mp1e$born.put(e, now + Math.min(k * 30_000_000L, 240_000_000L)); k++; }
        }
        if (s1mp1e$born.size() > ch.size() + 32) {           // forget removed entries
            java.util.IdentityHashMap<Object, Long> keep = new java.util.IdentityHashMap<>();
            for (Object e : ch) keep.put(e, s1mp1e$born.get(e));
            s1mp1e$born.clear();
            s1mp1e$born.putAll(keep);
        }
    }

    /**
     * A newly arrived entry: draw it risen-from-below and faded ({@link dev.s1mp1e.client.gui.GuiAlpha}), easing in.
     * 1.17.1: the entry's draw is the one {@code Entry.render(...)} call inside {@code renderList} (no
     * {@code renderEntry}), wrapped here — the receiver is the entry itself.
     */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "renderList",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/widget/EntryListWidget$Entry;"
                            + "render(Lnet/minecraft/client/util/math/MatrixStack;IIIIIIIZF)V"))
    private void s1mp1e$entryEnter(EntryListWidget.Entry<?> entry, MatrixStack matrices, int index, int y, int x, int entryWidth,
                                   int entryHeight, int mouseX, int mouseY, boolean hovered, float delta,
                                   com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> op) {
        Long born = s1mp1e$born.isEmpty() ? null : s1mp1e$born.get(entry);
        if (born == null || born == Long.MIN_VALUE) {
            op.call(entry, matrices, index, y, x, entryWidth, entryHeight, mouseX, mouseY, hovered, delta);
            return;
        }
        float t = (System.nanoTime() - born) / 1.0e9f;
        float p = t <= 0f ? 0f : 1f - (1f + S1MP1E_ENTER_W * t) * (float) Math.exp(-S1MP1E_ENTER_W * t);
        if (p >= 0.998f) {
            s1mp1e$born.put(entry, Long.MIN_VALUE);
            op.call(entry, matrices, index, y, x, entryWidth, entryHeight, mouseX, mouseY, hovered, delta);
            return;
        }
        float inv = 1f - p;
        dev.s1mp1e.client.gui.GuiAlpha.push(matrices, 1f - inv * inv);
        matrices.push();
        matrices.translate(0f, S1MP1E_ENTER_RISE * inv, 0f);
        try {
            op.call(entry, matrices, index, y, x, entryWidth, entryHeight, mouseX, mouseY, hovered, delta);
        } finally {
            dev.s1mp1e.client.gui.GuiAlpha.pop(matrices);
            matrices.pop();
        }
    }
}
