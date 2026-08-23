package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.anim.Spring;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.PanelGhost;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.screen.ingame.ContainerScreen;
import net.minecraft.container.Container;
import net.minecraft.container.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * System 2 (container glass) — frosted panel + slot lattice + quick-craft drag
 * highlight + hover pill. The 1.15.2 Fabric port of {@code GlassContainerHandler}
 * (itself LiquidGlass26's {@code GlassPanels} + {@code ContainerScreensGlassMixin}).
 *
 * <h3>Injection point — the Forge {@code BackgroundDrawnEvent} equivalent</h3>
 * The Forge handler drew from {@code GuiScreenEvent.BackgroundDrawnEvent}: after
 * the dark world-dim gradient, before {@code GuiContainer} paints its GUI texture,
 * so the panel ends up UNDER the container texture (invisible while the screen is
 * open for vanilla screens). The one unobstructed view of the panel is the close
 * ghost.
 *
 * <p>Verified against the mapped 1.15.2 jar: {@code ContainerScreen.render(int,int,
 * float)} does NOT call {@code renderBackground} first — its very first background
 * {@code INVOKE} (bytecode offset 16, before {@code super.render}) is the abstract
 * {@code drawBackground(float, int, int)}, and the dark dim gradient lives INSIDE
 * each concrete {@code drawBackground} (which calls {@code renderBackground()} then
 * blits the GUI texture). The behaviour-matching seam is therefore
 * {@code shift = BEFORE} that {@code drawBackground} {@code INVOKE}: the glass draws
 * before the whole background (dim + texture), so the texture covers it during the
 * open state exactly as in Forge, and the visible panel remains the close ghost
 * (driven independently from the HUD pass / {@link PanelGhost}). We cannot inject
 * {@code drawBackground} directly: it is abstract on {@code ContainerScreen}; the
 * concrete surface is {@code render}.
 *
 * <p>The backdrop grabbed here is world-only (the dim has not run yet). That is
 * harmless: the open-state panel is covered anyway, and the only visible surface —
 * the close ghost — grabs its own fresh world backdrop from the HUD pass. At the
 * {@code drawBackground} instruction no {@code translate(x, y)} is active yet
 * ({@code render}'s {@code pushMatrix}/{@code translatef} run later), so drawing at
 * the absolute {@code this.x / this.y} panel origin is correct — as in Forge.
 *
 * <p>As in 1.12.2, the vanilla container texture then paints over the glass (we do
 * not cancel it), so the panel is fully visible only during the close ghost.
 *
 * <h3>1.15.2 tier deltas vs the 1.16.5 source</h3>
 * <ul>
 *   <li>base class {@code HandledScreen} &rarr; {@code ContainerScreen}
 *       ({@code class_465}); the {@code render} descriptor is {@code (IIF)V} — no
 *       {@code MatrixStack} — so the injected signature drops it, and the
 *       {@code drawBackground} target descriptor is {@code (FII)V}
 *       (not {@code (Lnet/minecraft/client/util/math/MatrixStack;FII)V}).</li>
 *   <li>{@code ScreenHandler handler} &rarr; {@code Container container}
 *       ({@code net.minecraft.container.Container}, {@code field_2797});
 *       {@code handler.slots} &rarr; {@code container.slots} ({@code field_7761}).</li>
 *   <li>{@code backgroundWidth/backgroundHeight} &rarr; {@code containerWidth/
 *       containerHeight} ({@code field_2792/field_2779}).</li>
 *   <li>{@code cursorDragging} &rarr; {@code isCursorDragging} ({@code field_2794});
 *       {@code cursorDragSlots} keeps its name ({@code field_2793}).</li>
 *   <li>{@code Slot} is {@code net.minecraft.container.Slot} ({@code class_1735})
 *       and its position fields are {@code xPosition/yPosition}
 *       ({@code field_7873/field_7872}), NOT {@code x/y}; {@code isEnabled()} is
 *       {@code doDrawHoveringEffect()} ({@code method_7682}, unchanged name).</li>
 * </ul>
 * All constants, spring rigs and easings are byte-for-byte the 26.2 values.
 */
@Mixin(ContainerScreen.class)
public abstract class HandledScreenGlassMixin {

    // ---- 26.2 constants (identical to GlassContainerHandler) ---------------
    private static final float HOVER_FADE_S = 0.10f;
    private static final float DRAG_IN_MS   = 90f;
    private static final float DRAG_OUT_MS  = 150f;
    private static final float DRAG_DROP    = 0.02f;

    // ---- yarn shadows (1.15.2 names) ---------------------------------------
    @Shadow protected int x;
    @Shadow protected int y;
    @Shadow protected int containerWidth;
    @Shadow protected int containerHeight;
    @Shadow protected Container container;
    @Shadow protected Set<Slot> cursorDragSlots;
    @Shadow protected boolean isCursorDragging;
    // Own abstract method of ContainerScreen (declared here, not inherited) -> @Shadow
    // resolves. Used for the no-glass fallback and the creative delegation.
    @Shadow protected abstract void drawBackground(float delta, int mouseX, int mouseY);

    // ---- per-screen-instance state (fresh with each opened container) ------
    private Fade s1mp1e$openFade;
    private boolean s1mp1e$opened;
    private Spring s1mp1e$hx1, s1mp1e$hx2, s1mp1e$hy1, s1mp1e$hy2;
    private boolean s1mp1e$hoverActive;
    private Fade s1mp1e$hoverFade;
    private long s1mp1e$hoverNanos;
    private HashMap<Long, Fade> s1mp1e$dragAlpha;

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/ContainerScreen;"
                            + "drawBackground(FII)V"))
    private void s1mp1e$glassPanel(ContainerScreen self, float delta, int mouseX, int mouseY) {
        // SWALLOW the vanilla container PNG (26.2 design). render() already ran
        // renderBackground() (the dim) before this drawBackground call, so the dim
        // survives; only the opaque GUI texture is replaced by glass. (The old
        // @Inject shift=BEFORE drew glass then let the PNG paint over it -> the panel
        // was invisible while open; this @Redirect fixes that.)

        // Creative: run its OWN drawBackground so CreativeGlassMixin can @Redirect the
        // ordinal-0 item-panel blit inside it (tabs/search/scrollbar draw normally).
        if ((Object) this instanceof net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen) {
            this.drawBackground(delta, mouseX, mouseY);
            return;
        }
        // Glass off: draw the vanilla container PNG unchanged.
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            this.drawBackground(delta, mouseX, mouseY);
            return;
        }

        if (s1mp1e$openFade == null) {
            s1mp1e$openFade = new Fade(0f, PanelGhost.FADE_MS);
            s1mp1e$hoverFade = new Fade(0f, HOVER_FADE_S * 1000f);
            s1mp1e$dragAlpha = new HashMap<Long, Fade>();
        }

        int gl = this.x, gt = this.y, xs = this.containerWidth, ys = this.containerHeight;

        // Backdrop = world + dim (renderBackground already ran this frame), grabbed
        // the instant before any glass draws.
        SceneCapture.grab();

        long now = System.nanoTime();
        if (!s1mp1e$opened) {
            // Fresh screen instance: snap the open fade and cancel any in-flight
            // close ghost (the Forge `curScreen != screen` branch).
            s1mp1e$opened = true;
            s1mp1e$openFade.snap(0f);
            s1mp1e$openFade.to(1f);
            PanelGhost.cancel();
        }
        float fade = s1mp1e$openFade.value();

        PanelGhost.beginFrame();
        PanelGhost.remember(gl, gt, xs, ys);
        GlassRenderer.panel(gl, gt, gl + xs, gt + ys, fade);

        List<Slot> slots = this.container.slots;

        s1mp1e$drawLattice(slots, gl, gt, fade);
        s1mp1e$drawDrag(gl, gt);
        s1mp1e$drawHover(slots, gl, gt, mouseX, mouseY, now);

        // Survival inventory: its drawBackground draws the PNG *and* the player model.
        // Reproducing drawEntity by hand in the glass batch's leftover state made the
        // model vanish. So re-run the REAL drawBackground now — InventoryGlassMixin
        // @Redirects only its PNG blit to nothing, leaving vanilla to paint the player
        // in its own correct state, on top of the glass. (Chest/furnace have no player,
        // so the swallow above is all they need.)
        if ((Object) this instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen) {
            this.drawBackground(delta, mouseX, mouseY);
        }
    }

    // Suppress vanilla's hovered-slot white highlight (0x80FFFFFF fillGradient in
    // render) — the glass hover pill replaces it. fillGradient is protected on
    // DrawableHelper (can't @Shadow/self-call an inherited method), so always no-op
    // when the redirect fires. Owner is ContainerScreen (javac receiver-type rule).
    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/ContainerScreen;"
                            + "fillGradient(IIIIII)V"))
    private void s1mp1e$suppressSlotHighlight(ContainerScreen self,
                                              int x1, int y1, int x2, int y2, int c1, int c2) {
        // no-op: the glass hover pill is the highlight now.
    }

    /** Slot-separator lattice: one cell per slot with a 4-bit neighbour mask. */
    private static void s1mp1e$drawLattice(List<Slot> slots, int gl, int gt, float fade) {
        HashSet<Long> pos = new HashSet<Long>();
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            pos.add(s1mp1e$key(s.xPosition, s.yPosition));
        }
        if (!GlassRenderer.beginBatch(GlassProgram.LINE)) return;
        try {
            for (int i = 0; i < slots.size(); i++) {
                Slot s = slots.get(i);
                int sx = s.xPosition, sy = s.yPosition;
                int mask = 0;
                if (pos.contains(s1mp1e$key(sx + 18, sy))) mask |= 1; // E
                if (pos.contains(s1mp1e$key(sx - 18, sy))) mask |= 2; // W
                if (pos.contains(s1mp1e$key(sx, sy + 18))) mask |= 4; // S
                if (pos.contains(s1mp1e$key(sx, sy - 18))) mask |= 8; // N
                GlassRenderer.batchQuad(gl + sx - 1, gt + sy - 1,
                                        gl + sx + 17, gt + sy + 17,
                                        0f, 1f, 1f, fade, (mask * 17) / 255f);
            }
        } finally {
            GlassRenderer.endBatch();
        }
    }

    /** Quick-craft (right-drag distribute) highlight — 26.2 easing verbatim. */
    private void s1mp1e$drawDrag(int gl, int gt) {
        HashSet<Long> current = new HashSet<Long>();
        boolean dragActive = this.isCursorDragging;
        if (dragActive && this.cursorDragSlots != null) {
            for (Object o : this.cursorDragSlots) {
                if (!(o instanceof Slot)) continue;
                Slot s = (Slot) o;
                Long k = Long.valueOf(s1mp1e$key(gl + s.xPosition, gt + s.yPosition));
                current.add(k);
                Fade f = s1mp1e$dragAlpha.get(k);
                if (f == null) {
                    f = new Fade(0f, DRAG_IN_MS);
                    s1mp1e$dragAlpha.put(k, f);
                }
                f.to(1f);
            }
        }
        if (s1mp1e$dragAlpha.isEmpty()) return;

        Iterator<Map.Entry<Long, Fade>> it = s1mp1e$dragAlpha.entrySet().iterator();
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
            // WHITE, non-refractive, glass corner radius (lift 1.0 -> pure white),
            // opacity halved to match vanilla's 0x80FFFFFF drag highlight.
            GlassRenderer.glass(bx - 1, by - 1, bx + 17, by + 17,
                                4f, 1.0f, 1.0f, a * 0.5f, GlassRenderer.FROST_NONE);
        }
    }

    /** Hover pill on the two-axis spring rig (lead 55 / trail 30, critical). */
    private void s1mp1e$drawHover(List<Slot> slots, int gl, int gt,
                                  int mouseX, int mouseY, long now) {
        Slot hov = null;
        int px = mouseX - gl, py = mouseY - gt;
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            int sx = s.xPosition, sy = s.yPosition;
            if (px >= sx - 1 && px < sx + 17 && py >= sy - 1 && py < sy + 17
                    && s.doDrawHoveringEffect()) {
                hov = s;
            }
        }
        boolean hovering = hov != null;

        float dt = (s1mp1e$hoverNanos == 0L) ? (1f / 60f)
                                             : Math.min(0.1f, (now - s1mp1e$hoverNanos) * 1.0e-9f);
        s1mp1e$hoverNanos = now;

        if (hovering) {
            float cx = gl + hov.xPosition + 8f;
            float cy = gt + hov.yPosition + 8f;
            if (s1mp1e$hx1 == null || (!s1mp1e$hoverActive && s1mp1e$hoverFade.value() <= 0.05f)) {
                s1mp1e$hx1 = new Spring(cx, Spring.OMEGA_SNAP, Spring.DAMPING);
                s1mp1e$hx2 = new Spring(cx, Spring.OMEGA_MED,  Spring.DAMPING);
                s1mp1e$hy1 = new Spring(cy, Spring.OMEGA_SNAP, Spring.DAMPING);
                s1mp1e$hy2 = new Spring(cy, Spring.OMEGA_MED,  Spring.DAMPING);
            } else {
                s1mp1e$hx1.setTarget(cx); s1mp1e$hx2.setTarget(cx);
                s1mp1e$hy1.setTarget(cy); s1mp1e$hy2.setTarget(cy);
            }
            s1mp1e$hoverActive = true;
            s1mp1e$hoverFade.to(1f);
        } else {
            s1mp1e$hoverActive = false;
            s1mp1e$hoverFade.to(0f);
            if (s1mp1e$hoverFade.value() <= 0.004f || s1mp1e$hx1 == null) return;
        }

        s1mp1e$hx1.advance(dt); s1mp1e$hx2.advance(dt);
        s1mp1e$hy1.advance(dt); s1mp1e$hy2.advance(dt);

        float lox = Math.min(s1mp1e$hx1.value(), s1mp1e$hx2.value());
        float hix = Math.max(s1mp1e$hx1.value(), s1mp1e$hx2.value());
        float loy = Math.min(s1mp1e$hy1.value(), s1mp1e$hy2.value());
        float hiy = Math.max(s1mp1e$hy1.value(), s1mp1e$hy2.value());
        // corner 1.0, neutral lift 0.12, sharp refraction, shadow pad 6, 20x20 pill
        GlassRenderer.glass(lox - 10f, loy - 10f, hix + 10f, hiy + 10f,
                            6f, 1.0f, 0.12f, s1mp1e$hoverFade.value(), GlassRenderer.FROST_NONE);
    }

    private static long s1mp1e$key(int x, int y) {
        return ((long) x << 32) | (y & 0xffffffffL);
    }
}
