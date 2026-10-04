package dev.s1mp1e.o.glass.hook;

import dev.s1mp1e.o.glass.render.GlassProgram;
import dev.s1mp1e.o.glass.ui.GlassScrollbar;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.render.Window;
import net.minecraft.client.gui.screen.inventory.menu.InventoryMenuScreen;
import net.minecraft.client.gui.screen.inventory.menu.CreativeInventoryScreen;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.platform.Lighting;
import net.minecraft.client.render.vertex.BufferBuilder;
import net.minecraft.client.render.vertex.Tesselator;
import net.minecraft.client.render.entity.ItemRenderer;
import net.minecraft.client.render.vertex.DefaultVertexFormat;
import net.minecraft.inventory.slot.InventorySlot;
import net.minecraft.item.ItemStack;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Feature (D) for 1.8.9: silky sub-pixel scrolling of the creative item grid,
 * the Forge fixed-function port of LiquidGlass26's
 * {@code CreativeGlassMixin#liquidglass$drawGlideOverlay} +
 * {@code ContainerGlideGlassMixin} + {@code GlassScrollbar.snapToTarget()}.
 *
 * <p><b>Model (kept identical to 26.2).</b> Vanilla keeps a ROW-ALIGNED logical
 * scroll: {@code ContainerCreative.scrollItems(currentScroll)} rounds to an integer
 * top row and fills the 45 grid slots from that row, so clicks, hit-testing and
 * tooltips are always correct. The glass scrollbar eases its drawn ratio toward
 * that integer row's ratio ({@code row/rowCount}); its {@link GlassScrollbar#pos()}
 * is the eased value. While the eased row differs from the logical row the grid is
 * "sliding": vanilla's own drawing of the 45 grid slots is suppressed (the coremod
 * head-splice on {@code InventoryMenuScreen.renderSlot} routes them here) and this class
 * redraws the visible items from {@code ContainerCreative.items} at the eased
 * position — six rows translated up by the sub-pixel fractional offset and clipped
 * to the five-row window with one extra row, so a row leaving the top is clipped
 * and a row entering the bottom shows no gap. A click mid-glide snaps the scrollbar
 * onto the target row first ({@link #onContainerMouseClicked}) so the frame the
 * click acts on shows the row vanilla will hit-test.
 *
 * <p><b>The scissor gotcha (Forge fixed-function form).</b> 26.2 draws inside the
 * {@code leftPos/topPos} pose and uses {@code enableScissor}, which is transformed
 * by that pose, so it passes slot-relative coords. Here the grid is drawn inside
 * {@code InventoryMenuScreen.render}'s {@code translate(guiLeft, guiTop, 0)} matrix,
 * but {@code glScissor} is in WINDOW pixels and is NOT affected by the modelview
 * matrix, so the scissor must be given in ABSOLUTE window pixels (GUI-scaled,
 * bottom-left origin) while the item draws stay slot-relative. The sub-pixel offset
 * is applied through {@code GlStateManager.translated} (float) rather than the item
 * coordinates (which are {@code int}), so the glide is genuinely sub-pixel.
 */
public final class GlassCreativeGlide {

    private GlassCreativeGlide() {}

    // vanilla creative grid geometry (relative to guiLeft/guiTop)
    private static final int GRID_X = 9;
    private static final int GRID_Y = 18;
    private static final int COLS   = 9;
    private static final int VIS_ROWS = 5;
    private static final int PITCH  = 18;

    // ---- per-frame glide state (written by update(), read by onDrawSlot()) ----
    private static CreativeInventoryScreen screen;
    private static GlassScrollbar bar;
    private static boolean sliding;
    private static int   glideBase;      // top item row of the drawn window
    private static float glideFracPx;    // 0..18 px the window is slid up by
    private static int   glideRowCount;
    private static int   gl, gt;
    private static List<ItemStack> items;
    private static boolean overlayDrawn; // one overlay per frame

    // ---- reflection into ContainerCreative.items ----
    private static boolean itemsReflectDone;
    private static Field fItemList;

    /** Clear all glide state (screen close / reset). */
    public static void reset() {
        screen = null; bar = null; sliding = false;
        glideBase = 0; glideFracPx = 0f; glideRowCount = 0; items = null;
        overlayDrawn = false;
    }

    /**
     * The creative item list for this screen, or {@code null}. Tries the SRG field
     * name first (Feather / reobf runtime) then the MCP name (dev {@code runClient}).
     */
    @SuppressWarnings("unchecked")
    public static List<ItemStack> items(CreativeInventoryScreen sc) {
        if (sc == null) return null;
        Object container = sc.menu;
        if (container == null) return null;
        if (!itemsReflectDone) {
            itemsReflectDone = true;
            String[] names = { dev.s1mp1e.o.util.Names.of("items", "f_85632121"), "itemList" };
            for (int i = 0; i < names.length && fItemList == null; i++) {
                try {
                    Field f = container.getClass().getDeclaredField(names[i]);
                    f.setAccessible(true);
                    fItemList = f;
                } catch (NoSuchFieldException ignored) { }
            }
        }
        if (fItemList == null) return null;
        try {
            Object v = fItemList.get(container);
            return (v instanceof List) ? (List<ItemStack>) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code rowCount = max(0, ceil(size/9) - 5)} — the same value scrollTo uses. */
    public static int rowCount(List<ItemStack> list) {
        if (list == null) return 0;
        int r = (list.size() + COLS - 1) / COLS - VIS_ROWS;
        return r < 0 ? 0 : r;
    }

    /**
     * Publish this frame's glide state. Called by {@link GlassCreative} right after
     * it eases the scrollbar (same frame, before the slot loop runs).
     *
     * @param logicalRow the integer top row vanilla's slots show (round(scroll*rc))
     * @param active     true only on a scrollable grid tab (else no glide)
     */
    public static void update(CreativeInventoryScreen sc, GlassScrollbar scrollbar,
                              int guiLeft, int guiTop, int rc, int logicalRow,
                              boolean active, List<ItemStack> list) {
        screen = sc;
        bar = scrollbar;
        gl = guiLeft; gt = guiTop;
        items = list;
        glideRowCount = rc;
        overlayDrawn = false;
        sliding = false;
        if (!active || rc <= 0 || scrollbar == null || list == null) return;

        float easedRows = scrollbar.pos() * rc;
        if (Math.abs(easedRows - logicalRow) > 0.02f) {
            sliding = true;
            int base = (int) Math.floor(easedRows);
            if (base < 0) base = 0;
            if (base > rc) base = rc;
            glideBase = base;
            glideFracPx = (easedRows - base) * PITCH;
        }
    }

    // -----------------------------------------------------------------------
    // Coremod hook: head of InventoryMenuScreen.renderSlot(InventorySlot).
    // -----------------------------------------------------------------------

    /**
     * True to SKIP vanilla's drawing of {@code slot}. Only the creative grid slots
     * are skipped, and only while the grid is sliding; the first such slot triggers
     * the whole six-row overlay (drawn once per frame). Everything else (the bottom
     * hotbar row, the player-inventory tab, any other container) draws normally.
     */
    public static boolean onDrawSlot(InventoryMenuScreen sc, InventorySlot slot) {
        try {
            if (!sliding || slot == null) return false;
            if (sc != screen) return false;
            if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return false;
            if (!isGridSlot(slot)) return false;
            if (!overlayDrawn) {
                overlayDrawn = true;
                drawOverlay();
            }
            return true;   // this grid slot is now drawn by the overlay
        } catch (Throwable t) {
            return false;  // never break the vanilla slot loop
        }
    }

    private static boolean isGridSlot(InventorySlot slot) {
        int x = slot.x, y = slot.y;
        return x >= GRID_X && x < GRID_X + COLS * PITCH
            && y >= GRID_Y && y < GRID_Y + VIS_ROWS * PITCH;
    }

    /**
     * Draw the six-row sub-pixel item overlay. Runs inside drawScreen's
     * {@code translate(guiLeft, guiTop, 0)} matrix with GUI item lighting already
     * enabled, so the item draws are slot-relative and identical to the vanilla
     * slots they replace; only the {@code glScissor} rect is in absolute window px.
     */
    private static void drawOverlay() {
        if (items == null) return;
        Minecraft mc = Minecraft.getInstance();
        ItemRenderer ri = mc.getItemRenderer();
        TextRenderer fr = mc.textRenderer;
        int total = items.size();

        // scissor to the five-row window, in absolute (GUI-scaled, bottom-left) px
        Window sr = new Window(mc);
        int sf = sr.getScale();
        int sX = (gl + GRID_X) * sf;
        int sY = mc.height - (gt + GRID_Y + VIS_ROWS * PITCH) * sf;
        int sW = COLS * PITCH * sf;
        int sH = VIS_ROWS * PITCH * sf;
        boolean scissored = false;
        if (sW > 0 && sH > 0) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(sX, sY, sW, sH);
            scissored = true;
        }

        GlStateManager.pushMatrix();
        GlStateManager.translatef(0f, -glideFracPx, 0f);   // sub-pixel (float) offset
        float oldZ = ri.zOffset;
        ri.zOffset = 100.0f;
        GlStateManager.enableDepthTest();
        for (int vr = 0; vr <= VIS_ROWS; vr++) {          // five visible + one extra
            int row = glideBase + vr;
            int y = GRID_Y + vr * PITCH;
            int baseIdx = row * COLS;
            for (int col = 0; col < COLS; col++) {
                int idx = baseIdx + col;
                if (idx < 0 || idx >= total) continue;
                ItemStack st = items.get(idx);
                if (st == null) continue;
                int x = GRID_X + col * PITCH;
                ri.renderGuiItem(st, x, y);
                ri.renderGuiItemDecorations(fr, st, x, y, null);
            }
        }
        ri.zOffset = oldZ;
        GlStateManager.popMatrix();

        if (scissored) GL11.glDisable(GL11.GL_SCISSOR_TEST);

        // Config-menu scroll-edge whisper: dim the edge where content runs off.
        boolean top = glideBase > 0 || glideFracPx > 0.5f;
        boolean bot = (glideBase + VIS_ROWS) < (glideRowCount + VIS_ROWS);
        edgeWhisper(top, bot);

        // Restore the loop's item-lighting state for the remaining vanilla slots.
        Lighting.turnOnGui();
        GlStateManager.color4f(1f, 1f, 1f, 1f);
    }

    /**
     * Two faint dark gradient bands at the top/bottom of the grid window (the
     * fixed-function stand-in for {@code GlassWidgets.scrollEdges}). InventorySlot-relative
     * coords (the drawScreen matrix is still active); full GL state save/restore so
     * the surrounding item loop is undisturbed.
     */
    private static void edgeWhisper(boolean top, boolean bot) {
        if (!top && !bot) return;
        int x0 = GRID_X, x1 = GRID_X + COLS * PITCH;
        int yTop = GRID_Y, yBot = GRID_Y + VIS_ROWS * PITCH;
        int band = 6;
        int edge = 0x30000000;      // ~19% black at the very edge
        int fade = 0x00000000;

        GlStateManager.disableTexture();
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableAlphaTest();
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder wr = tess.getBuffer();
        if (top) vGradient(wr, tess, x0, x1, yTop, yTop + band, edge, fade);
        if (bot) vGradient(wr, tess, x0, x1, yBot - band, yBot, fade, edge);
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.disableBlend();
        GlStateManager.enableAlphaTest();
        GlStateManager.enableTexture();
        GlStateManager.color4f(1f, 1f, 1f, 1f);
    }

    /** A vertical gradient quad (colorTop at y0, colorBot at y1). */
    private static void vGradient(BufferBuilder wr, Tesselator tess,
                                  int x0, int x1, int y0, int y1, int colTop, int colBot) {
        float aT = (colTop >>> 24) / 255f, rT = (colTop >> 16 & 255) / 255f,
              gT = (colTop >> 8 & 255) / 255f, bT = (colTop & 255) / 255f;
        float aB = (colBot >>> 24) / 255f, rB = (colBot >> 16 & 255) / 255f,
              gB = (colBot >> 8 & 255) / 255f, bB = (colBot & 255) / 255f;
        wr.begin(7, DefaultVertexFormat.POSITION_COLOR);
        wr.vertex(x1, y0, 200.0f).color(rT, gT, bT, aT).nextVertex();
        wr.vertex(x0, y0, 200.0f).color(rT, gT, bT, aT).nextVertex();
        wr.vertex(x0, y1, 200.0f).color(rB, gB, bB, aB).nextVertex();
        wr.vertex(x1, y1, 200.0f).color(rB, gB, bB, aB).nextVertex();
        tess.end();
    }

    // -----------------------------------------------------------------------
    // Suppression queries for the hover pill and the tooltip while gliding.
    // -----------------------------------------------------------------------

    /** True while the grid is mid-glide and the cursor is over the grid window. */
    public static boolean cursorOverGridWhileSliding(int mouseX, int mouseY) {
        if (!sliding) return false;
        int rx = mouseX - gl, ry = mouseY - gt;
        return rx >= GRID_X && rx < GRID_X + COLS * PITCH
            && ry >= GRID_Y && ry < GRID_Y + VIS_ROWS * PITCH;
    }

    /** True to suppress the item tooltip this frame (grid item mid-glide would
     *  otherwise show the not-yet-drawn logical row's item). */
    public static boolean suppressTooltip() {
        if (!sliding || screen == null) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != screen) return false;
        // MC computes the screen mouse the same way Screen does.
        int mx, my;
        try {
            Window sr = new Window(mc);
            mx = org.lwjgl.input.Mouse.getX() * sr.getWidth() / mc.width;
            my = sr.getHeight() - org.lwjgl.input.Mouse.getY() * sr.getHeight() / mc.height - 1;
        } catch (Throwable t) {
            return false;
        }
        return cursorOverGridWhileSliding(mx, my);
    }

    // -----------------------------------------------------------------------
    // Coremod hook: head of InventoryMenuScreen.mouseClicked(int,int,int).
    // -----------------------------------------------------------------------

    /** Snap the glide onto the target row so a mid-glide click acts on the item
     *  drawn under the cursor (26.2 {@code lg$snapOnClick}). */
    public static void onContainerMouseClicked(InventoryMenuScreen sc) {
        try {
            if (sliding && sc == screen && bar != null) {
                bar.snapToTarget();
                sliding = false;
            }
        } catch (Throwable ignored) { }
    }

    /** For DevShot / verification: whether the grid is currently gliding. */
    public static boolean sliding() { return sliding; }
    public static int glideBase() { return glideBase; }
    public static float glideFracPx() { return glideFracPx; }
}
