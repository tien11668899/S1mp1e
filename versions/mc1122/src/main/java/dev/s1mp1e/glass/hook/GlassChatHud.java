package dev.s1mp1e.glass.hook;

import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.glass.render.GlassCorners;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Feature G1 — chat panel + chat input (1.12.2 port of the mc189 hook).
 *
 * <p>{@code S1mp1eTransformer} splices {@link #begin} onto the head of {@code GuiNewChat.drawChat}
 * and redirects that method's per-line {@code drawRect(...)} calls to {@link #rect}. {@link #begin}
 * computes the visible message column from vanilla's own state (line count, scroll, fade) and draws
 * ONE frosted glass panel — hugging the WIDEST visible line, not the full chat column — plus a soft
 * grey scrim, in the same scaled chat space vanilla draws the text in ({@code translate(2, 8);
 * scale(chatScale)}, matching 1.12.2's {@code drawChat}); {@link #rect} then drops the per-line dark
 * background rects (RGB 0) while keeping the scrollbar rects (RGB != 0). The panel opacity carries
 * the newest visible line's fade (full while the chat is focused), so it fades out with the messages
 * exactly like vanilla.
 *
 * <p>The open input field's background rect is redirected from {@code GuiChat.drawScreen} to
 * {@link #inputRect}, which paints a glass bar. Both surfaces reuse the world backdrop grabbed at the
 * overlay-pass head (by {@link GlassHudHandler}), so they never sample already-drawn glass (R4). New
 * pieces -&gt; hotbar corner radius via {@link GlassCorners} (R2). Every path falls back to the
 * vanilla draw if the glass pipeline is down, so chat never renders worse than vanilla.
 */
public final class GlassChatHud {

    private GlassChatHud() {}

    private static final int SCRIM       = 0x66101018;   // panel readability scrim (alpha scaled by fade)
    private static final int INPUT_SCRIM = 0x33101018;
    private static final float PANEL_BASE_ALPHA = 0.82f;
    private static final int RIGHT_PAD = 6;
    private static final int LEFT_PAD  = 2;

    /** True while a glass panel drew this drawChat call, so {@link #rect} drops the per-line dark rects. */
    private static boolean panelActive;

    private static boolean fieldsResolved;
    private static Field F_DRAWN, F_SCROLL;

    // ---- HEAD splice on GuiNewChat.drawChat --------------------------------

    /** Draw the single glass chat panel. Runs at drawChat head, under renderChat's translate(0, h-48). */
    public static void begin(GuiNewChat self, int updateCounter) {
        panelActive = false;
        colOffsetPx = 0f;
        try {
            // Group 7 chat arrival: register lines that arrived since last frame, take the pending column offset.
            List<?> all = drawnLines(self);
            if (all != null) {
                int sp = scrollPos(self);
                dev.s1mp1e.glass.render.ChatArrival.track(all, Math.max(0, sp));
                curDrawn = all;
                curScroll = Math.max(0, sp);
                colOffsetPx = dev.s1mp1e.glass.render.ChatArrival.offset(9) * self.getChatScale();
            }
        } catch (Throwable ignored) {}
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.gameSettings == null) return;
            if (mc.gameSettings.chatVisibility == EntityPlayer.EnumChatVisibility.HIDDEN) return;

            List<?> drawn = drawnLines(self);
            if (drawn == null) return;
            int k = drawn.size();
            if (k == 0) return;
            int scrollPos = scrollPos(self);
            if (scrollPos < 0) return;

            int lineCount = self.getLineCount();
            boolean open = self.getChatOpen();
            float scale = self.getChatScale();
            float f = mc.gameSettings.chatOpacity * 0.9f + 0.1f;

            int visible = 0, maxW = 0, newestAlpha = -1;
            for (int i1 = 0; i1 + scrollPos < k && i1 < lineCount; i1++) {
                Object o = drawn.get(i1 + scrollPos);
                if (!(o instanceof ChatLine)) continue;
                ChatLine line = (ChatLine) o;
                int age = updateCounter - line.getUpdatedCounter();
                if (age >= 200 && !open) continue;
                double d0 = age / 200.0;
                d0 = 1.0 - d0; d0 *= 10.0;
                d0 = d0 < 0.0 ? 0.0 : (d0 > 1.0 ? 1.0 : d0);
                d0 *= d0;
                int l1 = (int) (255.0 * d0);
                if (open) l1 = 255;
                l1 = (int) (l1 * f);
                if (l1 <= 3) continue;
                visible++;
                String text = line.getChatComponent().getFormattedText();
                int w = mc.fontRenderer.getStringWidth(text);
                if (w > maxW) maxW = w;
                if (newestAlpha < 0) newestAlpha = l1;   // first visible = newest (bottom) line
            }
            if (visible == 0 || maxW <= 0) return;

            boolean glass = GlassProgram.ensureReady() && GlassProgram.usable() && SceneCapture.hasBackdrop();
            if (!glass) return;   // vanilla per-line rects draw normally (rect() falls back)

            float fade = open ? 1f : Math.max(0f, Math.min(1f, newestAlpha / 255f));
            float panelAlpha = PANEL_BASE_ALPHA * fade;

            int x0 = -LEFT_PAD, x1 = maxW + RIGHT_PAD;
            int y1 = 1, y0 = -(visible * 9) - 1;
            int w = x1 - x0, h = y1 - y0;

            panelActive = true;

            GlStateManager.pushMatrix();
            GlStateManager.translate(2.0f, 8.0f + colOffsetPx, 0.0f);   // drawChat's own translate + arrival shift
            GlStateManager.scale(scale, scale, 1.0f);
            GlassProgram.setShadowScale(mc.currentScreen != null ? 0f : 1f);
            try {
                GlassRenderer.glass(x0, y0, x1, y1, GlassRenderer.PAD_PANEL,
                                    GlassCorners.hotbarCorner(w, h), 0f, panelAlpha, GlassRenderer.FROST_PANEL);
                if (GlassProgram.roundUsable()) {
                    int a = (int) (((SCRIM >>> 24) & 0xFF) * fade) & 0xFF;
                    GlassRenderer.roundRect(x0, y0, x1, y1, GlassCorners.hotbarRadiusPx(w, h),
                                            (a << 24) | (SCRIM & 0xFFFFFF));
                }
            } finally {
                GlassProgram.setShadowScale(1f);
                GlStateManager.popMatrix();
            }
        } catch (Throwable t) {
            panelActive = false;   // any failure -> vanilla chat draws unchanged
        }
    }

    /** The arrival shift of this drawChat call (screen px), and the column it was computed for. */
    private static float colOffsetPx;
    private static List<?> curDrawn;
    private static int curScroll;

    /** Redirect of drawChat's FIRST {@code GlStateManager.translate(2, 8, 0)} (the chat pose): add the arrival shift. */
    public static void translate(float x, float y, float z) {
        GlStateManager.translate(x, y + colOffsetPx, z);
    }

    /**
     * Redirect of drawChat's per-line {@code drawStringWithShadow(s, 0, j2 - 8, rgb + (alpha << 24))}: the line is
     * found from its y ({@code j2 = -i * 9}) and its alpha is multiplied by its entrance fade.
     */
    public static int text(net.minecraft.client.gui.FontRenderer fr, String s, float x, float y, int color) {
        try {
            if (curDrawn != null) {
                int i = Math.round(-(y + 8f) / 9f);
                int idx = i + curScroll;
                if (idx >= 0 && idx < curDrawn.size()) {
                    float fade = dev.s1mp1e.glass.render.ChatArrival.lineFade(curDrawn.get(idx));
                    if (fade < 1f) {
                        int a = (color >>> 24) & 0xFF;
                        int na = Math.round(a * fade);
                        if (na < 4) return (int) x;            // the font reads alpha < 4 as opaque: skip the line
                        color = (na << 24) | (color & 0xFFFFFF);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return fr.drawStringWithShadow(s, x, y, color);
    }

    /** Redirect target for the per-line {@code Gui.drawRect} in drawChat. Drops the dark line bg when the panel is up. */
    public static void rect(int x0, int y0, int x1, int y1, int argb) {
        if (panelActive && (argb & 0xFFFFFF) == 0) return;   // per-line dark background -> replaced by the panel
        Gui.drawRect(x0, y0, x1, y1, argb);                  // scrollbar rects (RGB != 0) + full fallback
    }

    // ---- input bar (GuiChat.drawScreen) ------------------------------------

    /**
     * Redirect target for the input-field background {@code drawRect} in {@code GuiChat.drawScreen}.
     *
     * <p><b>Appear fade (3a, 2026-10-09).</b> Pressing T pops the input bar in; instead it joins the shared
     * screen-open fade (150 ms) so it fades in with the GuiChat it belongs to. {@code ScreenOpenFade} is keyed by
     * screen identity and its clock restarts when the current screen changes, so passing the live GuiChat makes the
     * fade start from 0 the moment the bar first draws (nothing else drives it while a GuiChat is open). The field is
     * empty at that instant, so only the glass bar is faded (the text/caret follow their own typing animation).
     */
    public static void inputRect(int x0, int y0, int x1, int y1, int argb) {
        int lx = Math.min(x0, x1), rx = Math.max(x0, x1);
        int ty = Math.min(y0, y1), by = Math.max(y0, y1);
        int w = rx - lx, h = by - ty;
        if (w <= 0 || h <= 0) return;
        float fade = ScreenOpenFade.value(Minecraft.getMinecraft().currentScreen);
        if (fade <= 0.004f) return;
        boolean glass = GlassProgram.ensureReady() && GlassProgram.usable() && SceneCapture.hasBackdrop();
        if (glass) {
            GlassRenderer.glass(lx, ty, rx, by, GlassRenderer.PAD_PANEL,
                                GlassCorners.hotbarCorner(w, h), 0f, 0.9f * fade, GlassRenderer.FROST_PANEL);
            if (GlassProgram.roundUsable()) {
                int a = Math.round(((INPUT_SCRIM >>> 24) & 0xFF) * fade) & 0xFF;
                GlassRenderer.roundRect(lx, ty, rx, by, GlassCorners.hotbarRadiusPx(w, h),
                                        (a << 24) | (INPUT_SCRIM & 0xFFFFFF));
            }
        } else {
            int a = Math.round(((argb >>> 24) & 0xFF) * fade) & 0xFF;
            Gui.drawRect(lx, ty, rx, by, (a << 24) | (argb & 0xFFFFFF));
        }
    }

    // ---- reflection (two private GuiNewChat fields) ------------------------

    private static List<?> drawnLines(GuiNewChat self) {
        resolveFields();
        if (F_DRAWN == null) return null;
        try {
            Object v = F_DRAWN.get(self);
            return (v instanceof List) ? (List<?>) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int scrollPos(GuiNewChat self) {
        resolveFields();
        if (F_SCROLL == null) return 0;
        try {
            return F_SCROLL.getInt(self);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void resolveFields() {
        if (fieldsResolved) return;
        fieldsResolved = true;
        try {
            F_DRAWN = ReflectionHelper.findField(GuiNewChat.class, "drawnChatLines", "field_146253_i");
        } catch (Throwable t) { F_DRAWN = null; }
        try {
            F_SCROLL = ReflectionHelper.findField(GuiNewChat.class, "scrollPos", "field_146250_j");
        } catch (Throwable t) { F_SCROLL = null; }
    }
}
