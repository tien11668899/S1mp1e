package dev.s1mp1e.client.module;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.Chroma;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;

/**
 * Attack-cooldown as a liquid-glass RING around the crosshair (replaces vanilla's crosshair attack-indicator sprites,
 * which {@code AttackIndicatorHideMixin} drops while this is on). Shows exactly what vanilla already shows - your own
 * attack strength - in the S1mp1e style (1.20.1 port of the 26.2 module):
 * <ul>
 *   <li>a glass ring track ({@code glass_ring.fsh}, the same material as every other glass surface), concentric and a
 *       little wider than the fill;</li>
 *   <li>a slider-blue arc on top that grows clockwise from 12 o'clock with the cooldown ({@code ring_arc.fsh}, round
 *       caps);</li>
 *   <li>fades in while charging, fades out when charged; like vanilla's "full" indicator it stays full while you aim at
 *       a living target with a slow weapon (attack-cooldown period above 5 ticks).</li>
 * </ul>
 * Drawn from {@code CrosshairMixin} at {@code InGameHud.renderCrosshair} HEAD: the buffered HUD is flushed, the raw-GL
 * ring lands on it, and the crosshair (vanilla sprite or the custom one) is drawn after - so the crosshair stays on top.
 */
public final class AttackRingModule extends Module {
    public final Setting radius = add(Setting.number("Ring radius", 11.0D, 4.0D, 30.0D));
    public final Setting width  = add(Setting.number("Ring width", 2.0D, 0.5D, 8.0D));
    public final Setting color        = add(Setting.color("Ring colour", 0xFF0A84FF));
    public final Setting readyColor   = add(Setting.color("Ready colour", 0xFF0A84FF));
    public final Setting chroma       = add(Setting.bool("Chroma", false));
    public final Setting chromaSpeed  = add(Setting.number("Chroma speed", 1.0D, 0.1D, 5.0D));
    public final Setting glassTrack   = add(Setting.bool("Glass track", true));
    public final Setting trackOpacity = add(Setting.number("Track opacity", 1.0D, 0.2D, 1.0D));
    public final Setting clockwise    = add(Setting.bool("Clockwise", true));
    public final Setting offsetX      = add(Setting.integer("Offset X", 0, -40, 40));
    public final Setting offsetY      = add(Setting.integer("Offset Y", 0, -40, 40));
    public final Setting ready        = add(Setting.bool("Show when ready", true));
    /** Indicator shape while charging: a circle, a rounded square, or a plus outline that wraps the crosshair. */
    public final Setting shape        = add(Setting.mode("Shape", "Circle", "Circle", "Square", "Wrap"));
    /** Shape when charged and aiming at a living target ("Same" = the charging shape). */
    public final Setting readyShape   = add(Setting.mode("Ready shape", "Same", "Same", "Circle", "Square", "Wrap"));
    /** Size of the ready (full) indicator; 0 = the same as Ring radius / Ring width. */
    public final Setting readyRadius  = add(Setting.number("Ready size", 0.0D, 0.0D, 30.0D));
    public final Setting readyWidth   = add(Setting.number("Ready width", 0.0D, 0.0D, 8.0D));
    /** Wrap follows the ACTIVE crosshair: the S1mp1e crosshair's size / gap / thickness / rotation, or vanilla's. */
    public final Setting fitCrosshair = add(Setting.bool("Fit crosshair", true));
    /** Space between the crosshair and the wrap's inner edge when fitting. */
    public final Setting fitPad       = add(Setting.number("Fit padding", 1.5D, 0.0D, 6.0D));

    /** The glass track reaches this far past the fill on each side (concentric, like the boss bar). */
    private static final float GLASS_MARGIN = 1.5f;

    private static AttackRingModule instance;
    private float vis;
    /** Displayed radius / width, eased toward the charging or ready size so a state change morphs, not jumps. */
    private float curR = -1f, curW = -1f;
    private long lastNanos;
    /** The progress the arc was last drawn at (read by the dev capture harness to check the sweep is continuous). */
    public static float lastProgress;

    public AttackRingModule() {
        super("AttackRing", "Combat");
        this.enabled = true;
        instance = this;
    }

    public static boolean active() {
        AttackRingModule m = instance;
        return m != null && m.enabled;
    }

    /** Called under the crosshair (see class doc). */
    public static void drawUnderCrosshair(DrawContext ctx) {
        AttackRingModule m = instance;
        if (m != null && m.enabled) m.draw(ctx);
    }

    private void draw(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerEntity p = mc.player;
        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 1f / 60f : Math.min(0.1f, (now - lastNanos) / 1.0e9f);
        lastNanos = now;
        if (p == null || mc.options.hudHidden || !mc.options.getPerspective().isFirstPerson()) { vis = 0f; return; }

        // The cooldown advances once per game tick (20 Hz); with a partial tick it is continuous, so the arc sweeps
        // every frame instead of stepping (vanilla's own indicator passes 0 and steps — fine for its 16 px sprite).
        // 1.20.1 has no RenderTickCounter on the client API: MinecraftClient.getTickDelta() is the frame's partial tick.
        float strength = p.getAttackCooldownProgress(mc.getTickDelta());
        boolean charging = strength < 0.999f;
        boolean full = !charging && ready.boolValue && p.getAttackCooldownProgressPerTick() > 5.0f
                && mc.targetedEntity instanceof LivingEntity le && le.isAlive();
        float target = (charging || full) ? 1f : 0f;
        // Apple-style: in ~100 ms, out ~150 ms
        float tau = target > vis ? 0.035f : 0.05f;
        vis += (target - vis) * (1f - (float) Math.exp(-dt / tau));
        if (vis < 0.01f) return;
        if (!GlassProgram.ensureReady()) return;

        int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        float cx = (sw - 15) / 2 + 7.5f + offsetX.intValue;          // vanilla crosshair centre + nudge
        float cy = (sh - 15) / 2 + 7.5f + offsetY.intValue;
        float tr = (float) radius.doubleValue, tw = (float) width.doubleValue;
        if (!charging) {
            if (readyRadius.doubleValue > 0.0) tr = (float) readyRadius.doubleValue;
            if (readyWidth.doubleValue > 0.0) tw = (float) readyWidth.doubleValue;
        }
        int shp = shapeCode(charging);
        float ratio = 0.45f, rot = 0f, gap = 0f;
        if (shp == 2 && fitCrosshair.boolValue) {                    // Wrap hugs the active crosshair
            float[] xh = crosshairGeom(sw, sh);
            cx = xh[0] + offsetX.intValue;
            cy = xh[1] + offsetY.intValue;
            float pad = (float) fitPad.doubleValue;
            tr = xh[2] + pad + tw;                                   // inner edge = crosshair + pad
            if (xh[5] > 0.5f) {
                shp = 0;                                             // round crosshair -> round wrap
            } else {
                float rc = xh[2] + pad + tw / 2f;                    // band centre line
                ratio = (xh[3] + pad + tw / 2f) / rc;
                rot = xh[4];
                // Separated crosshair (a gap between the arms) -> a hollow plus that wraps each arm on its own;
                // the hole reaches the crosshair's inner gap, tightened by the same padding (0 = a joined plus).
                gap = Math.max(0f, (xh[6] - pad)) / rc;
            }
        }
        if (curR < 0f) { curR = tr; curW = tw; }
        float k = 1f - (float) Math.exp(-dt / 0.05f);           // ~150 ms ease between the two sizes
        curR += (tr - curR) * k;
        curW += (tw - curW) * k;
        float r = curR;
        float w = curW;
        int argb = colour(charging);
        int a = Math.round(((argb >>> 24) & 0xFF) * vis);
        int fill = (a << 24) | (argb & 0xFFFFFF);
        float progress = charging ? strength : 1f;
        lastProgress = progress;

        if (!SceneCapture.hasBackdrop()) SceneCapture.grabNow();
        ctx.draw();                                                  // flush: the ring lands on the buffered HUD
        RenderSystem.disableDepthTest();
        boolean rotated = rot != 0f;
        net.minecraft.client.util.math.MatrixStack mv = RenderSystem.getModelViewStack();
        try {
            if (rotated) { mv.push(); mv.translate(cx, cy, 0f); mv.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Z.rotationDegrees(rot)); mv.translate(-cx, -cy, 0f); }
            if (glassTrack.boolValue && GlassProgram.ringUsable()) {
                GlassRenderer.ring(cx, cy, r + GLASS_MARGIN, w + 2f * GLASS_MARGIN, vis * (float) trackOpacity.doubleValue, shp, ratio, gap);
            }
            // the arc's direction rides on the sign of the progress (ring_arc.fsh mirrors itself for a negative one)
            if (GlassProgram.arcUsable()) GlassRenderer.arc(cx, cy, r, w, clockwise.boolValue ? progress : -progress, fill, shp, ratio, gap);
        } finally {
            RenderSystem.enableDepthTest();
            if (rotated) { mv.pop(); }
        }
    }


    /**
     * The active crosshair as {cx, cy, reach, armHalfThickness, rotationDeg, round}: the S1mp1e crosshair when it is on
     * (its bars sit at {@code c - (t+1)/2}, so the true centre is half a pixel up-left for odd thicknesses; the outline
     * adds 1 px), else vanilla's 15 px sprite at {@code (dim-15)/2}. Circle / Dot crosshairs report round = 1.
     */
    private static float[] crosshairGeom(int gw, int gh) {
        dev.s1mp1e.client.Module m = dev.s1mp1e.client.ModuleManager.byName("Crosshair");
        if (m instanceof CrosshairModule && m.enabled) {
            CrosshairModule ch = (CrosshairModule) m;
            int s = ch.size.intValue, t = ch.thick.intValue, gp = ch.gap.intValue;
            float out = ch.outline.boolValue ? 1f : 0f;
            int icx = gw / 2, icy = gh / 2;
            float ccx = icx - (t + 1) / 2 + t / 2f, ccy = icy - (t + 1) / 2 + t / 2f;
            String mode = ch.shape.modeValue;
            if ("Circle".equals(mode)) return new float[] { icx, icy, gp + s + out, 0f, 0f, 1f, 0f };
            if ("Dot".equals(mode))    return new float[] { ccx, ccy, t * 0.7072f + out, 0f, 0f, 1f, 0f };
            // element [6] = the crosshair inner gap (arms this far from centre; 0 for a joined "+")
            return new float[] { ccx, ccy, t / 2f + gp + s + out, t / 2f + out, ch.rotation.intValue, 0f, (float) gp };
        }
        return new float[] { (gw - 15) / 2 + 7.5f, (gh - 15) / 2 + 7.5f, 7.5f, 0.5f, 0f, 0f, 0f };
    }

    /** 0 circle, 1 rounded square, 2 crosshair wrap - the charging shape, or the ready shape once full. */
    private int shapeCode(boolean charging) {
        String m = shape.modeValue;
        if (!charging && !"Same".equals(readyShape.modeValue)) m = readyShape.modeValue;
        return "Wrap".equals(m) ? 2 : ("Square".equals(m) ? 1 : 0);
    }

    /** Progress colour while charging, the ready colour when full, or a flowing rainbow (keeps the colour's alpha). */
    private int colour(boolean charging) {
        int base = charging ? color.colorValue : readyColor.colorValue;
        if (!chroma.boolValue) return base;
        return Chroma.argb(Chroma.alpha(base), Chroma.hue(chromaSpeed.doubleValue, 0.0), 0.75f, 1.0f);
    }
}
