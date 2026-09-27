package dev.s1mp1e.glass.hook;

import dev.s1mp1e.client.gui.GlassScrollbar;
import dev.s1mp1e.client.gui.GlassWidgets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import org.lwjgl.input.Mouse;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Features (C) + (D) for the creative item grid: the shared vertical glass scrollbar
 * ({@link GlassScrollbar}) driven from the vanilla scroll state, and the silky sub-pixel content
 * glide that makes the 45-slot grid slide like the S1mp1e config menu instead of stepping by whole
 * rows.
 *
 * <h3>How the glide keeps clicks correct</h3>
 * Vanilla's LOGICAL scroll stays row-aligned: {@code handleMouseInput}/the drag set
 * {@code currentScroll} and call {@code ContainerCreative.scrollTo}, which snaps the 45 slots'
 * CONTENT to whole rows ({@code round(currentScroll * rows)}). Clicks, hit-testing and tooltips read
 * those fixed slot positions, so they act on the row-aligned content — always correct. While the
 * eased scrollbar offset differs from that logical row, this hook (a) suppresses vanilla's own drawing
 * of the grid slots ({@link #handleSlot}, spliced onto {@code GuiContainer.drawSlot}) and (b) redraws
 * the visible item stacks itself from {@code ContainerCreative.itemList} + the eased row, translated by
 * the fractional offset and clipped to the grid window with one extra row so no edge gap shows. A click
 * mid-glide first snaps the bar onto the target row ({@link #snapOnClick}) so the frame the click acts
 * on shows the row vanilla will hit-test — "snap to the target row, then let vanilla handle the click".
 *
 * <h3>The scissor-space gotcha (mirror of the 26.2 creative trap)</h3>
 * The overlay runs INSIDE {@code GuiContainer.drawScreen}'s {@code GlStateManager.translate(guiLeft,
 * guiTop)} — the slot-loop matrix — so the item draws use slot-relative coords, and the extra sub-pixel
 * shift is another {@code GlStateManager.translate(0, -frac, 0)}. But {@code glScissor} is applied in
 * WINDOW space and is NOT affected by the modelview matrix, so the scissor rect is given in ABSOLUTE
 * GUI coords ({@code guiLeft+9 .. guiLeft+171}). On 26.2 (DrawContext) the opposite held — the scissor
 * WAS transformed by the pose and had to be relative; here it must be absolute. (Documented per rule.)
 *
 * <p>No scroll-edge blur whisper is drawn on 1.12.2: that effect samples the finished composite, which
 * on this fixed-function line would need a fresh framebuffer grab mid slot-loop (it would capture a
 * partial frame and violate R4). The core glide — sub-pixel content + a thumb that moves as one — is
 * delivered; the edge whisper is intentionally omitted, not deferred.
 */
public final class CreativeGlideHook {

    private CreativeGlideHook() {}

    // ---- grid geometry (slot-relative), matches ContainerCreative's LockedSlot layout ----
    private static final int GRID_X = 9, GRID_Y = 18, COLS = 9, VIS_ROWS = 5, PITCH = 18;

    // ---- the shared vertical glass scrollbar, keyed to the creative screen instance ----
    private static Object barOwner;
    private static GlassScrollbar bar;

    // ---- per-frame glide state (computed in drawScrollbar, read in handleSlot) ----
    private static Object glideOwner;
    private static boolean sliding;
    private static int   glideBase, glideRows;
    private static float glideFracPx;

    // ---- reflection ----
    private static boolean itemsTried;
    private static Field   fItemList;

    /**
     * Draw the glass scrollbar (C) and recompute the glide state (D). Called from
     * {@link dev.s1mp1e.glass.asm.CreativeHook#scrollbar} — i.e. in place of the vanilla thumb blit,
     * every frame, with the container geometry and the vanilla scroll fields already read.
     *
     * @param gl,gt   container top-left
     * @param scroll  {@code currentScroll} 0..1
     * @param held    {@code isScrolling} (vanilla is dragging the bar)
     * @param active  the grid can actually scroll (the {@code u <= 232} sprite state)
     */
    public static void drawScrollbar(GuiContainerCreative screen, int gl, int gt,
                                     float scroll, boolean held, boolean active) {
        if (screen != barOwner || bar == null) { barOwner = screen; bar = new GlassScrollbar(); }

        List<?> items = itemList(screen);
        int rc = items == null ? 0 : Math.max(0, (items.size() + COLS - 1) / COLS - VIS_ROWS);
        int logicalRow = rc <= 0 ? 0 : clamp(Math.round(scroll * rc), 0, rc);
        float targetRatio = rc <= 0 ? 0f : (float) logicalRow / rc;   // row-snapped (settles on a vanilla row)

        // vanilla creative track: thumb 12x15 at x = gl+175, top = gt+18, thumb-top travel 95 (= 112-17)
        float cx = gl + 175 + 6f;
        float trackTop = gt + 18f;
        float alpha = openAlpha();
        GlassScrollbar.run(bar, cx, trackTop, 95f, 15f, targetRatio, active, held && active, mouseY(), alpha);

        // glide: while the eased row differs from the logical (row-aligned) row, slide the content
        sliding = false;
        if (active && rc > 0) {
            float easedRows = bar.pos() * rc;
            glideRows = rc;
            if (Math.abs(easedRows - logicalRow) > 0.02f) {
                sliding = true;
                glideBase = clamp((int) Math.floor(easedRows), 0, rc);
                glideFracPx = (easedRows - glideBase) * PITCH;
            }
        }
        glideOwner = screen;
    }

    /**
     * {@code GuiContainer.drawSlot} head-splice. Returns true to SUPPRESS vanilla's draw of this slot
     * (the caller returns immediately). While the creative grid is mid-glide, every grid slot is
     * suppressed and the sub-pixel overlay is drawn once, on the top-left grid slot (the loop reaches it
     * first, so the overlay lands before the other grid slots and under the item foreground).
     */
    public static boolean handleSlot(GuiContainer gc, Slot slot) {
        try {
            if (!sliding || !(gc instanceof GuiContainerCreative) || gc != glideOwner) return false;
            if (slot == null || !isGridSlot(slot)) return false;
            if (slot.xPos == GRID_X && slot.yPos == GRID_Y) {
                drawOverlay((GuiContainerCreative) gc);   // anchor slot: draw the whole gliding grid once
            }
            return true;   // suppress this grid slot's vanilla draw
        } catch (Throwable t) {
            return false;  // never blank the grid on an error — let vanilla draw
        }
    }

    /** True if {@code slot} is one of the 45 scrolling grid cells (by position, like the 26.2 host). */
    public static boolean isGridSlot(Slot slot) {
        return slot.xPos >= GRID_X && slot.xPos < GRID_X + COLS * PITCH
            && slot.yPos >= GRID_Y && slot.yPos < GRID_Y + VIS_ROWS * PITCH;
    }

    /** True this frame when the given creative screen's grid is mid-glide (gate the slot-hover pill off). */
    public static boolean gliding(Object screen) {
        return sliding && screen == glideOwner;
    }

    /** A click mid-glide: snap the bar to the target row so the click acts on the drawn item. */
    public static void snapOnClick(GuiContainerCreative screen) {
        if (bar != null && screen == barOwner) bar.snapToTarget();
        sliding = false;
    }

    // -----------------------------------------------------------------------------------------------

    private static void drawOverlay(GuiContainerCreative screen) {
        List<?> items = itemList(screen);
        if (items == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        RenderItem ir = mc.getRenderItem();

        int gl = 0, gt = 0;   // the modelview is already translated to (guiLeft, guiTop); draw slot-relative
        // ABSOLUTE scissor coords (glScissor ignores the modelview translate — see class doc).
        int[] r = GlassContainerHandler.panelRect(screen);
        if (r == null) return;
        GlassWidgets.beginScissor(r[0] + GRID_X, r[1] + GRID_Y,
                r[0] + GRID_X + COLS * PITCH, r[1] + GRID_Y + VIS_ROWS * PITCH);
        GlStateManager.pushMatrix();
        GlStateManager.translate(0f, -glideFracPx, 0f);   // sub-pixel shift, on top of the (guiLeft,guiTop) matrix
        float savedZ = ir.zLevel;
        ir.zLevel = 100.0F;
        GlStateManager.enableDepth();
        try {
            for (int vr = 0; vr <= VIS_ROWS; vr++) {           // one extra row so nothing gaps at the bottom
                int row = glideBase + vr;
                int y = GRID_Y + vr * PITCH;
                for (int col = 0; col < COLS; col++) {
                    int idx = row * COLS + col;
                    if (idx < 0 || idx >= items.size()) continue;
                    Object o = items.get(idx);
                    if (!(o instanceof ItemStack)) continue;
                    ItemStack st = (ItemStack) o;
                    if (st.isEmpty()) continue;
                    int x = GRID_X + col * PITCH;
                    ir.renderItemAndEffectIntoGUI(mc.player, st, gl + x, gt + y);
                    ir.renderItemOverlayIntoGUI(mc.fontRenderer, st, gl + x, gt + y, null);
                }
            }
        } finally {
            ir.zLevel = savedZ;
            GlStateManager.popMatrix();
            GlassWidgets.endScissor();
        }
    }

    private static float openAlpha() {
        try { return GlassContainerHandler.panelFade(); } catch (Throwable t) { return 1f; }
    }

    private static double mouseY() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            net.minecraft.client.gui.ScaledResolution sr = new net.minecraft.client.gui.ScaledResolution(mc);
            int h = sr.getScaledHeight();
            return h - Mouse.getY() * h / mc.displayHeight - 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static List<?> itemList(GuiContainerCreative screen) {
        try {
            Object menu = screen.inventorySlots;
            if (menu == null) return null;
            if (!itemsTried) {
                itemsTried = true;
                Class<?> cls = menu.getClass();
                String[] names = { "field_148330_a", "itemList" };
                for (int i = 0; i < names.length; i++) {
                    try { Field f = cls.getDeclaredField(names[i]); f.setAccessible(true); fItemList = f; break; }
                    catch (NoSuchFieldException ignored) {}
                }
            }
            if (fItemList == null) return null;
            Object v = fItemList.get(menu);
            return v instanceof List ? (List<?>) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
