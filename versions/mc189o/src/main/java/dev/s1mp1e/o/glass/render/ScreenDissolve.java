package dev.s1mp1e.o.glass.render;

import dev.s1mp1e.o.client.gui.ScreenOpenFade;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.Window;
import net.minecraft.client.gui.screen.inventory.menu.InventoryMenuScreen;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.platform.GLX;
import net.minecraft.client.render.pipeline.RenderTarget;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * 選單到選單的交叉淡化（第 5 組）：遊戲外的畫面切換不再硬切。1.8.9 版，移植自 mc1122 的 {@code ScreenDissolve}
 * （它本身是 mc1144 {@code ScreenDissolve} ＝ LiquidGlass26 {@code ScreenTransition} 的移植），取代這條線舊的
 * {@code ScreenFade}（150 ms 線性淡出，用的是每幀結尾、20 Hz 的複製，可能晚 50 ms）。
 *
 * <p>切換畫面時，把「上一張畫完的幀」——仍然留在主 framebuffer 裡，因為 {@code displayGuiScreen} 是在兩幀之間由
 * tick／輸入回呼呼叫的——複製進一張快照材質，接下來 {@link #DURATION_S} 內以 smoothstep 下降的 alpha 畫在所有東西
 * 上面；同時把 {@link ScreenOpenFade} 固定在 1，讓進來的畫面從第一幀起就在底下完整呈現：真正的交叉淡化，沒有半成品幀。
 * 淡化途中又切換時，重新抓目前（已混合）的幀，所以連續切換也是連續的。
 *
 * <p><b>1.8.9 的接法。</b>觸發：Forge 的 {@code GuiOpenEvent}（在 {@code Minecraft.openScreen} 開頭送出，
 * 此時 {@code currentScreen} 還是要離開的畫面）。繪製：{@code GlassTopLayer} 在 {@code RenderTickEvent} END——1.8.9
 * 的遊戲迴圈在 {@code updateCameraAndRender}（世界、HUD、畫面＋tooltip）之後送出它，主 framebuffer 仍綁著。
 * 唯一畫在它之後的是「成就達成」小視窗（{@code guiAchievement.updateAchievementWindow}），會蓋在淡化上面——
 * 很少出現，可接受。固定管線：一個 {@code GL_MODULATE} 的貼圖四邊形配 {@code glColor4f(1,1,1,a)}，原生 GL 包在
 * {@code glPushAttrib} 裡、再讓 GlStateManager 重新同步——不用 shader，所以「絕不能在 setScreen 裡建玻璃程式」
 * 這個陷阱不會發生（這裡完全不碰 {@code GlassProgram}）。
 *
 * <p>有自己開關動態的畫面不套用：容器（玻璃面板淡入＋關閉殘影）和聊天輸入。
 */
public final class ScreenDissolve {

    private ScreenDissolve() {}

    public static final float DURATION_S = 0.22f;

    private static final int GL_FRAMEBUFFER_BINDING = 0x8CA6;

    private static long startNs;
    private static boolean active;
    private static int snapTex = 0, snapW = 0, snapH = 0;

    /** 只給 DevShot 用：上一幀畫快照時用的 alpha（-1 = 沒有）。 */
    public static float lastAlpha = -1f;

    /** displayGuiScreen 開頭（GuiOpenEvent）：{@code from} 還是目前畫面，{@code to} 是要進來的畫面。 */
    public static void onSetScreen(Screen from, Screen to) {
        if (from == to) return;
        if (excluded(from) || excluded(to)) return;
        if (from == null && Minecraft.getInstance().world == null) return;   // 開機：還沒有畫完的幀可以淡
        if (!begin()) return;
        ScreenOpenFade.holdUntil(startNs + (long) (DURATION_S * 1.0e9f));
    }

    /** 同一個畫面內的內容切換（創造模式分類、更多世界選項）。 */
    public static void onTabSwitch() {
        begin();
    }

    public static boolean canDissolve() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && GLX.useFbo() && mc.getRenderTarget() != null;
    }

    private static boolean excluded(Screen s) {
        return s instanceof InventoryMenuScreen || s instanceof ChatScreen;
    }

    private static boolean begin() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isOnSameThread()) return false;
        if (!canDissolve()) return false;
        if (!grabSnapshot()) return false;
        startNs = System.nanoTime();
        active = true;
        return true;
    }

    /** 把主 FBO（上一張畫完的幀）複製進快照；之前綁定的 FBO／材質會還原。 */
    private static boolean grabSnapshot() {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget fb = mc.getRenderTarget();
        if (fb == null || fb.frameBufferId <= 0) return false;
        int w = fb.width, h = fb.height;
        if (w <= 0 || h <= 0) return false;

        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevFbo = GL11.glGetInteger(GL_FRAMEBUFFER_BINDING);

        if (snapTex == 0 || snapW != w || snapH != h) {
            if (snapTex != 0) GL11.glDeleteTextures(snapTex);
            snapTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, snapTex);
            // 複製之前先定義 level 0 並設非 mipmap 的濾鏡（不完整的材質取樣起來等於「關掉貼圖」——
            // 這條線第一版 ScreenFade 的白色閃光就是這樣來的）
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, w, h, 0, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE,
                    (java.nio.ByteBuffer) null);
            snapW = w; snapH = h;
        } else {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, snapTex);
        }

        if (prevFbo != fb.frameBufferId) GLX.bindFramebuffer(GLX.GL_FRAMEBUFFER, fb.frameBufferId);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
        if (prevFbo != fb.frameBufferId) GLX.bindFramebuffer(GLX.GL_FRAMEBUFFER, prevFbo);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        GlStateManager.bindTexture(0);
        GlStateManager.bindTexture(prevTex);
        return true;
    }

    /** 把淡出中的快照畫在這一幀所有東西上面（GlassTopLayer，GUI 正交投影已設好）。 */
    public static void draw() {
        if (!active) { lastAlpha = -1f; return; }
        Minecraft mc = Minecraft.getInstance();
        float t = (System.nanoTime() - startNs) / 1.0e9f / DURATION_S;
        RenderTarget fb = mc.getRenderTarget();
        if (t >= 1f || fb == null || fb.width != snapW || fb.height != snapH
                || snapTex == 0) {
            end();
            return;
        }
        float s = t <= 0f ? 0f : t * t * (3f - 2f * t);   // smoothstep
        float alpha = 1f - s;
        lastAlpha = alpha;
        if (alpha <= 1f / 255f) return;

        Window sr = new Window(mc);
        float w = sr.getWidth(), h = sr.getHeight();
        // fb 材質是整個（可能比較大的）FBO；畫面只佔 framebufferWidth x framebufferHeight
        float u1 = fb.viewWidth / (float) snapW, v1 = fb.viewHeight / (float) snapH;

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_TEXTURE_BIT
                | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, snapTex);
        GL11.glColor4f(1f, 1f, 1f, alpha);
        GL11.glBegin(GL11.GL_QUADS);                       // 材質 y 朝上、GUI y 朝下：畫面頂端 = v1
        GL11.glTexCoord2f(0f, v1); GL11.glVertex2f(0f, 0f);
        GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(0f, h);
        GL11.glTexCoord2f(u1, 0f); GL11.glVertex2f(w, h);
        GL11.glTexCoord2f(u1, v1); GL11.glVertex2f(w, 0f);
        GL11.glEnd();
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glPopAttrib();
        GlStateManager.bindTexture(0);
        GlStateManager.color4f(0f, 0f, 0f, 0f);
        GlStateManager.color4f(1f, 1f, 1f, 1f);
    }

    public static boolean active() { return active; }

    private static void end() {
        active = false;
        lastAlpha = -1f;
        ScreenOpenFade.holdUntil(0L);
    }
}
