package dev.s1mp1e.o.glass.hook;

import net.minecraft.client.gui.widget.ListWidget;
import org.lwjgl.input.Mouse;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Smooth wheel scrolling for every vanilla list ({@link ListWidget}: world / server / language / resource-pack / stats /
 * mod lists …) — group 6, the 1.12.2 counterpart of mc1144's {@code ListMotionMixin} (26.2 {@code ScrollAreaSmooth}).
 *
 * <p>Vanilla's {@code ListWidget.handleMouse} jumps {@code amountScrolled} by half a slot per notch. The coremod
 * brackets that method: {@link #before} at its head snapshots {@code amountScrolled}; {@link #after} before every
 * RETURN sees whether THIS input event was a wheel notch that moved it — if so the jump is undone and added to a glide
 * target instead (notches accumulate: a fast flick travels further). {@link #step} at the head of
 * {@code ListWidget.render} eases the real {@code amountScrolled} toward the target once per frame ({@code τ ≈ 85 ms},
 * no overshoot), before the list draws and hit-tests, so a click mid-glide lands on what is drawn. Any other change
 * (scrollbar drag, programmatic set, a clamp after a resize) cancels the glide, so every non-wheel path stays vanilla.
 * Pure float maths; no glass pipeline needed.
 *
 * <p>1.8.9：從 mc1122 移植。{@code getMaxScroll} 在 1.8.9 沒有 MCP 名稱，直接用 {@code getMaxScroll}；
 * 捲動量欄位 {@code amountScrolled}（scrollAmount）和每格滾輪跳半格（{@code slotHeight / 2}）都和 1.12.2 一樣。
 */
public final class ListMotionHook {

    private ListMotionHook() {}

    private static final double TAU = 0.085;

    private static final class State {
        float target = Float.NaN;
        float lastSet = Float.NaN;
        long ns;
    }

    private static final Map<ListWidget, State> STATES = new WeakHashMap<ListWidget, State>();
    private static Field fAmount;
    private static boolean resolved;
    private static float before;
    private static ListWidget beforeOf;

    private static Field amount() {
        if (!resolved) {
            resolved = true;
            for (String n : new String[]{dev.s1mp1e.o.util.Names.of("scrollAmount", "f_41255119"), "amountScrolled"}) {
                try { Field f = ListWidget.class.getDeclaredField(n); f.setAccessible(true); fAmount = f; break; }
                catch (NoSuchFieldException ignored) {}
            }
        }
        return fAmount;
    }

    /** handleMouseInput HEAD. */
    public static void before(ListWidget list) {
        try {
            Field f = amount();
            if (f == null) return;
            before = f.getFloat(list);
            beforeOf = list;
        } catch (Throwable ignored) {}
    }

    /** handleMouseInput, before every RETURN. */
    public static void after(ListWidget list) {
        try {
            Field f = amount();
            if (f == null || beforeOf != list) return;
            beforeOf = null;
            float now = f.getFloat(list);
            if (now == before || (Mouse.getEventDWheel() == 0 && !devWheel)) return;   // not a wheel move
            State st = state(list);
            float base = Float.isNaN(st.target) ? before : st.target;
            if (Float.isNaN(st.target)) st.ns = System.nanoTime();
            float max = Math.max(0, list.getMaxScroll());
            st.target = Math.max(0f, Math.min(max, base + (now - before)));
            f.setFloat(list, before);                                  // undo the jump: the frame step glides there
            st.lastSet = before;
        } catch (Throwable ignored) {}
    }

    /** drawScreen HEAD: one eased step toward the wheel target. */
    public static void step(ListWidget list) {
        try {
            State st = STATES.get(list);
            if (st == null || Float.isNaN(st.target)) return;
            Field f = amount();
            if (f == null) return;
            float cur = f.getFloat(list);
            if (!Float.isNaN(st.lastSet) && Math.abs(cur - st.lastSet) > 0.01f) {   // someone else moved it: cancel
                st.target = Float.NaN; st.lastSet = Float.NaN; st.ns = 0L;
                return;
            }
            long now = System.nanoTime();
            double dt = st.ns == 0L ? 1.0 / 60.0 : Math.min(0.05, (now - st.ns) / 1.0e9);
            st.ns = now;
            float max = Math.max(0, list.getMaxScroll());
            float tgt = Math.max(0f, Math.min(max, st.target));
            float next = (float) (cur + (tgt - cur) * (1.0 - Math.exp(-dt / TAU)));
            boolean done = Math.abs(tgt - next) < 0.35f;
            if (done) next = tgt;
            f.setFloat(list, next);
            st.lastSet = next;
            if (done) { st.target = Float.NaN; st.lastSet = Float.NaN; st.ns = 0L; }
        } catch (Throwable ignored) {}
    }

    private static State state(ListWidget list) {
        State st = STATES.get(list);
        if (st == null) { st = new State(); STATES.put(list, st); }
        return st;
    }

    /** DEV-only (DevShot cannot inject an LWJGL wheel event): treat the bracketed change as a wheel notch. */
    public static boolean devWheel;

    /**
     * DEV-only: one scripted wheel move of {@code notches} (positive = down) through the exact hook path vanilla's
     * {@code handleMouseInput} takes — before, vanilla's own half-slot jump, after.
     */
    public static void devNotches(ListWidget list, int notches) {
        try {
            Field f = amount();
            if (f == null) return;
            before(list);
            f.setFloat(list, f.getFloat(list) + notches * list.getEntryHeight() / 2f);
            devWheel = true;
            after(list);
        } catch (Throwable ignored) {
        } finally {
            devWheel = false;
        }
    }

    /** DevShot: true while a list glide is in flight (for a burst log). */
    public static boolean gliding(ListWidget list) {
        State st = STATES.get(list);
        return st != null && !Float.isNaN(st.target);
    }
}
