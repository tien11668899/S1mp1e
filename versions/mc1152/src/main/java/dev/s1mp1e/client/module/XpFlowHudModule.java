package dev.s1mp1e.client.module;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.glass.render.HudLayout;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.Matrix4f;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.opengl.GL11;

/**
 * "Liquid sheen" on the vanilla experience bar — a slow gloss band that glides left→right across the EARNED
 * portion once every 4 s and then rests. Nothing else is touched: the bar itself is vanilla; this only lays a
 * highlight on top.
 *
 * <ul>
 *   <li>4.0 s cycle: the first 40% (1.6 s) is the glide, the last 60% (2.4 s) is a rest gap.</li>
 *   <li>Band 36 px wide (HALF 18 each side), Hann-feathered to 0 at its edges — no hard stop on a 5 px bar.</li>
 *   <li>Peak opacity 25% (alpha 64), scaled by a {@code sin(pi*u)} temporal envelope so the band fades IN and
 *       OUT during travel — no onset event at either edge, so it reads as a calm liquid tail.</li>
 *   <li>smootherstep easing on position; left→right, with the fill direction; clipped to the earned fill.</li>
 * </ul>
 *
 * <p><b>Colour ({@code Sheen colour}).</b> The band centre is the chosen colour; two nearby hues (±0.07 on
 * the wheel) sit at its edges, so the sheen reads as a little multi-colour ribbon. <b>Glow ({@code Glow}).</b>
 * optional feathered halo above/below the bar, tinted the same as the band.
 *
 * <p>Fair play: reads {@code mc.player.experienceProgress} only — the same fraction already on the bar.
 *
 * <p><b>1.15.2 draw path.</b> The bar's vertical position comes from {@link HudLayout#DECO_LIFT} (the px the
 * {@code InGameHudMixin} raises the XP bar by). Rather than one {@code DrawableHelper.fill} per 1 px column —
 * each its own Tessellator flush, hundreds per frame with the halo on — every column is collected into
 * preallocated arrays and emitted as ONE {@code POSITION_COLOR} quad draw, in the same order, so the blend
 * result is unchanged. Quads are wound TL&rarr;BL&rarr;BR&rarr;TR (the GUI cull winding) and pre-multiplied by
 * the caller's {@link MatrixStack} model matrix; blend is left ON (never {@code disableBlend}) and the
 * RenderSystem colour cache is reset 0&rarr;1 afterwards.
 */
public final class XpFlowHudModule extends Module implements HudRenderer {

    private static final int BAR_W = 182, BAR_H = 5, HALF = 18, GLOW = 3;

    /** Upper bound on batched columns: every band column plus its halo rows above and below. */
    private static final int MAX_QUADS = (2 * HALF + 1) * (1 + 2 * GLOW);

    public final Setting color = add(Setting.color("Sheen colour", 0xFFEAF6FF));   // cool glass white
    public final Setting glow  = add(Setting.bool("Glow", true));                  // soft emitted halo

    // Scratch for the batch — preallocated (the HUD pass is render-thread only) so a frame allocates nothing.
    private final int[] qx = new int[MAX_QUADS], qTop = new int[MAX_QUADS],
                        qBot = new int[MAX_QUADS], qArgb = new int[MAX_QUADS];

    public XpFlowHudModule() { super("XpFlow", "Visual"); this.enabled = true; }

    @Override
    public void renderHud() {
        if (!enabled) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        if (mc.interactionManager == null || !mc.interactionManager.hasExperienceBar()) return;

        float progress = MathHelper.clamp(mc.player.experienceProgress, 0f, 1f);
        if (progress <= 0f) return;

        int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        int x0 = sw / 2 - 91;
        int y0 = sh - 29 - HudLayout.DECO_LIFT, y1 = y0 + BAR_H;   // vanilla XP-bar top (sh-32+3), minus our lift
        int fillW = Math.round(progress * BAR_W);
        if (fillW <= 0) return;

        float p = (System.nanoTime() % 4_000_000_000L) / 4.0e9f;   // 0..1, real-time, frame-rate independent
        if (p >= 0.40f) return;                                    // rest gap: draw nothing
        float u = p / 0.40f;                                       // 0..1 during the 1.6 s glide
        float s = u * u * u * (u * (6f * u - 15f) + 10f);          // smootherstep (ease-in-out)
        float env = (float) Math.sin(Math.PI * u);                 // temporal fade: 0 at both ends
        float bx = x0 - HALF + s * (fillW + 2f * HALF);            // band centre crosses the fill + both margins
        float peak = 64f * env;                                    // 0..64 alpha

        int base = color.colorValue & 0xFFFFFF;
        float[] hsb = java.awt.Color.RGBtoHSB((base >> 16) & 0xFF, (base >> 8) & 0xFF, base & 0xFF, null);
        int cL = java.awt.Color.HSBtoRGB((hsb[0] - 0.07f + 1f) % 1f, hsb[1], hsb[2]) & 0xFFFFFF;   // nearby −
        int cR = java.awt.Color.HSBtoRGB((hsb[0] + 0.07f) % 1f,      hsb[1], hsb[2]) & 0xFFFFFF;   // nearby +
        boolean bloom = glow.boolValue;
        int n = 0;
        for (int i = -HALF; i <= HALF; i++) {
            int px = Math.round(bx) + i;
            if (px < x0 || px >= x0 + fillW) continue;             // clip to the EARNED XP only
            float w = 0.5f + 0.5f * (float) Math.cos(Math.PI * i / HALF);   // Hann window (1 centre → 0 edge)
            int a = Math.round(peak * w);
            if (a <= 0) continue;
            int col = grad3(cL, base, cR, (i + HALF) / (2f * HALF));        // nearby colours flowing together
            n = push(n, px, y0, y1, (a << 24) | col);
            if (bloom) {
                for (int gy = 1; gy <= GLOW; gy++) {
                    int ga = Math.round(a * 0.45f * (1f - gy / (float) (GLOW + 1)));   // feathered halo
                    if (ga <= 0) continue;
                    n = push(n, px, y0 - gy, y0 - gy + 1, (ga << 24) | col);   // above the bar
                    n = push(n, px, y1 + gy - 1, y1 + gy, (ga << 24) | col);   // below the bar
                }
            }
        }
        if (n > 0) drawBatch(n);
    }

    /** Queue one 1 px-wide column for the batch (silently drops anything past the bound — cannot happen). */
    private int push(int n, int x, int top, int bottom, int argb) {
        if (n >= MAX_QUADS || bottom <= top) return n;
        qx[n] = x; qTop[n] = top; qBot[n] = bottom; qArgb[n] = argb;
        return n + 1;
    }

    /** Emit the queued columns as ONE POSITION_COLOR quad draw (see the class javadoc for the GL contract). */
    private void drawBatch(int n) {
        RenderSystem.disableTexture();
        RenderSystem.enableBlend();
        RenderSystem.disableAlphaTest();
        RenderSystem.blendFuncSeparate(770, 771, 1, 0);
        Tessellator t = Tessellator.getInstance();
        BufferBuilder bb = t.getBuffer();
        bb.begin(GL11.GL_QUADS, VertexFormats.POSITION_COLOR);
        for (int i = 0; i < n; i++) {
            int argb = qArgb[i];
            int a = (argb >>> 24) & 0xFF, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
            float x = qx[i], yt = qTop[i], yb = qBot[i];
            // wound TL -> BL -> BR -> TR (GUI cull winding)
            bb.vertex(x,      yt, 0f).color(r, g, b, a).next();
            bb.vertex(x,      yb, 0f).color(r, g, b, a).next();
            bb.vertex(x + 1f, yb, 0f).color(r, g, b, a).next();
            bb.vertex(x + 1f, yt, 0f).color(r, g, b, a).next();
        }
        t.draw();
        RenderSystem.enableAlphaTest();
        RenderSystem.enableTexture();
        // Blend stays ENABLED (MC's baseline). Reset the colour cache so the next HUD draw is untinted.
        RenderSystem.color4f(0f, 0f, 0f, 0f);
        RenderSystem.color4f(1f, 1f, 1f, 1f);
    }

    /** 3-stop RGB gradient: c0 at t=0, c1 at t=0.5, c2 at t=1. */
    private static int grad3(int c0, int c1, int c2, float t) {
        int a, b; float u;
        if (t < 0.5f) { a = c0; b = c1; u = t * 2f; } else { a = c1; b = c2; u = (t - 0.5f) * 2f; }
        int r  = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * u);
        int g  = Math.round(((a >> 8)  & 0xFF) + (((b >> 8)  & 0xFF) - ((a >> 8)  & 0xFF)) * u);
        int bl = Math.round((a & 0xFF)         + ((b & 0xFF)         - (a & 0xFF))         * u);
        return (r << 16) | (g << 8) | bl;
    }
}
