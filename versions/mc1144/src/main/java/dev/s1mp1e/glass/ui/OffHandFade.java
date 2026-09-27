package dev.s1mp1e.glass.ui;

import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.anim.Spring;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * Off-hand slot show / hide + item-swap animation, shared verbatim by the legacy
 * fixed-function hotbar mixins (1.13.2 Legacy Fabric, 1.14.4 Fabric).
 *
 * <p>The off-hand spec:
 * <ul>
 *   <li><b>empty &lt;-&gt; filled</b>: the WHOLE slot (glass + item) fades in or out together.</li>
 *   <li><b>filled -&gt; filled</b> (both hands hold items and the off-hand item type changes on a
 *       hand swap): the slot glass STAYS and only the item icons cross-transition
 *       (outgoing out, incoming in).</li>
 *   <li>duration ~150 ms ({@link Spring#FADE_MS}), a short Apple-like ramp.</li>
 * </ul>
 *
 * <p><b>Fixed-function limitation.</b> On 1.13.2 / 1.14.4 the GUI item is drawn by the vanilla
 * item renderer, which forces {@code color4f(1,1,1,1)} — there is no cheap way to alpha-fade a
 * lit 3D GUI model. The glass slot itself fades with true opacity (the shader takes an alpha),
 * and the item transition is expressed as a centre-anchored SCALE (0..1), the standard
 * scale/opacity stand-in for a fade at this GL tier. The scale is smoothstep-eased so it reads
 * as an ease-in/out, not a linear zoom.
 *
 * <p>Pure state + math, no GL and no MC-render calls, so it compiles identically on both
 * versions; the mixin owns the actual draw. Fed once per frame with the live off-hand stack.
 */
public final class OffHandFade {

    private final Fade slot  = new Fade(0f, Spring.FADE_MS);   // glass opacity 0..1
    private final Fade cross = new Fade(1f, Spring.FADE_MS);   // 0 = prev, 1 = cur (both-full swap)
    private ItemStack cur  = ItemStack.EMPTY;                  // current / incoming item
    private ItemStack prev = null;                             // outgoing item (fade-out / cross)
    private boolean crossing = false;

    /** Feed the live off-hand stack once per frame, BEFORE querying the getters below. */
    public void frame(ItemStack off) {
        boolean hasOff  = off != null && !off.isEmpty();
        boolean hadCur  = cur != null && !cur.isEmpty();
        Item newItem = hasOff ? off.getItem()  : null;
        Item curItem = hadCur ? cur.getItem()  : null;

        if (newItem != curItem) {              // the off-hand item identity changed
            if (hadCur && hasOff) {            // both full -> swap: keep the slot, cross the icons
                prev = cur;
                cur  = off.copy();
                crossing = true;
                cross.snap(0f);
                cross.to(1f);
            } else {                           // empty <-> filled: whole slot fades
                prev = hadCur ? cur : null;    // remember the outgoing item to draw while it fades out
                cur  = hasOff ? off.copy() : ItemStack.EMPTY;
                crossing = false;
                cross.snap(1f);
            }
        }

        slot.to(hasOff ? 1f : 0f);
        if (crossing && cross.isIdle()) {      // swap finished: drop the outgoing item
            crossing = false;
            prev = null;
        }
    }

    /** Glass-slot opacity, 0..1. */
    public float slotAlpha()    { return slot.value(); }
    /** True while the slot glass should be drawn at all. */
    public boolean slotVisible() { return slot.value() > 0.01f; }
    /** True while a both-hands-full icon swap is animating (slot stays put). */
    public boolean crossing()   { return crossing; }

    // --- item draw plan (up to two items; scales are 0..1 centre-anchored) -----------------

    /** The main item to draw (incoming during a cross, otherwise the visible item). */
    public ItemStack primaryStack() {
        if (crossing) return cur;
        return (cur != null && !cur.isEmpty()) ? cur : prev;   // fade-out draws the cached outgoing
    }

    /** Scale for {@link #primaryStack()}. */
    public float primaryScale() {
        if (crossing) return clamp((cross.value() - 0.5f) * 2f);   // incoming grows over the 2nd half
        return ease(slot.value());                                 // whole-slot fade: follows the glass
    }

    /** The outgoing item during a both-full swap, else null. */
    public ItemStack secondaryStack() { return crossing ? prev : null; }

    /** Scale for {@link #secondaryStack()}. */
    public float secondaryScale() {
        if (!crossing) return 0f;
        return clamp(1f - cross.value() * 2f);                     // outgoing shrinks over the 1st half
    }

    private static float clamp(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
    private static float ease(float t)  { t = clamp(t); return t * t * (3f - 2f * t); }
}
