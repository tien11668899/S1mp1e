package dev.s1mp1e.client.module;

import dev.s1mp1e.client.Chroma;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.ModuleManager;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * The 26.2 attack-cooldown ring, ported to 1.12.2 (Forge, MCP names). Drawn under the crosshair from a
 * {@code RenderGameOverlayEvent.Pre(CROSSHAIRS)} handler at HIGH priority (so it lands before the vanilla or S1mp1e
 * crosshair and stays under it), never cancelling the event. A liquid-glass track (glass_ring.fsh) plus a flat progress
 * arc (ring_arc.fsh) on a circle, rounded square, or a "+" that wraps the crosshair — and, when the crosshair is a
 * separated cross, wraps each arm on its own (the plus gap).
 *
 * <p>FAIR-PLAY: purely a render of the player's OWN attack cooldown and settings. The only entity read is
 * {@code pointedEntity} for the ready-shape, exactly what vanilla's own attack indicator already shows.
 */
public final class AttackRingModule extends Module {

    public final Setting radius       = add(Setting.number("Ring radius", 11.0D, 4.0D, 30.0D));
    public final Setting width        = add(Setting.number("Ring width", 2.0D, 0.5D, 8.0D));
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
    public final Setting shape        = add(Setting.mode("Shape", "Circle", "Circle", "Square", "Wrap"));
    public final Setting readyShape   = add(Setting.mode("Ready shape", "Same", "Same", "Circle", "Square", "Wrap"));
    public final Setting readyRadius  = add(Setting.number("Ready size", 0.0D, 0.0D, 30.0D));
    public final Setting readyWidth   = add(Setting.number("Ready width", 0.0D, 0.0D, 8.0D));
    public final Setting fitCrosshair = add(Setting.bool("Fit crosshair", true));
    public final Setting fitPad       = add(Setting.number("Fit padding", 1.5D, 0.0D, 6.0D));

    private static final float GLASS_MARGIN = 1.5f;

    private static AttackRingModule instance;
    private float vis;
    private float curR = -1f, curW = -1f;
    private long lastNanos;

    public AttackRingModule() {
        super("AttackRing", "Combat");
        this.enabled = true;
        instance = this;
    }

    @Override public void onEnable()  { MinecraftForge.EVENT_BUS.register(this); }
    @Override public void onDisable() { MinecraftForge.EVENT_BUS.unregister(this); }

    public static boolean active() {
        AttackRingModule m = instance;
        return m != null && m.enabled;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onRenderCrosshair(RenderGameOverlayEvent.Pre e) {
        if (!enabled || e.getType() != RenderGameOverlayEvent.ElementType.CROSSHAIRS) return;
        try {
            draw();
        } catch (Throwable t) {
            // cosmetic: a failed ring must never take the HUD down
        }
    }

    private void draw() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 1f / 60f : Math.min(0.1f, (now - lastNanos) / 1.0e9f);
        lastNanos = now;
        if (p == null || mc.gameSettings.hideGUI || mc.gameSettings.thirdPersonView != 0) { vis = 0f; return; }

        float strength = p.getCooledAttackStrength(0f);
        boolean charging = strength < 0.999f;
        boolean full = !charging && ready.boolValue && p.getCooldownPeriod() > 5.0f
                && mc.pointedEntity instanceof EntityLivingBase && ((EntityLivingBase) mc.pointedEntity).isEntityAlive();
        float target = (charging || full) ? 1f : 0f;
        float tau = target > vis ? 0.035f : 0.05f;
        vis += (target - vis) * (1f - (float) Math.exp(-dt / tau));
        if (vis < 0.01f) return;

        ScaledResolution sr = new ScaledResolution(mc);
        int gw = sr.getScaledWidth(), gh = sr.getScaledHeight();
        float cx = gw / 2f + offsetX.intValue;
        float cy = gh / 2f + offsetY.intValue;
        float tr = (float) radius.doubleValue, tw = (float) width.doubleValue;
        if (!charging) {
            if (readyRadius.doubleValue > 0.0) tr = (float) readyRadius.doubleValue;
            if (readyWidth.doubleValue > 0.0) tw = (float) readyWidth.doubleValue;
        }
        int shp = shapeCode(charging);
        float ratio = 0.45f, rot = 0f, gap = 0f;
        if (shp == 2 && fitCrosshair.boolValue) {
            float[] xh = crosshairGeom(gw, gh);
            cx = xh[0] + offsetX.intValue;
            cy = xh[1] + offsetY.intValue;
            float pad = (float) fitPad.doubleValue;
            tr = xh[2] + pad + tw;
            if (xh[5] > 0.5f) {
                shp = 0;
            } else {
                float rc = xh[2] + pad + tw / 2f;
                ratio = (xh[3] + pad + tw / 2f) / rc;
                rot = xh[4];
                // Separated crosshair -> a hollow "+" that wraps each arm on its own (0 = a joined plus).
                gap = Math.max(0f, (xh[6] - pad)) / rc;
            }
        }
        if (curR < 0f) { curR = tr; curW = tw; }
        float k = 1f - (float) Math.exp(-dt / 0.05f);
        curR += (tr - curR) * k;
        curW += (tw - curW) * k;
        float r = curR, w = curW;

        boolean rotated = rot != 0f;
        GlStateManager.disableDepth();
        if (rotated) {
            GlStateManager.pushMatrix();
            GlStateManager.translate(cx, cy, 0f);
            GlStateManager.rotate(rot, 0f, 0f, 1f);
            GlStateManager.translate(-cx, -cy, 0f);
        }
        try {
            if (glassTrack.boolValue) {
                GlassRenderer.ring(cx, cy, r + GLASS_MARGIN, w + 2f * GLASS_MARGIN, vis * (float) trackOpacity.doubleValue, shp, ratio, gap);
            }
            if (GlassProgram.arcUsable()) {
                int argb = colour(charging);
                int a = Math.round(((argb >>> 24) & 0xFF) * vis);
                int fill = (a << 24) | (argb & 0xFFFFFF);
                float progress = charging ? strength : 1f;
                GlassRenderer.arc(cx, cy, r, w, clockwise.boolValue ? progress : -progress, fill, shp, ratio, gap);
            }
        } finally {
            if (rotated) GlStateManager.popMatrix();
            GlStateManager.enableDepth();
        }
    }

    /**
     * The active crosshair as {cx, cy, reach, armHalfThickness, rotationDeg, round, innerGap}: the S1mp1e crosshair when
     * it is on, else vanilla's 15 px sprite. Circle / Dot crosshairs report round = 1; innerGap is how far a cross's
     * arms sit from the centre (0 for a joined "+").
     */
    private static float[] crosshairGeom(int gw, int gh) {
        Module m = ModuleManager.byName("Crosshair");
        if (m instanceof CrosshairModule && m.enabled) {
            CrosshairModule ch = (CrosshairModule) m;
            int s = ch.size.intValue, t = ch.thick.intValue, gp = ch.gap.intValue;
            float out = ch.outline.boolValue ? 1f : 0f;
            int icx = gw / 2, icy = gh / 2;
            float ccx = icx - (t + 1) / 2 + t / 2f, ccy = icy - (t + 1) / 2 + t / 2f;
            String mode = ch.shape.modeValue;
            if ("Circle".equals(mode)) return new float[] { icx, icy, gp + s + out, 0f, 0f, 1f, 0f };
            if ("Dot".equals(mode))    return new float[] { ccx, ccy, t * 0.7072f + out, 0f, 0f, 1f, 0f };
            return new float[] { ccx, ccy, t / 2f + gp + s + out, t / 2f + out, ch.rotation.intValue, 0f, (float) gp };
        }
        return new float[] { (gw - 15) / 2 + 7.5f, (gh - 15) / 2 + 7.5f, 7.5f, 0.5f, 0f, 0f, 0f };
    }

    private int shapeCode(boolean charging) {
        String m = shape.modeValue;
        if (!charging && !"Same".equals(readyShape.modeValue)) m = readyShape.modeValue;
        return "Wrap".equals(m) ? 2 : ("Square".equals(m) ? 1 : 0);
    }

    private int colour(boolean charging) {
        int base = charging ? color.colorValue : readyColor.colorValue;
        if (!chroma.boolValue) return base;
        return Chroma.argb(Chroma.alpha(base), Chroma.hue(chromaSpeed.doubleValue, 0.0), 0.75f, 1.0f);
    }
}
