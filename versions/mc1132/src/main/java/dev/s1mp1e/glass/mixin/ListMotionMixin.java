package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.gui.widget.ListWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Menu-list motion for every {@link ListWidget} (world / server / language / resource-pack / key-binding lists,
 * option lists, stats, social, …) — the 1.13.2 port of the 1.20.1 / 1.21.1 lines' mixin (same {@code ListWidget}
 * methods where 1.13.2 has them, javap-checked: {@code mouseScrolled(DDD)}, the private {@code scroll(I)},
 * {@code renderList(MatrixStack,IIIIF)}; 1.13.2 has no {@code renderEntry} / {@code drawSelectionHighlight} yet — the
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
 * <p><b>1.13.2.</b> The old {@code ListWidget} (GuiSlot) keeps its scroll amount in the field {@code field_20083}
 * and writes it directly: {@code mouseScrolled(double)} and {@code scroll(int)} each have ONE {@code PUTFIELD}, which
 * is redirected into the glide target. There is no setter to watch, so "somebody else moved the list" (a scrollbar
 * drag, a clamp after a resize) is noticed by the field no longer holding the value our last step wrote — that
 * cancels the glide, so every non-wheel path keeps vanilla's exact behaviour. The selected row's box is drawn inline
 * in {@code method_6704} (renderList) from the row-top local (slot 10), which the row's own draw reads as well: the
 * local is eased right before the box ({@code GlStateManager.disableTexture()}) and put back right after it
 * ({@code enableTexture()}). Entries are objects only in {@code EntryListWidget}: the arrival cascade lives in
 * {@code EntryListMotionMixin}. Pure {@code double}/{@code float} maths — no glass pipeline needed.
 */
@Mixin(ListWidget.class)
public abstract class ListMotionMixin {
    /** Wheel/keyboard scroll ease time constant (26.2's {@code ScrollAreaSmoothMixin.LG_TAU}). */
    @Unique private static final double S1MP1E_SCROLL_TAU = 0.085;
    /** Selection-box glide time constant (26.2's {@code SelectionListGlideMixin.LG_TAU}). */
    @Unique private static final float S1MP1E_SEL_TAU = 0.07f;

    @Shadow protected double field_20083;                 // the scroll amount
    @Shadow public abstract int getMaxScroll();

    // ---- smooth scrolling -------------------------------------------------
    @Unique private double s1mp1e$scrollTarget = Double.NaN;
    @Unique private double s1mp1e$lastWritten = Double.NaN;
    @Unique private long s1mp1e$scrollNs;

    /** Wheel: aim at a target instead of setting the amount outright. */
    @Redirect(method = "mouseScrolled(D)Z",
            at = @At(value = "FIELD", opcode = org.objectweb.asm.Opcodes.PUTFIELD,
                     target = "Lnet/minecraft/client/gui/widget/ListWidget;field_20083:D"))
    private void s1mp1e$wheel(ListWidget self, double requested) {
        s1mp1e$glideTo(requested);
    }

    /** Arrow-key navigation glides just like the wheel. */
    @Redirect(method = "scroll(I)V",
            at = @At(value = "FIELD", opcode = org.objectweb.asm.Opcodes.PUTFIELD,
                     target = "Lnet/minecraft/client/gui/widget/ListWidget;field_20083:D"))
    private void s1mp1e$keyboard(ListWidget self, double requested) {
        s1mp1e$glideTo(requested);
    }

    @Unique
    private void s1mp1e$glideTo(double requested) {
        double cur = this.field_20083;
        boolean idle = Double.isNaN(s1mp1e$scrollTarget);
        double base = idle ? cur : s1mp1e$scrollTarget;
        double t = base + (requested - cur);
        int max = Math.max(0, getMaxScroll());
        if (idle) { s1mp1e$scrollNs = System.nanoTime(); s1mp1e$lastWritten = cur; }
        s1mp1e$scrollTarget = Math.max(0.0, Math.min(max, t));
    }

    /** Step the pending scroll once per frame, before the list draws / hit-tests this frame. */
    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$stepScroll(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (Double.isNaN(s1mp1e$scrollTarget)) return;
        double cur = this.field_20083;
        if (Math.abs(cur - s1mp1e$lastWritten) > 1.0e-6) {   // moved by somebody else (drag, clamp): theirs wins
            s1mp1e$scrollTarget = Double.NaN;
            s1mp1e$scrollNs = 0L;
            return;
        }
        long now = System.nanoTime();
        double dt = s1mp1e$scrollNs == 0L ? 1.0 / 60.0 : Math.min(0.05, (now - s1mp1e$scrollNs) / 1.0e9);
        s1mp1e$scrollNs = now;
        double tgt = Math.max(0.0, Math.min(Math.max(0, getMaxScroll()), s1mp1e$scrollTarget));
        double next = cur + (tgt - cur) * (1.0 - Math.exp(-dt / S1MP1E_SCROLL_TAU));
        boolean done = Math.abs(tgt - next) < 0.35;
        if (done) next = tgt;
        this.field_20083 = next;
        s1mp1e$lastWritten = next;
        if (done) { s1mp1e$scrollTarget = Double.NaN; s1mp1e$scrollNs = 0L; }
    }

    // ---- gliding selection box --------------------------------------------
    @Unique private double s1mp1e$selEasedContentY = Double.NaN;   // eased box top, content coordinates
    @Unique private long s1mp1e$selNs;
    @Unique private int s1mp1e$rowTop;

    /**
     * The selected row's box: {@code method_6704} paints it inline (two tessellator quads) from the row-top local
     * (slot 10, javap-checked). Right before the box is drawn — the {@code GlStateManager.disableTexture()} call that
     * opens the block, reached only for the selected row — the local is replaced by its eased value: eased in CONTENT
     * coordinates (row top + scroll) so the box glides between rows on a selection change but stays glued to the
     * content while scrolling. The box itself is still vanilla's.
     */
    @ModifyVariable(method = "method_6704",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;disableTexture()V", ordinal = 0),
            index = 10)
    private int s1mp1e$glidingSelection(int y) {
        s1mp1e$rowTop = y;
        double scroll = this.field_20083;
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

    /** ... and the row itself (drawn right after the box from the same local) stays where it is. */
    @ModifyVariable(method = "method_6704",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;enableTexture()V", ordinal = 0,
                     shift = At.Shift.AFTER),
            index = 10)
    private int s1mp1e$rowStays(int y) {
        return s1mp1e$rowTop;
    }
}
