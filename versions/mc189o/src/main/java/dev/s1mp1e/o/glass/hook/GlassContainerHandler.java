package dev.s1mp1e.o.glass.hook;

import dev.s1mp1e.o.glass.anim.Fade;
import dev.s1mp1e.o.glass.anim.Spring;
import dev.s1mp1e.o.glass.render.GlassProgram;
import dev.s1mp1e.o.glass.render.GlassRenderer;
import dev.s1mp1e.o.glass.render.PanelGhost;
import dev.s1mp1e.o.glass.render.SceneCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.inventory.menu.InventoryMenuScreen;
import net.minecraft.inventory.slot.InventorySlot;
import dev.s1mp1e.o.event.GuiOpenEvent;
import dev.s1mp1e.o.event.GuiScreenEvent;
import dev.s1mp1e.o.event.RenderGameOverlayEvent;
import dev.s1mp1e.o.event.SubscribeEvent;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Liquid glass for vanilla container screens — the 1.8.9 Forge port of
 * LiquidGlass26's {@code GlassPanels} + {@code ContainerScreensGlassMixin} +
 * {@code ContainerCloseGhostMixin}.
 *
 * <p>Timing mirrors 26.2. The panel is drawn from
 * {@link dev.s1mp1e.o.event.GuiScreenEvent.BackgroundDrawnEvent},
 * which Forge posts at the end of {@code Screen.renderBackground()} — i.e.
 * after the dark world-dim gradient but before {@code InventoryMenuScreen} paints its
 * GUI texture. That is exactly where 26.2 wants the backdrop grabbed (the world
 * is drawn, the glass is not, so it never samples itself) and where the panel
 * belongs. We grab there, then draw the frosted panel, the slot lattice, the
 * quick-craft drag highlight and the hover pill.
 *
 * <p>Forge events can't cancel the vanilla {@code drawGuiContainerBackgroundLayer}
 * blit in 1.8.9, so the opaque GUI texture still paints over our glass for these
 * vanilla screens — accepted for now (the mod's own screens will render their
 * glass unobstructed later; the close-ghost already shows it during the fade).
 * A no-op {@link GuiScreenEvent.DrawScreenEvent.Post} marks that intent.
 */
public final class GlassContainerHandler {

    // ---- 26.2 constants (do not invent new ones) --------------------------
    private static final float HOVER_FADE_S = 0.10f; // hover pill fade in/out
    /** Eased, frame-rate independent: the old per-frame +0.25/-0.15 ramps ran
     *  2.4x faster at 144fps than at 60. */
    private static final float DRAG_IN_MS   = 90f;   // touched  -> lit
    private static final float DRAG_OUT_MS  = 150f;  // released -> gone
    private static final float DRAG_DROP    = 0.02f; // entry dropped below this
    private static final float SLOT         = 18f;   // vanilla slot pitch (px)

    // ---- reflection into InventoryMenuScreen geometry / drag set -----------------
    private static boolean reflectDone = false;
    private static Field fGuiLeft, fGuiTop, fXSize, fYSize, fDragSlots, fDragging;

    // ---- panel open-fade, tracked per screen instance ---------------------
    private static Object curScreen;
    /** Panel open fade: eased, and interruptible if the screen is
     *  re-opened before the close ghost finished. */
    private final Fade openFade = new Fade(0f, PanelGhost.FADE_MS);
    private Object openFadeOwner;

    // ---- hover pill: two springs per axis (lead 55 / trail 30, zeta 1) ----
    private Spring hx1, hx2, hy1, hy2;
    private boolean hoverActive = false;
    private final Fade hoverFade = new Fade(0f, HOVER_FADE_S * 1000f);
    private long    hoverNanos  = 0L;

    // ---- quick-craft drag highlight: alpha per absolute slot cell ---------
    private final HashMap<Long, Fade> dragAlpha = new HashMap<Long, Fade>();

    // ---- creative fused tabs + glass scrollbar (B/C) ----------------------
    private final GlassCreative creative = new GlassCreative();

    // -----------------------------------------------------------------------
    // Panel + lattice + drag + hover, all before the vanilla GUI texture.
    // -----------------------------------------------------------------------
    @SubscribeEvent
    public void onBackgroundDrawn(GuiScreenEvent.BackgroundDrawnEvent e) {
        if (!(e.gui instanceof InventoryMenuScreen)) return;
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;

        ensureReflect();
        if (fGuiLeft == null || fGuiTop == null || fXSize == null || fYSize == null) return;

        InventoryMenuScreen screen = (InventoryMenuScreen) e.gui;
        int gl, gt, xs, ys;
        try {
            gl = fGuiLeft.getInt(screen);
            gt = fGuiTop.getInt(screen);
            xs = fXSize.getInt(screen);
            ys = fYSize.getInt(screen);
        } catch (Throwable t) {
            return;
        }

        // 背景 = 只有世界（沒有原版的暗色遮罩），和 26.2 一樣。
        //
        // 2026-10-04 使用者回報「有些版本背包比較暗」，推測是 26.2 用模糊世界、不是背包後面變暗的世界，所以玻璃比較亮。
        // 查證屬實：26.2 的面板取樣的是 GuiRenderer.render 開頭、任何 GUI 都還沒畫時拍的那張（只有世界）；暗色遮罩雖然也
        // 畫，但在面板底下，面板裡看到的是明亮、被玻璃霧化的世界。這條線原本在 drawDefaultBackground 的 0xC0101010→
        // 0xD0101010 漸層（約 75–82% 黑）畫完之後才 forceGrab，玻璃折射的是已經變暗的世界，整個背包就暗下來。
        //
        // 以前改用 forceGrab 的理由是「兩個拍背景的地方誰贏取決於時間抖動 → 閃爍」（見 SceneCapture 的說明）。現在不靠
        // 時間：HUD 在每一幀 overlay 最開頭（Pre(ALL)、HIGHEST）newFrame()＋grabOnce() 拍那張只有世界的背景，1.8.9 在
        // 世界裡開著畫面時 overlay 一定先於畫面畫，所以這裡 hasBackdrop() 為真時那張一定就是只有世界的版本——固定的順序，
        // 不會在兩種背景之間跳。只有這一幀沒有 overlay（理論上不會）才退回在遮罩之後重拍。
        if (!SceneCapture.hasBackdrop()) SceneCapture.forceGrab();

        // Per-instance open fade: fadeByte = min(1, elapsedMs / 150).
        long now = System.nanoTime();
        if (curScreen != screen) {
            curScreen = screen;
            openFade.snap(0f);
            openFade.to(1f);
            openFadeOwner = screen;
            PanelGhost.cancel();
        }
        float fade = openFade.value();

        // Frosted panel (pad 12, corner 0.19, frost 0.5) + close-ghost bookkeeping.
        // Creative uses a fused sheet extended above/below for the two tab rows (B).
        boolean isCreative = screen instanceof net.minecraft.client.gui.screen.inventory.menu.CreativeInventoryScreen;
        PanelGhost.beginFrame();
        PanelGhost.remember(gl, gt, xs, ys);
        if (isCreative) {
            creative.drawSheet(gl, gt, xs, ys, fade);
        } else {
            GlassRenderer.panel(gl, gt, gl + xs, gt + ys, fade);
        }

        List<InventorySlot> slots = screen.menu.slots;

        drawLattice(slots, gl, gt, fade);
        drawDrag(screen, gl, gt);
        drawHover(slots, gl, gt, e.getMouseX(), e.getMouseY(), now);

        // Creative tab pills + glass scrollbar (B/C), before the tab icons.
        if (isCreative) {
            creative.drawTabsAndScrollbar((net.minecraft.client.gui.screen.inventory.menu.CreativeInventoryScreen) screen,
                    gl, gt, xs, ys, e.getMouseX(), e.getMouseY(), fade);
        }

        // Status-effect strip (F): one continuous glass strip to the LEFT of the
        // panel, drawn here (before items + tooltip) so the tooltip stays on top
        // (R1). Vanilla's own drawActivePotionEffects is suppressed by the coremod
        // once GlassEffects has armed the latch.
        if (screen instanceof net.minecraft.client.gui.screen.inventory.menu.PlayerInventoryScreen
                && dev.s1mp1e.o.glass.render.GlassEffects.hasVisibleEffects(Minecraft.getInstance())) {
            dev.s1mp1e.o.glass.render.GlassEffects.draw(Minecraft.getInstance(), gl, gt, fade);
        }
    }

    /** InventorySlot-separator lattice: one cell per slot with a 4-bit neighbour mask. */
    private static void drawLattice(List<InventorySlot> slots, int gl, int gt, float fade) {
        HashSet<Long> pos = new HashSet<Long>();
        for (int i = 0; i < slots.size(); i++) {
            InventorySlot s = slots.get(i);
            pos.add(key(s.x, s.y));
        }
        // One batch for the whole lattice. Drawing each cell through
        // latticeCell() meant a state push + program bind + glBegin/glEnd PER
        // SLOT — 46 of them in the inventory, every frame.
        if (!GlassRenderer.beginBatch(dev.s1mp1e.o.glass.render.GlassProgram.LINE)) return;
        try {
            for (int i = 0; i < slots.size(); i++) {
                InventorySlot s = slots.get(i);
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

    /**
     * Quick-craft (right-drag distribute) highlight: each touched slot eases a
     * rounded glass quad in over {@link #DRAG_IN_MS}; slots that leave the drag
     * set ease back out over {@link #DRAG_OUT_MS} and are dropped once settled
     * below {@link #DRAG_DROP}. Every cell owns a {@link Fade}, so sweeping the
     * cursor back over a half-faded slot re-lights it from where it stands
     * instead of restarting. The drag set is {@code InventoryMenuScreen.draggedInvSlots}
     * read reflectively; without it we simply draw no drag highlight.
     */
    private void drawDrag(InventoryMenuScreen screen, int gl, int gt) {
        HashSet<Long> current = new HashSet<Long>();
        boolean dragActive = false;
        if (fDragging != null) {
            try {
                dragActive = fDragging.getBoolean(screen);
            } catch (Throwable t) {
                dragActive = false;
            }
        }
        if (dragActive && fDragSlots != null) {
            Set<?> dragging = null;
            try {
                dragging = (Set<?>) fDragSlots.get(screen);
            } catch (Throwable t) {
                dragging = null;
            }
            if (dragging != null) {
                for (Object o : dragging) {
                    if (!(o instanceof InventorySlot)) continue;
                    InventorySlot s = (InventorySlot) o;
                    Long k = Long.valueOf(key(gl + s.x, gt + s.y));
                    current.add(k);
                    Fade f = dragAlpha.get(k);
                    if (f == null) {
                        f = new Fade(0f, DRAG_IN_MS);
                        dragAlpha.put(k, f);
                    }
                    f.retarget(1f);
                }
            }
        }
        if (dragAlpha.isEmpty()) return;

        Iterator<Map.Entry<Long, Fade>> it = dragAlpha.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Fade> en = it.next();
            Long k = en.getKey();
            Fade f = en.getValue();
            if (!current.contains(k)) f.retarget(0f, DRAG_OUT_MS);
            float a = f.value();
            if (a <= DRAG_DROP && f.isIdle()) {
                it.remove();
                continue;
            }
            long kk = k.longValue();
            int bx = (int) (kk >> 32);
            int by = (int) kk;
            // A WHITE block with the glass corner radius — deliberately not
            // refractive. lift = 1.0 drives glass.fsh's `col = mix(col, white,
            // lift)` all the way to pure white, so the shader's SDF still
            // supplies the exact same rounding as every other glass element
            // while the fill matches vanilla's 0x80FFFFFF drag highlight.
            // Opacity is halved for the same reason: vanilla's alpha is 128/255.
            GlassRenderer.glass(bx - 1, by - 1, bx + 17, by + 17,
                                4f, 1.0f, 1.0f, a * 0.5f, GlassRenderer.FROST_NONE);
        }
    }

    /**
     * Hover pill: a sharp glass square that slides between slots on two-axis
     * springs (lead omega 55, trail omega 30, critically damped) so a diagonal
     * hop stretches a bridging box, fading in/out over {@link #HOVER_FADE_S}.
     * It snaps in place the first time it appears.
     */
    private void drawHover(List<InventorySlot> slots, int gl, int gt, int mouseX, int mouseY, long now) {
        InventorySlot hov = null;
        int px = mouseX - gl, py = mouseY - gt;
        for (int i = 0; i < slots.size(); i++) {
            InventorySlot s = slots.get(i);
            int sx = s.x, sy = s.y;
            if (px >= sx - 1 && px < sx + 17 && py >= sy - 1 && py < sy + 17
                    && s.isActive()) {
                hov = s;
            }
        }
        // While the creative grid is mid-glide the drawn item under the cursor is the
        // eased (not-yet-settled) item, so vanilla's hovered grid slot is a mismatch:
        // drop the hover pill for it (26.2 nulls the hovered grid slot). The pill
        // returns the instant the glide settles.
        if (hov != null && GlassCreativeGlide.cursorOverGridWhileSliding(mouseX, mouseY)) {
            hov = null;
        }
        boolean hovering = hov != null;

        float dt = (hoverNanos == 0L) ? (1f / 60f)
                                      : Math.min(0.1f, (now - hoverNanos) * 1.0e-9f);
        hoverNanos = now;

        if (hovering) {
            float cx = gl + hov.x + 8f;
            float cy = gt + hov.y + 8f;
            if (hx1 == null || (!hoverActive && hoverFade.value() <= 0.05f)) {
                // fresh hover: appear in place (constructor snaps target=value)
                hx1 = new Spring(cx, Spring.OMEGA_SNAP, Spring.DAMPING);
                hx2 = new Spring(cx, Spring.OMEGA_MED,  Spring.DAMPING);
                hy1 = new Spring(cy, Spring.OMEGA_SNAP, Spring.DAMPING);
                hy2 = new Spring(cy, Spring.OMEGA_MED,  Spring.DAMPING);
            } else {
                // sliding (or continuing a slide interrupted mid fade-out)
                hx1.setTarget(cx); hx2.setTarget(cx);
                hy1.setTarget(cy); hy2.setTarget(cy);
            }
            hoverActive = true;
            hoverFade.retarget(1f);
        } else {
            hoverActive = false;
            hoverFade.retarget(0f);
            if (hoverFade.value() <= 0.004f || hx1 == null) return;
        }

        // Spring.advance sub-steps at 1/120 s and NaN-guards for us.
        hx1.advance(dt); hx2.advance(dt); hy1.advance(dt); hy2.advance(dt);

        float lox = Math.min(hx1.value(), hx2.value());
        float hix = Math.max(hx1.value(), hx2.value());
        float loy = Math.min(hy1.value(), hy2.value());
        float hiy = Math.max(hy1.value(), hy2.value());
        // Same glass as the hotbar selector, knob for knob: corner 1.0 (fully
        // rounded), neutral lift 0.12, sharp refraction, shadow pad 6.
        // Footprint 20x20 rather than the hotbar's 18: the inventory slot pitch
        // is 18 (not 20), so an 18px pill sat exactly on the cell and read
        // small — 20 lets it sit slightly proud of the lattice, which is what
        // makes it look like a highlight rather than another cell.
        GlassRenderer.glass(lox - 10f, loy - 10f, hix + 10f, hiy + 10f,
                            6f, 1.0f, 0.12f, hoverFade.value(), GlassRenderer.FROST_NONE);
    }

    // -----------------------------------------------------------------------
    // Close: fade the panel(s) out together, and reset the transient rigs.
    // -----------------------------------------------------------------------
    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent e) {
        Screen old = Minecraft.getInstance().screen;
        if (!(old instanceof InventoryMenuScreen)) return;
        // Only fires when leaving a container (close, or switch to another screen).
        // If the INCOMING screen is itself a container (chest -> inventory, a
        // double-tapped E), the new panel takes over immediately, so arming a
        // fade-out ghost only flashes the old panel for the one frame before
        // onBackgroundDrawn cancels it. Kill it here instead.
        if (e.gui instanceof InventoryMenuScreen) {
            PanelGhost.cancel();
        } else {
            PanelGhost.trigger();
        }
        curScreen = null;
        hoverActive = false;
        hoverFade.snap(0f);
        hx1 = hx2 = hy1 = hy2 = null;
        dragAlpha.clear();
        creative.reset();
    }

    // -----------------------------------------------------------------------
    // Draw the fade-out ghost from the HUD overlay pass. GlassHudHandler has
    // already grabbed a fresh backdrop at Pre(ALL); Post(ALL) fires every frame
    // in-world, so once the container closes the ghost fades over the world.
    // -----------------------------------------------------------------------
    @SubscribeEvent
    public void onOverlayPost(RenderGameOverlayEvent.Post e) {
        if (e.type != RenderGameOverlayEvent.ElementType.ALL) return;
        PanelGhost.drawGhosts();
    }

    // -----------------------------------------------------------------------
    // The vanilla container GUI texture paints over our glass and can't be
    // cancelled from a Forge event in 1.8.9; this documented no-op marks the
    // point where the mod's own screens will later take over rendering.
    // -----------------------------------------------------------------------
    @SubscribeEvent
    public void onDrawScreenPost(GuiScreenEvent.DrawScreenEvent.Post e) {
        // Safety net: if the vanilla background layer threw before ContainerHook
        // could disarm, clear the suppressor so the next screen's blits aren't
        // eaten.
        dev.s1mp1e.o.glass.asm.BlitSuppressor.disarm();
    }

    // ---- helpers ----------------------------------------------------------

    /** Pack a slot cell position into a long key (matches 26.2's HashSet keys). */
    private static long key(int x, int y) {
        return ((long) x << 32) | (y & 0xffffffffL);
    }

    /**
     * Cache the protected {@code InventoryMenuScreen} geometry fields and the private
     * drag set once. Field-name lookups try the SRG name first (obfuscated /
     * Feather runtime) then the MCP name (dev {@code runClient}); the drag set
     * are found by name, with a type scan as a last-resort fallback.
     */
    private static void ensureReflect() {
        if (reflectDone) return;
        reflectDone = true;
        // `dragSplitting` — the boolean that says a drag is actually IN PROGRESS.
        // Reading only dragSplittingSlots is not enough: vanilla clears that set
        // when a drag is ABORTED, but the normal completion path just sets
        // dragSplitting = false and leaves the set populated. Vanilla gets away
        // with it because it guards its own highlight with
        // `if (dragSplitting && dragSplittingSlots.contains(slot))`; without the
        // boolean the last distribution's slots stay lit forever.
        fDragging = findField(InventoryMenuScreen.class, dev.s1mp1e.o.util.Names.of("isDraggingItem", "f_68881372"), "dragSplitting");
        fGuiLeft = findField(InventoryMenuScreen.class, dev.s1mp1e.o.util.Names.of("x", "f_86721753"), "guiLeft");
        fGuiTop  = findField(InventoryMenuScreen.class, dev.s1mp1e.o.util.Names.of("y", "f_86953664"), "guiTop");
        fXSize   = findField(InventoryMenuScreen.class, dev.s1mp1e.o.util.Names.of("backgroundWidth", "f_99871233"), "xSize");
        fYSize   = findField(InventoryMenuScreen.class, dev.s1mp1e.o.util.Names.of("backgroundHeight", "f_73003637"), "ySize");
        // Both drag fields by name (SRG first for the reobfuscated Feather
        // runtime, then MCP for dev), verified against MCP stable_20:
        //   isDraggingItem = dragSplitting, draggedInvSlots = dragSplittingSlots
        fDragSlots = findField(InventoryMenuScreen.class, dev.s1mp1e.o.util.Names.of("draggedInvSlots", "f_47258017"), "dragSplittingSlots");
        if (fDragSlots == null) {
            // Fallback: the only Set field on InventoryMenuScreen is the drag set.
            Field[] declared = InventoryMenuScreen.class.getDeclaredFields();
            for (int i = 0; i < declared.length; i++) {
                if (Set.class.isAssignableFrom(declared[i].getType())) {
                    declared[i].setAccessible(true);
                    fDragSlots = declared[i];
                    break;
                }
            }
        }
    }

    private static Field findField(Class<?> cls, String srg, String mcp) {
        String[] names = { srg, mcp };
        for (int i = 0; i < names.length; i++) {
            try {
                Field f = cls.getDeclaredField(names[i]);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // try the next candidate name
            }
        }
        return null;
    }

    /**
     * True when we drew a glass panel for this screen this frame — the ASM
     * ContainerHook consults it to decide whether suppressing the vanilla
     * background texture is safe. If our panel never drew (pipeline down,
     * geometry unreadable) the texture must still render or the screen is blank.
     */
    public static boolean hasPanelFor(Object screen) {
        return curScreen == screen;
    }

    /**
     * The panel rectangle {@code {guiLeft, guiTop, xSize, ySize}} for a container
     * screen, read through the same cached reflected geometry fields the panel
     * itself uses. Returns {@code null} when the screen is not a
     * {@code InventoryMenuScreen} or the geometry can't be read — the ASM
     * {@link dev.s1mp1e.o.glass.asm.ContainerHook} then leaves the vanilla texture
     * alone rather than blanking the screen.
     */
    public static int[] panelRect(Object screen) {
        if (!(screen instanceof InventoryMenuScreen)) return null;
        ensureReflect();
        if (fGuiLeft == null || fGuiTop == null || fXSize == null || fYSize == null) return null;
        try {
            int gl = fGuiLeft.getInt(screen);
            int gt = fGuiTop.getInt(screen);
            int xs = fXSize.getInt(screen);
            int ys = fYSize.getInt(screen);
            return new int[] { gl, gt, xs, ys };
        } catch (Throwable t) {
            return null;
        }
    }
}
