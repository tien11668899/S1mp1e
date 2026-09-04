package dev.s1mp1e.client.module;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

/**
 * Custom crosshair — replaces the vanilla 16x16 icon sprite with shapes we draw
 * ourselves.
 *
 * <p><b>FAIR PLAY.</b> This crosshair is a function of its settings and the
 * screen size, and of nothing else. It never reads {@code mc.objectMouseOver},
 * {@code mc.pointedEntity}, entity positions or any distance, so it cannot leak
 * information the player does not already have on screen. A crosshair that
 * changed colour/size/state when something is targeted is a reach indicator and
 * is prohibited here.
 *
 * <p>GL state note: Forge's {@code GuiIngameForge.renderCrosshairs} is written as
 * {@code if (pre(CROSSHAIRS)) return;} — cancelling the Pre event returns out of
 * the method <em>before</em> its own blend cleanup
 * ({@code tryBlendFuncSeparate(SRC_ALPHA, ONE_MINUS_SRC_ALPHA); disableBlend()})
 * ever runs. So we own that cleanup now: whatever we do here must land on exactly
 * the state the vanilla path would have left, or every later HUD element — and
 * the glass renderer, which is sensitive to leaked GL state — draws wrong.
 */
public final class CrosshairModule extends Module {

    /** Segment count for the ring; enough that a 20 px circle has no visible facets. */
    private static final int CIRCLE_SEGMENTS = 48;

    private final Setting shape    = add(Setting.mode("Shape", "Cross", "Cross", "Dot", "Circle"));
    private final Setting size     = add(Setting.integer("Size", 4, 1, 20));
    private final Setting thick    = add(Setting.integer("Thickness", 1, 1, 5));
    private final Setting gap      = add(Setting.integer("Gap", 2, 0, 10));
    private final Setting colour   = add(Setting.color("Colour", 0xFFFFFFFF));
    private final Setting outline  = add(Setting.bool("Outline", true));
    private final Setting outlineC = add(Setting.color("Outline Colour", 0xC0000000));

    public CrosshairModule() {
        super("Crosshair", "Visual");
    }

    @Override
    public void onEnable() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public void onDisable() {
        MinecraftForge.EVENT_BUS.unregister(this);
    }

    @SubscribeEvent
    public void onRenderCrosshair(RenderGameOverlayEvent.Pre e) {
        if (!enabled) return;
        if (e.type != RenderGameOverlayEvent.ElementType.CROSSHAIRS) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;

        // Vanilla hides the crosshair behind the F3 screen (GuiIngame.showCrosshair);
        // its other branch is the spectator one, which peeks at pointedEntity /
        // objectMouseOver — we deliberately do NOT reproduce that, we simply always
        // draw. Drawing unconditionally reveals nothing; the vanilla branch does.
        boolean debugScreen = mc.gameSettings.showDebugInfo
                           && !mc.thePlayer.hasReducedDebug()
                           && !mc.gameSettings.reducedDebugInfo;

        e.setCanceled(true);
        if (debugScreen) {
            // Cancelled anyway, but the vanilla cleanup below still has to happen
            // because renderCrosshairs() returned early and never ran its own.
            restoreState();
            return;
        }

        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2;
        int cy = sr.getScaledHeight() / 2;

        draw(cx, cy);
        restoreState();
    }

    private void draw(int cx, int cy) {
        // The one and only source of the crosshair colour. This value must NEVER
        // be derived from world state (targeted entity, hit result, distance) —
        // that would turn the crosshair into a reach/target indicator.
        final int colour = this.colour.colorValue;
        final int oColour = this.outlineC.colorValue;
        final boolean drawOutline = this.outline.boolValue;

        final int s = this.size.intValue;
        final int t = this.thick.intValue;
        final int g = this.gap.intValue;

        // Blend on, alpha test off: the HUD runs with alphaFunc(GREATER, 0.1) and a
        // deliberately faint crosshair would otherwise be clipped away entirely.
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableAlpha();

        String mode = this.shape.modeValue;

        if ("Circle".equals(mode)) {
            // Radius matches the cross's arm tips so switching shape keeps the
            // same overall footprint on screen.
            float outer = g + s;
            float inner = Math.max(0f, outer - t);
            // Clamp like the fill's inner rim: a negative radius mirrors the inner
            // vertices through the centre and folds the strip into a bowtie.
            if (drawOutline) ring(cx, cy, Math.max(0f, inner - 1f), outer + 1f, oColour);
            ring(cx, cy, inner, outer, colour);
        } else {
            int[][] rects = "Dot".equals(mode) ? dotRects(cx, cy, t)
                                               : crossRects(cx, cy, s, t, g);
            // Two passes: every outline first, then every fill. With gap 0 the four
            // arms touch, and a per-arm outline drawn inline would paint over the
            // neighbouring arm's fill.
            if (drawOutline) {
                for (int i = 0; i < rects.length; i++) {
                    int[] r = rects[i];
                    Gui.drawRect(r[0] - 1, r[1] - 1, r[2] + 1, r[3] + 1, oColour);
                }
            }
            for (int i = 0; i < rects.length; i++) {
                int[] r = rects[i];
                Gui.drawRect(r[0], r[1], r[2], r[3], colour);
            }
        }
    }

    /** Filled square of side {@code t}; size and gap have no meaning for a dot. */
    private static int[][] dotRects(int cx, int cy, int t) {
        int half = t / 2;
        int x0 = cx - half;
        int y0 = cy - half;
        return new int[][] { { x0, y0, x0 + t, y0 + t } };
    }

    /**
     * Four arms of length {@code s}, each starting {@code g} px out from centre.
     * {@code half = t / 2} keeps an odd thickness centred on the exact centre
     * pixel instead of straddling it.
     */
    private static int[][] crossRects(int cx, int cy, int s, int t, int g) {
        int half = t / 2;
        int bandY0 = cy - half;
        int bandY1 = bandY0 + t;
        int bandX0 = cx - half;
        int bandX1 = bandX0 + t;
        return new int[][] {
            { cx - g - s, bandY0, cx - g,     bandY1 },   // left
            { cx + g,     bandY0, cx + g + s, bandY1 },   // right
            { bandX0, cy - g - s, bandX1, cy - g     },   // up
            { bandX0, cy + g,     bandX1, cy + g + s }    // down
        };
    }

    /**
     * Annulus as a triangle strip alternating inner/outer rim vertices — one draw
     * call, no per-quad state churn. Follows Gui.drawRect's contract exactly
     * (texturing off while drawing, back on afterwards) so callers see no surprise.
     */
    private static void ring(float cx, float cy, float inner, float outer, int argb) {
        float a = (float) (argb >> 24 & 255) / 255.0F;
        float r = (float) (argb >> 16 & 255) / 255.0F;
        float g = (float) (argb >> 8 & 255) / 255.0F;
        float b = (float) (argb & 255) / 255.0F;

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldrenderer = tessellator.getWorldRenderer();

        GlStateManager.disableTexture2D();
        GlStateManager.color(r, g, b, a);
        worldrenderer.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION);
        for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
            double ang = (Math.PI * 2.0D) * i / CIRCLE_SEGMENTS;
            double sin = Math.sin(ang);
            double cos = Math.cos(ang);
            worldrenderer.pos(cx + sin * outer, cy - cos * outer, 0.0D).endVertex();
            worldrenderer.pos(cx + sin * inner, cy - cos * inner, 0.0D).endVertex();
        }
        tessellator.draw();
        GlStateManager.enableTexture2D();
    }

    /**
     * The exact state GuiIngameForge.renderCrosshairs leaves when it is allowed to
     * run: standard alpha blending, blend disabled, alpha test on, colour reset to
     * white (renderGameOverlay sets white just before calling it, and later
     * elements bind textures expecting an untinted colour).
     */
    private static void restoreState() {
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
