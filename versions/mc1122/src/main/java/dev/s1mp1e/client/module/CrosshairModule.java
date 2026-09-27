package dev.s1mp1e.client.module;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

/**
 * Custom crosshair — replaces the vanilla 16x16 icon sprite with shapes we draw
 * ourselves.
 *
 * <p><b>FAIR PLAY.</b> This crosshair is a function of its settings and the
 * screen size, and of nothing else. It never reads the hit result, the pointed
 * entity, entity positions or any distance, so it cannot leak information the
 * player does not already have on screen. A crosshair that changed
 * colour/size/state when something is targeted is a reach indicator and is
 * prohibited here. For the same reason two branches of vanilla's
 * {@code GuiIngame.renderAttackIndicator} are deliberately NOT reproduced: the
 * spectator branch (which hides the crosshair unless the hit result is an
 * inventory block) and the crosshair-mode attack indicator (whose "ready"
 * variant keys off the pointed entity).
 *
 * <p>GL state note: Forge's 1.12.2 {@code GuiIngameForge.renderCrosshairs} is
 * {@code if (pre(CROSSHAIRS)) return; bind(ICONS); enableBlend(); renderAttackIndicator(); post();}
 * — cancelling the Pre event returns before any of that runs, so we own the tail
 * state. Unlike 1.8.9 there is NO {@code disableBlend} in that method: blend stays
 * ENABLED afterwards, and so it does here. Whatever we do must land on that state,
 * or every later HUD element — and the glass renderer, which is sensitive to
 * leaked GL state — draws wrong.
 */
public final class CrosshairModule extends Module {

    /** Segment count for the ring; enough that a 20 px circle has no visible facets. */
    private static final int CIRCLE_SEGMENTS = 48;

    /** GuiIngameForge sets {@code zLevel = -90} right before renderCrosshairs; the F3 axes use it. */
    private static final float HUD_Z = -90.0F;

    public final Setting shape    = add(Setting.mode("Shape", "Cross", "Cross", "T", "Dot", "Circle"));
    public final Setting size     = add(Setting.integer("Size", 4, 1, 20));
    public final Setting thick    = add(Setting.integer("Thickness", 1, 1, 5));
    public final Setting gap      = add(Setting.integer("Gap", 2, 0, 10));
    public final Setting rotation = add(Setting.integer("Rotation", 0, 0, 45));
    private final Setting centerDot = add(Setting.bool("Center Dot", false));
    private final Setting dotSize  = add(Setting.integer("Dot Size", 2, 1, 6));
    private final Setting colour   = add(Setting.color("Colour", 0xFFFFFFFF));
    public final Setting outline  = add(Setting.bool("Outline", true));
    private final Setting outlineC = add(Setting.color("Outline Colour", 0xC0000000));

    public CrosshairModule() {
        super("Crosshair", "Visual");
    }

    @Override
    public void onEnable() {
        // EventBus.register is idempotent for an already-registered object, and
        // Module.setEnabled only calls this on a real off->on change.
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public void onDisable() {
        MinecraftForge.EVENT_BUS.unregister(this);
    }

    @SubscribeEvent
    public void onRenderCrosshair(RenderGameOverlayEvent.Pre e) {
        if (!enabled) return;
        if (e.getType() != RenderGameOverlayEvent.ElementType.CROSSHAIRS) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) return;
        GameSettings gs = mc.gameSettings;
        // Third person: vanilla draws nothing here either, so leave its path alone.
        if (gs.thirdPersonView != 0) return;

        e.setCanceled(true);

        ScaledResolution sr = e.getResolution() != null ? e.getResolution() : new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2;
        int cy = sr.getScaledHeight() / 2;

        // F3: vanilla swaps the crosshair for the orientation axes under exactly this
        // condition. Reproduced verbatim — it only reads the camera's own rotation.
        boolean debugScreen = gs.showDebugInfo
                           && !gs.hideGUI
                           && !mc.player.hasReducedDebug()
                           && !gs.reducedDebugInfo;

        try {
            // The glass HUD pass draws with raw glColor4f, which leaves GlStateManager's
            // colour cache out of sync with the actual GL colour. Force the cache to a
            // known state (write, then reset to white) before any GlStateManager
            // colour/tint call, or the first Gui.drawRect can pick up the stale tint.
            GlStateManager.color(0.0F, 0.0F, 0.0F, 0.0F);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            // Forge's renderCrosshairs prelude (enableBlend) that the cancel skipped.
            GlStateManager.enableBlend();
            if (debugScreen) {
                drawDebugAxes(mc, cx, cy, e.getPartialTicks());
            } else {
                draw(cx, cy);
            }
        } catch (Throwable t) {
            // A cosmetic draw must never take the HUD down; the tail below still runs.
        } finally {
            restoreState(mc);
        }
    }

    /** Vanilla's F3 crosshair: the XYZ direction gizmo, rotated by the camera's own view angles. */
    private static void drawDebugAxes(Minecraft mc, int cx, int cy, float partialTicks) {
        Entity view = mc.getRenderViewEntity();
        if (view == null) return;
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate((float) cx, (float) cy, HUD_Z);
            GlStateManager.rotate(view.prevRotationPitch + (view.rotationPitch - view.prevRotationPitch) * partialTicks,
                    -1.0F, 0.0F, 0.0F);
            GlStateManager.rotate(view.prevRotationYaw + (view.rotationYaw - view.prevRotationYaw) * partialTicks,
                    0.0F, 1.0F, 0.0F);
            GlStateManager.scale(-1.0F, -1.0F, -1.0F);
            OpenGlHelper.renderDirections(10);
        } finally {
            GlStateManager.popMatrix();
        }
    }

    private void draw(int cx, int cy) {
        // (The colour cache was reset by the caller before this runs.)

        // The one and only source of the crosshair colour. This value must NEVER
        // be derived from world state (targeted entity, hit result, distance) —
        // that would turn the crosshair into a reach/target indicator.
        final int colour = this.colour.colorValue;
        final int oColour = this.outlineC.colorValue;
        final boolean drawOutline = this.outline.boolValue;

        final int s = this.size.intValue;
        final int t = this.thick.intValue;
        final int g = this.gap.intValue;
        final int rot = this.rotation.intValue;

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
                          : "T".equals(mode)   ? tRects(cx, cy, s, t, g)
                                               : crossRects(cx, cy, s, t, g);
            // Rotation spins the arms about the exact centre (Dot is rotationally
            // symmetric, so skip the matrix cost there).
            boolean rotated = rot != 0 && !"Dot".equals(mode);
            if (rotated) {
                GlStateManager.pushMatrix();
                GlStateManager.translate(cx, cy, 0f);
                GlStateManager.rotate(rot, 0f, 0f, 1f);
                GlStateManager.translate(-cx, -cy, 0f);
            }
            try {
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
            } finally {
                if (rotated) GlStateManager.popMatrix();
            }
        }

        // Optional centre dot, drawn upright on top of whatever shape is active.
        if (this.centerDot.boolValue && !"Dot".equals(mode)) {
            int[][] d = dotRects(cx, cy, this.dotSize.intValue);
            if (drawOutline) {
                for (int i = 0; i < d.length; i++) {
                    int[] r = d[i];
                    Gui.drawRect(r[0] - 1, r[1] - 1, r[2] + 1, r[3] + 1, oColour);
                }
            }
            for (int i = 0; i < d.length; i++) {
                int[] r = d[i];
                Gui.drawRect(r[0], r[1], r[2], r[3], colour);
            }
        }
    }

    // ---- geometry (pure int math, ported from mc1211) ----
    //
    // CENTERING. cx,cy are scaledWidth/2, scaledHeight/2 — a pixel BOUNDARY, not a pixel. A 1-px line
    // cannot straddle it; it commits to one side and is a half-pixel off (the same reason vanilla's own
    // crosshair sits ~1px off centre). What reads as "centred" is that the four arms are EXACTLY equal in
    // length and gap. Both bar bands are placed with a single {@code off = (t+1)/2} — biasing the
    // sub-pixel the SAME way vanilla does (up-left) — and each arm is measured symmetrically from the band
    // edge, so left==right and up==down for every thickness.

    /** Vertical-bar / horizontal-bar offset from the centre boundary. {@code (t+1)/2} puts a 1-px mark on
     *  the up-left pixel of the boundary — matching vanilla's bias — and keeps an even mark centred. */
    private static int barOffset(int t) { return (t + 1) / 2; }

    /** T-crosshair: the cross minus its up arm (opening upward). Same symmetric left/right/down arms. */
    private static int[][] tRects(int cx, int cy, int s, int t, int g) {
        int off = barOffset(t);
        int bx0 = cx - off, bx1 = bx0 + t;
        int by0 = cy - off, by1 = by0 + t;
        return new int[][] {
            { bx0 - g - s, by0, bx0 - g,     by1 },   // left
            { bx1 + g,     by0, bx1 + g + s, by1 },   // right
            { bx0, by1 + g,     bx1, by1 + g + s }    // down
        };
    }

    /** Filled square of side {@code d}, centred on the same boundary bias as the bars. */
    private static int[][] dotRects(int cx, int cy, int d) {
        int off = barOffset(d);
        int x0 = cx - off, y0 = cy - off;
        return new int[][] { { x0, y0, x0 + d, y0 + d } };
    }

    /** Cross: four arms of equal length {@code s}, each {@code g} px out from the bar edge, symmetric
     *  about the bar centre on both axes. */
    private static int[][] crossRects(int cx, int cy, int s, int t, int g) {
        int off = barOffset(t);
        int bx0 = cx - off, bx1 = bx0 + t;   // vertical bar columns
        int by0 = cy - off, by1 = by0 + t;   // horizontal bar rows
        return new int[][] {
            { bx0 - g - s, by0, bx0 - g,     by1 },   // left  (horizontal band)
            { bx1 + g,     by0, bx1 + g + s, by1 },   // right
            { bx0, by0 - g - s, bx1, by0 - g },       // up    (vertical band)
            { bx0, by1 + g,     bx1, by1 + g + s }    // down
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
        BufferBuilder buffer = tessellator.getBuffer();

        GlStateManager.disableTexture2D();
        GlStateManager.color(r, g, b, a);
        buffer.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION);
        for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
            double ang = (Math.PI * 2.0D) * i / CIRCLE_SEGMENTS;
            double sin = Math.sin(ang);
            double cos = Math.cos(ang);
            buffer.pos(cx + sin * outer, cy - cos * outer, 0.0D).endVertex();
            buffer.pos(cx + sin * inner, cy - cos * inner, 0.0D).endVertex();
        }
        tessellator.draw();
        GlStateManager.enableTexture2D();
    }

    /**
     * The state GuiIngameForge.renderCrosshairs leaves on 1.12.2 when it is allowed
     * to run: ICONS bound, blend ENABLED (1.12.2 has no disableBlend here — do not
     * port 1.8.9's), standard alpha blend function, alpha test on, texturing on, and
     * the colour cache reset to white (later elements bind textures expecting an
     * untinted colour, and the ring/drawRect path wrote a raw tint).
     */
    private static void restoreState(Minecraft mc) {
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.color(0.0F, 0.0F, 0.0F, 0.0F);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        try {
            mc.getTextureManager().bindTexture(Gui.ICONS);
        } catch (Throwable ignored) {
            // binding is cosmetic parity with vanilla's tail; never fail the HUD over it
        }
    }
}
