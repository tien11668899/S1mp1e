package dev.s1mp1e.glass.render;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.GlBackend;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.GlTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * 1.21.5 moved rendering onto {@code GpuDevice}: render state lives in each pipeline, so the {@code RenderSystem} state
 * setters the immediate-GL glass used ({@code enableBlend}, {@code depthMask}, {@code bindTexture}, …) are gone, and
 * every vanilla render pass binds its own target and ends with framebuffer 0 bound — the window, not the main render
 * target the frame is composed in. The glass programs still draw with raw GL, so this is the state they need, set
 * through {@link GlStateManager} (whose caches the GL backend reads) and with the main target bound for the draw.
 */
public final class GlCompat {
    private GlCompat() {}

    public static void enableBlend()  { GlStateManager._enableBlend(); }
    public static void disableBlend() { GlStateManager._disableBlend(); }
    public static void blendFuncSeparate(int srcRgb, int dstRgb, int srcA, int dstA) {
        GlStateManager._blendFuncSeparate(srcRgb, dstRgb, srcA, dstA);
    }
    public static void enableCull()       { GlStateManager._enableCull(); }
    public static void disableCull()      { GlStateManager._disableCull(); }
    public static void depthMask(boolean on) { GlStateManager._depthMask(on); }
    public static void enableDepthTest()  { GlStateManager._enableDepthTest(); }
    public static void disableDepthTest() { GlStateManager._disableDepthTest(); }
    public static void activeTexture(int unit) { GlStateManager._activeTexture(unit); }
    public static void bindTexture(int id)     { GlStateManager._bindTexture(id); }

    /** The GL name of a texture's storage (1.21.5: a {@code GpuTexture}, the GL backend's {@link GlTexture}). */
    public static int glId(AbstractTexture texture) {
        return texture == null ? 0 : ((GlTexture) texture.getGlTexture()).getGlId();
    }

    /** The framebuffer object of the main render target (colour + depth), created on first use by the GL backend. */
    public static int mainFbo() {
        Framebuffer fb = MinecraftClient.getInstance().getFramebuffer();
        GlTexture color = (GlTexture) fb.getColorAttachment();
        return color.getOrCreateFramebuffer(((GlBackend) RenderSystem.getDevice()).getFramebufferManager(),
                fb.useDepthAttachment ? fb.getDepthAttachment() : null);
    }

    /** Binds the main render target for an immediate-GL draw or copy; returns the binding to give back afterwards. */
    public static int bindMainTarget() {
        int prev = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, mainFbo());
        return prev;
    }

    public static void restoreTarget(int prev) {
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
    }
}
