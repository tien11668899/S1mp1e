package dev.s1mp1e.glass.render;

import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.anim.Spring;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.slot.Slot;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared container glass — the frosted panel + slot lattice + quick-craft drag highlight + hover pill drawn behind a
 * {@code HandledScreen}'s contents. This is the exact draw {@code HandledScreenGlassMixin} does for the generic
 * (list-less) containers, hoisted into a reusable helper so the list screens — stonecutter / loom / merchant — can draw
 * the SAME glass from a targeted panel-texture redirect INSIDE their own {@code drawBackground} while still letting their
 * recipe / trade list, buttons and scroller draw on top. (The generic mixin used to SWALLOW the whole
 * {@code drawBackground}, which erased those lists — the 1.19.2 / 1.21.1 blocker; it now also runs the screen's own
 * drawBackground and drops only the body blit via {@link #beginBodySuppress}.)
 *
 * <p>1.19.2: no {@code DrawContext} — the glass is immediate GL at absolute GUI coordinates, the {@code MatrixStack}
 * argument is only kept so the call sites read like the newer lines'.
 *
 * <p>Every constant, spring rig and easing here is byte-for-byte the value in {@code HandledScreenGlassMixin} (itself
 * the 26.2 {@code GlassContainerHandler} / {@code GlassPanels}). A {@link State} instance holds one screen's open fade,
 * hover springs and drag-highlight fades; each list-screen mixin keeps one as a {@code @Unique} field.
 */
public final class ContainerGlass {

    private ContainerGlass() {}

    // ---- 26.2 constants (identical to HandledScreenGlassMixin / GlassContainerHandler) ----
    private static final float HOVER_FADE_S = 0.10f;
    private static final float DRAG_IN_MS   = 90f;
    private static final float DRAG_OUT_MS  = 150f;
    private static final float DRAG_DROP    = 0.02f;

    // ---- body-blit suppression for the GENERIC container path -------------------------------------------------
    // HandledScreenGlassMixin draws the glass panel, then RUNS the screen's own drawBackground (26.2's
    // ContainerScreensGlassMixin keeps everything but the body blit): the furnace flame/arrow, brewing bubbles, the
    // enchanting options + book, anvil/grindstone error X, smithing ghost icons + armor stand, crafter disabled-slot
    // overlays, mount saddle/armor slot art + preview, the survival player model all stay vanilla on top of the glass.
    // Only the container body texture is dropped: every body blit in vanilla is a FULL-WIDTH strip of the panel
    // (x == panel x, width == backgroundWidth — one strip for most screens, two for the 9xN chest), which is what
    // DrawableBodyBlitMixin cancels while a suppression window is open.
    private static boolean suppressing;
    private static int supX, supY, supW, supH;

    /** Open the body-blit suppression window for the panel rect (generic container path only). */
    public static void beginBodySuppress(int x, int y, int w, int h) {
        suppressing = true;
        supX = x; supY = y; supW = w; supH = h;
    }

    public static void endBodySuppress() { suppressing = false; }

    /** True while a generic container's own drawBackground runs over the glass panel. */
    public static boolean suppressing() { return suppressing; }

    /** True when a texture blit to {@code [x1,x2) x [y1,y2)} is a full-width strip of the suppressed container body. */
    public static boolean isSuppressedBody(int x1, int x2, int y1, int y2) {
        return suppressing && x1 == supX && x2 - x1 == supW && y1 >= supY && y2 <= supY + supH && y2 > y1;
    }

    /** Per-screen-instance glass state (open fade + hover spring rig + drag-highlight fades). */
    public static final class State {
        Fade openFade;
        boolean opened;
        Spring hx1, hx2, hy1, hy2;
        boolean hoverActive;
        Fade hoverFade;
        long hoverNanos;
        HashMap<Long, Fade> dragAlpha;

        /** The panel's open fade (1 before the first draw), for a list screen's own extras (scrollbar, edge scrims). */
        public float fade() { return openFade == null ? 1f : openFade.value(); }
    }

    /**
     * Draw the container glass at the panel origin. The caller has already decided the glass pipeline is usable and is
     * calling this from the panel-texture redirect inside the screen's own {@code drawBackground} (dim still pending,
     * list not yet drawn). Flushes the deferred dim, grabs a fresh backdrop (rule 4), advances the open fade, then draws
     * panel + lattice + drag + hover — identical to the generic mixin.
     */
    public static void draw(State st, MatrixStack context, int gl, int gt, int xs, int ys,
                            List<Slot> slots, Set<Slot> dragSlots, boolean dragging, int mouseX, int mouseY) {
        if (st.openFade == null) {
            st.openFade = new Fade(0f, PanelGhost.FADE_MS);
            st.hoverFade = new Fade(0f, HOVER_FADE_S * 1000f);
            st.dragAlpha = new HashMap<Long, Fade>();
        }

        // Flush the deferred screen-darkening fill so the glass + lattice land ON TOP of the dim, and so grabNow
        // captures world+dim as the backdrop the panel refracts (post-1.17 deferred-dim regression, see the generic
        // mixin). grabNow (not the deduped grab) -> a fresh backdrop every frame, so no self-sampling / hi-fps flicker.
        GuiFlush.flush();
        // REUSE the frame's undimmed world capture exactly as the generic container path does (see
        // HandledScreenGlassMixin) — a fresh grab here would capture the screen dim that the list screen's own
        // drawBackground has just drawn, and the stonecutter / loom panel would be darker than a chest's.
        if (!SceneCapture.hasBackdrop()) SceneCapture.grabNow();

        long now = System.nanoTime();
        if (!st.opened) {
            st.opened = true;
            st.openFade.snap(0f);
            st.openFade.to(1f);
            PanelGhost.cancel();
        }
        float fade = st.openFade.value();

        PanelGhost.beginFrame();
        PanelGhost.remember(gl, gt, xs, ys);
        GlassRenderer.panel(gl, gt, gl + xs, gt + ys, fade);

        drawLattice(slots, gl, gt, fade);
        drawDrag(st, dragSlots, dragging, gl, gt);
        drawHover(st, slots, gl, gt, mouseX, mouseY, now);
    }

    /**
     * Only the slot layer of the container glass — lattice + quick-craft drag highlight + hover pill — for a screen that
     * draws its own panel glass (the creative screen's fused body+tab sheet). Same constants and springs as
     * {@link #draw}; 26.2 draws these for the creative grid too ("the slot lattice stays at the panel's slots").
     */
    public static void drawSlotLayer(State st, int gl, int gt, List<Slot> slots, Set<Slot> dragSlots,
                                     boolean dragging, int mouseX, int mouseY, float fade) {
        if (st.hoverFade == null) st.hoverFade = new Fade(0f, HOVER_FADE_S * 1000f);
        if (st.dragAlpha == null) st.dragAlpha = new HashMap<Long, Fade>();
        drawLattice(slots, gl, gt, fade);
        drawDrag(st, dragSlots, dragging, gl, gt);
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
                if (pos.contains(key(sx + 18, sy))) mask |= 1;
                if (pos.contains(key(sx - 18, sy))) mask |= 2;
                if (pos.contains(key(sx, sy + 18))) mask |= 4;
                if (pos.contains(key(sx, sy - 18))) mask |= 8;
                GlassRenderer.batchQuad(gl + sx - 1, gt + sy - 1,
                                        gl + sx + 17, gt + sy + 17,
                                        0f, 1f, 1f, fade, (mask * 17) / 255f);
            }
        } finally {
            GlassRenderer.endBatch();
        }
    }

    /** Quick-craft (right-drag distribute) highlight — 26.2 easing verbatim. */
    private static void drawDrag(State st, Set<Slot> dragSlots, boolean cursorDragging, int gl, int gt) {
        HashSet<Long> current = new HashSet<Long>();
        if (cursorDragging && dragSlots != null) {
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
            GlassRenderer.glass(bx - 1, by - 1, bx + 17, by + 17,
                                4f, 1.0f, 1.0f, a * 0.5f, GlassRenderer.FROST_NONE);
        }
    }

    /** Hover pill on the two-axis spring rig (lead 55 / trail 30, critical). */
    private static void drawHover(State st, List<Slot> slots, int gl, int gt, int mouseX, int mouseY, long now) {
        Slot hov = null;
        int px = mouseX - gl, py = mouseY - gt;
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            int sx = s.x, sy = s.y;
            if (px >= sx - 1 && px < sx + 17 && py >= sy - 1 && py < sy + 17 && s.isEnabled()) {
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
        GlassRenderer.glass(lox - 10f, loy - 10f, hix + 10f, hiy + 10f,
                            6f, 1.0f, 0.12f, st.hoverFade.value(), GlassRenderer.FROST_NONE);
    }

    private static long key(int x, int y) {
        return ((long) x << 32) | (y & 0xffffffffL);
    }
}
