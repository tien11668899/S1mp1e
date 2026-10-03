package dev.s1mp1e.glass.render;

import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.anim.Spring;
import net.minecraft.inventory.slot.Slot;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * System 2 container glass as one reusable routine: frosted panel + slot-separator lattice + quick-craft drag highlight
 * + the two-axis hover pill. It is the EXACT body of {@code HandledScreenGlassMixin}'s original swallow path, lifted out
 * so the screens that must run their OWN {@code drawBackground} (stonecutter, loom — their recipe/pattern lists live
 * inside it) draw the SAME glass from a redirect of their body blit, instead of the generic path swallowing their whole
 * background (the KNOWN BLOCKER that erased the stonecutter / loom lists).
 *
 * <p>All constants, spring rigs and easings are byte-for-byte the previous in-mixin values (26.2's
 * {@code GlassContainerHandler}); the generic container path now calls {@link #draw} too, so every container looks
 * identical to before. Per-screen motion lives in a {@link State} owned by the screen instance (a fresh screen = fresh
 * state = snapped open fade + no stale hover).
 *
 * <p><b>Backdrop (R4).</b> A forced {@link SceneCapture#grabNow()} is taken the instant before the panel draws: in
 * 1.13.2 the in-game HUD does not render behind an open container, so the panel is the frame-primary surface and MUST
 * re-copy a fresh backdrop each frame (the deduped {@code grab()} would fold onto a stale, wrong-stage snapshot at high
 * fps -> the 1.13.2 flicker root cause).
 */
public final class ContainerGlass {

    private ContainerGlass() {}

    // ---- 26.2 constants (identical to GlassContainerHandler) ---------------
    private static final float HOVER_FADE_S = 0.10f;
    private static final float DRAG_IN_MS   = 90f;
    private static final float DRAG_OUT_MS  = 150f;
    private static final float DRAG_DROP    = 0.02f;

    /** Per-screen-instance motion state (fresh with each opened container). */
    public static final class State {
        Fade openFade;
        boolean opened;
        Spring hx1, hx2, hy1, hy2;
        boolean hoverActive;
        Fade hoverFade;
        long hoverNanos;
        HashMap<Long, Fade> dragAlpha;

        void ensure() {
            if (openFade == null) {
                openFade = new Fade(0f, PanelGhost.FADE_MS);
                hoverFade = new Fade(0f, HOVER_FADE_S * 1000f);
                dragAlpha = new HashMap<Long, Fade>();
            }
        }

        /** The panel open-fade value (1 when never drawn). */
        public float fade() { return openFade == null ? 1f : openFade.value(); }
    }

    /**
     * Panel + lattice + drag + hover for a container whose body PNG sits at {@code (gl,gt,xs,ys)}. Caller has already
     * checked {@code GlassProgram.ensureReady() && GlassProgram.usable()}. Registers the rect with {@link PanelGhost}.
     * Returns the open-fade value.
     */
    public static float draw(State st, int gl, int gt, int xs, int ys, List<Slot> slots,
                             Collection<Slot> dragSlots, boolean dragging, int mouseX, int mouseY) {
        st.ensure();

        // Frame-primary surface: re-copy a fresh backdrop every frame (grabNow, NOT the deduped grab) so a high-fps
        // paused container can never fold the panel grab onto a stale tooltip snapshot (the 1.13.2 flicker fix, R4).
        SceneCapture.grabNow();

        if (!st.opened) {
            // Fresh screen instance: snap the open fade and cancel any in-flight close ghost.
            st.opened = true;
            st.openFade.snap(0f);
            st.openFade.to(1f);
            PanelGhost.cancel();
        }
        float fade = st.openFade.value();

        PanelGhost.beginFrame();
        PanelGhost.remember(gl, gt, xs, ys);
        GlassRenderer.panel(gl, gt, gl + xs, gt + ys, fade);

        drawSlotLayer(st, gl, gt, slots, dragSlots, dragging, mouseX, mouseY, fade);
        return fade;
    }

    /** Lattice + drag highlight + hover pill only (the creative fused sheet draws its own panel). */
    public static void drawSlotLayer(State st, int gl, int gt, List<Slot> slots, Collection<Slot> dragSlots,
                                     boolean dragging, int mouseX, int mouseY, float fade) {
        st.ensure();
        drawLattice(slots, gl, gt, fade);
        drawDrag(st, gl, gt, dragSlots, dragging);
        drawHover(st, slots, gl, gt, mouseX, mouseY, System.nanoTime());
    }

    /** Slot-separator lattice: one cell per slot with a 4-bit neighbour mask. */
    private static void drawLattice(List<Slot> slots, int gl, int gt, float fade) {
        HashSet<Long> pos = new HashSet<Long>();
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            pos.add(key(s.x, s.y));
        }
        if (!GlassRenderer.beginBatch(GlassProgram.LINE)) return;
        try {
            for (int i = 0; i < slots.size(); i++) {
                Slot s = slots.get(i);
                int sx = s.x, sy = s.y;
                int mask = 0;
                if (pos.contains(key(sx + 18, sy))) mask |= 1; // E
                if (pos.contains(key(sx - 18, sy))) mask |= 2; // W
                if (pos.contains(key(sx, sy + 18))) mask |= 4; // S
                if (pos.contains(key(sx, sy - 18))) mask |= 8; // N
                GlassRenderer.batchQuad(gl + sx - 1, gt + sy - 1,
                                        gl + sx + 17, gt + sy + 17,
                                        0f, 1f, 1f, fade, (mask * 17) / 255f);
            }
        } finally {
            GlassRenderer.endBatch();
        }
    }

    /** Quick-craft (right-drag distribute) highlight — 26.2 easing verbatim. */
    private static void drawDrag(State st, int gl, int gt, Collection<Slot> dragSlots, boolean dragActive) {
        HashSet<Long> current = new HashSet<Long>();
        if (dragActive && dragSlots != null) {
            for (Object o : dragSlots) {
                if (!(o instanceof Slot)) continue;
                Slot s = (Slot) o;
                Long k = Long.valueOf(key(gl + s.x, gt + s.y));
                current.add(k);
                Fade f = st.dragAlpha.get(k);
                if (f == null) {
                    f = new Fade(0f, DRAG_IN_MS);
                    st.dragAlpha.put(k, f);
                }
                f.to(1f);
            }
        }
        if (st.dragAlpha.isEmpty()) return;

        Iterator<Map.Entry<Long, Fade>> it = st.dragAlpha.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Fade> en = it.next();
            Long k = en.getKey();
            Fade f = en.getValue();
            if (!current.contains(k)) f.to(0f, DRAG_OUT_MS);
            float a = f.value();
            if (a <= DRAG_DROP && f.isIdle()) {
                it.remove();
                continue;
            }
            long kk = k.longValue();
            int bx = (int) (kk >> 32);
            int by = (int) kk;
            // WHITE, non-refractive, full corner radius (lift 1.0 -> pure white), opacity halved to match vanilla's
            // 0x80FFFFFF drag highlight. Pad 6 (as the hover pill) so the rounded corners read clearly.
            GlassRenderer.glass(bx - 1, by - 1, bx + 17, by + 17,
                                6f, 1.0f, 1.0f, a * 0.55f, GlassRenderer.FROST_NONE);
        }
    }

    /** Hover pill on the two-axis spring rig (lead 55 / trail 30, critical). */
    private static void drawHover(State st, List<Slot> slots, int gl, int gt, int mouseX, int mouseY, long now) {
        Slot hov = null;
        int px = mouseX - gl, py = mouseY - gt;
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            int sx = s.x, sy = s.y;
            if (px >= sx - 1 && px < sx + 17 && py >= sy - 1 && py < sy + 17
                    && s.doDrawHoveringEffect()) {
                hov = s;
            }
        }
        boolean hovering = hov != null;

        float dt = (st.hoverNanos == 0L) ? (1f / 60f)
                                         : Math.min(0.1f, (now - st.hoverNanos) * 1.0e-9f);
        st.hoverNanos = now;

        if (hovering) {
            float cx = gl + hov.x + 8f;
            float cy = gt + hov.y + 8f;
            if (st.hx1 == null || (!st.hoverActive && st.hoverFade.value() <= 0.05f)) {
                st.hx1 = new Spring(cx, Spring.OMEGA_SNAP, Spring.DAMPING);
                st.hx2 = new Spring(cx, Spring.OMEGA_MED,  Spring.DAMPING);
                st.hy1 = new Spring(cy, Spring.OMEGA_SNAP, Spring.DAMPING);
                st.hy2 = new Spring(cy, Spring.OMEGA_MED,  Spring.DAMPING);
            } else {
                st.hx1.setTarget(cx); st.hx2.setTarget(cx);
                st.hy1.setTarget(cy); st.hy2.setTarget(cy);
            }
            st.hoverActive = true;
            st.hoverFade.to(1f);
        } else {
            st.hoverActive = false;
            st.hoverFade.to(0f);
            if (st.hoverFade.value() <= 0.004f || st.hx1 == null) return;
        }

        st.hx1.advance(dt); st.hx2.advance(dt);
        st.hy1.advance(dt); st.hy2.advance(dt);

        float lox = Math.min(st.hx1.value(), st.hx2.value());
        float hix = Math.max(st.hx1.value(), st.hx2.value());
        float loy = Math.min(st.hy1.value(), st.hy2.value());
        float hiy = Math.max(st.hy1.value(), st.hy2.value());
        // corner 1.0, neutral lift 0.12, sharp refraction, shadow pad 6, 20x20 pill
        GlassRenderer.glass(lox - 10f, loy - 10f, hix + 10f, hiy + 10f,
                            6f, 1.0f, 0.12f, st.hoverFade.value(), GlassRenderer.FROST_NONE);
    }

    private static long key(int x, int y) {
        return ((long) x << 32) | (y & 0xffffffffL);
    }
}
