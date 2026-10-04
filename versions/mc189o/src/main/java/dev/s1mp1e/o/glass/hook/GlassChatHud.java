package dev.s1mp1e.o.glass.hook;

import dev.s1mp1e.o.glass.render.GlassCorners;
import dev.s1mp1e.o.glass.render.GlassProgram;
import dev.s1mp1e.o.glass.render.GlassRenderer;
import dev.s1mp1e.o.glass.render.SceneCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatMessage;
import net.minecraft.client.gui.GuiElement;
import net.minecraft.client.gui.chat.ChatGui;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.entity.living.player.PlayerEntity;
import dev.s1mp1e.o.util.ReflectionHelper;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Feature G1 — chat panel + chat input.
 *
 * <p>{@code S1mp1eTransformer} splices {@link #begin} onto the head of {@code ChatGui.render} and redirects
 * that method's per-line {@code drawRect(...)} calls to {@link #rect}. {@link #begin} computes the visible message
 * column from vanilla's own state (line count, scroll, fade) and draws ONE frosted glass panel — hugging the
 * WIDEST visible line, not the full chat column — plus a soft grey scrim, in the same scaled chat space vanilla
 * draws the text in; {@link #rect} then drops the per-line dark background rects (RGB 0) while keeping the
 * scrollbar. The panel opacity carries the newest visible line's fade (full while the chat is focused), so it
 * fades out with the messages exactly like vanilla.
 *
 * <p>The open input field's background rect is redirected from {@code ChatScreen.render} to {@link #inputRect},
 * which paints a glass bar. Both surfaces reuse the world backdrop grabbed at the overlay-pass head, so they never
 * sample already-drawn glass (R4). New pieces -> hotbar corner radius via {@link GlassCorners} (R2).
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

    // ---- 第 7 組：聊天新訊息進場（ChatArrival）-------------------------------------------------------

    /** 這次 drawChat 的進場偏移（螢幕 px），以及計算它用的那一欄。 */
    private static float colOffsetPx;
    private static List<?> curDrawn;
    private static int curScroll;

    /**
     * 轉接 drawChat 的「第一個」{@code GlStateManager.translatef(2, 20, 0)}（聊天欄的位置；1.8.9 是 20，1.12.2 是 8）：
     * 加上進場偏移，整欄文字跟玻璃面板一起滑。
     */
    public static void translate(float x, float y, float z) {
        GlStateManager.translatef(x, y + colOffsetPx, z);
    }

    /**
     * 轉接 drawChat 每一行的 {@code drawStringWithShadow(s, x, j2 - 8, rgb + (alpha << 24))}：從 y 反推是第幾行
     * （{@code j2 = -i * 9}），把那一行的 alpha 乘上它的進場淡入。陰影由全域「不畫陰影」規則處理。
     */
    public static int text(net.minecraft.client.render.TextRenderer fr, String s, float x, float y, int color) {
        try {
            if (curDrawn != null) {
                int i = Math.round(-(y + 8f) / 9f);
                int idx = i + curScroll;
                if (idx >= 0 && idx < curDrawn.size()) {
                    float fade = dev.s1mp1e.o.glass.render.ChatArrival.lineFade(curDrawn.get(idx));
                    if (fade < 1f) {
                        int a = (color >>> 24) & 0xFF;
                        int na = Math.round(a * fade);
                        if (na < 4) return (int) x;            // 字型把 alpha < 4 當成不透明：這行直接不畫
                        color = (na << 24) | (color & 0xFFFFFF);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return fr.drawWithShadow(s, x, y, color);
    }

    private static boolean fieldsResolved;
    private static Field F_DRAWN, F_SCROLL;

    // ---- HEAD splice on ChatGui.render --------------------------------

    /** Draw the single glass chat panel. Runs at drawChat head, inside renderChat's translate(2, height-48). */
    public static void begin(ChatGui self, int updateCounter) {
        panelActive = false;
        colOffsetPx = 0f;
        try {
            // 第 7 組聊天進場：登記上一幀之後新來的行，取得這一幀整欄要往下偏的量（新行從下面推上來）。
            List<?> all = drawnLines(self);
            if (all != null) {
                int sp = scrollPos(self);
                dev.s1mp1e.o.glass.render.ChatArrival.track(all, Math.max(0, sp));
                curDrawn = all;
                curScroll = Math.max(0, sp);
                colOffsetPx = dev.s1mp1e.o.glass.render.ChatArrival.offset(9) * self.getChatScale();
            }
        } catch (Throwable ignored) {}
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.options == null) return;
            if (mc.options.chatVisibility == PlayerEntity.ChatVisibility.HIDDEN) return;

            List<?> drawn = drawnLines(self);
            if (drawn == null) return;
            int k = drawn.size();
            if (k == 0) return;
            int scrollPos = scrollPos(self);

            int lineCount = self.getVisibleLineCount();
            boolean open = self.isChatFocused();
            float scale = self.getChatScale();
            float f = mc.options.chatOpacity * 0.9f + 0.1f;

            int visible = 0, maxW = 0, newestAlpha = -1;
            for (int i1 = 0; i1 + scrollPos < k && i1 < lineCount; i1++) {
                Object o = drawn.get(i1 + scrollPos);
                if (!(o instanceof ChatMessage)) continue;
                ChatMessage line = (ChatMessage) o;
                int age = updateCounter - line.getTimeOfCreation();
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
                String text = line.getText().getFormattedString();
                int w = mc.textRenderer.getWidth(text);
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
            GlStateManager.translatef(2.0f, 20.0f + colOffsetPx, 0.0f);   // drawChat 自己的平移＋進場偏移
            GlStateManager.scalef(scale, scale, 1.0f);
            GlassProgram.setShadowScale(mc.screen != null ? 0f : 1f);
            try {
                GlassRenderer.glass(x0, y0, x1, y1, GlassRenderer.PAD_PANEL,
                                    GlassCorners.cornerKnob(w, h), 0f, panelAlpha, GlassRenderer.FROST_PANEL);
                if (GlassProgram.roundUsable()) {
                    int a = (int) (((SCRIM >>> 24) & 0xFF) * fade) & 0xFF;
                    GlassRenderer.roundRect(x0, y0, x1, y1, GlassCorners.radiusPx(w, h),
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

    /** Redirect target for the per-line {@code GuiElement.fill} in drawChat. Drops the dark line bg when the panel is up. */
    public static void rect(int x0, int y0, int x1, int y1, int argb) {
        if (panelActive && (argb & 0xFFFFFF) == 0) return;   // per-line dark background -> replaced by the panel
        GuiElement.fill(x0, y0, x1, y1, argb);                  // scrollbar rects (RGB != 0) + full fallback
    }

    // ---- input bar (ChatScreen.render) ------------------------------------

    /** Redirect target for the input-field background {@code drawRect} in {@code ChatScreen.render}. */
    public static void inputRect(int x0, int y0, int x1, int y1, int argb) {
        int lx = Math.min(x0, x1), rx = Math.max(x0, x1);
        int ty = Math.min(y0, y1), by = Math.max(y0, y1);
        int w = rx - lx, h = by - ty;
        if (w <= 0 || h <= 0) return;
        boolean glass = GlassProgram.ensureReady() && GlassProgram.usable() && SceneCapture.hasBackdrop();
        if (glass) {
            GlassRenderer.glass(lx, ty, rx, by, GlassRenderer.PAD_PANEL,
                                GlassCorners.cornerKnob(w, h), 0f, 0.9f, GlassRenderer.FROST_PANEL);
            if (GlassProgram.roundUsable()) {
                GlassRenderer.roundRect(lx, ty, rx, by, GlassCorners.radiusPx(w, h), INPUT_SCRIM);
            }
        } else {
            GuiElement.fill(lx, ty, rx, by, argb);
        }
    }

    // ---- reflection (two private ChatGui fields) ------------------------

    private static List<?> drawnLines(ChatGui self) {
        resolveFields();
        if (F_DRAWN == null) return null;
        try {
            Object v = F_DRAWN.get(self);
            return (v instanceof List) ? (List<?>) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int scrollPos(ChatGui self) {
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
            F_DRAWN = ReflectionHelper.findField(ChatGui.class, "drawnChatLines", dev.s1mp1e.o.util.Names.of("trimmedMessages", "f_88224172"));
        } catch (Throwable t) { F_DRAWN = null; }
        try {
            F_SCROLL = ReflectionHelper.findField(ChatGui.class, "scrollPos", dev.s1mp1e.o.util.Names.of("scroll", "f_01826520"));
        } catch (Throwable t) { F_SCROLL = null; }
    }
}
