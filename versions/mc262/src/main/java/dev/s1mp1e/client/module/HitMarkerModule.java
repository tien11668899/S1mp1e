package dev.s1mp1e.client.module;

import com.seagull.liquidglass.client.mixin.GuiGraphicsExtractorAccessor;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassRectRenderState;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassWidgets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Hit marker: a brief X of four short glass ticks around the crosshair when YOUR attack is confirmed by the server
 * (visual feedback only - nothing vanilla does not already tell you through the hurt flash / sound).
 * <ul>
 *   <li>{@link #onAttack} (Player.attack HEAD, local player): remembers the target and the time;</li>
 *   <li>{@link #onCrit} (Player.crit HEAD, local player): older versions decide crits client-side;</li>
 *   <li>{@link #onCritAnimate} (ClientPacketListener.handleAnimate, CRITICAL_HIT): in 26.2 the client never runs
 *       {@code crit()} ({@code hurtClient} is always false, so {@code attackVisualEffects} is skipped) - the server's
 *       crit animation packet for the target is the only crit signal. It arrives right after the damage event, so an
 *       already-shown marker is upgraded to the crit colour within {@link #CRIT_UPGRADE_MS};</li>
 *   <li>{@link #onDeath} (LivingEntity.handleEntityEvent 3): the target died within {@link #KILL_UPGRADE_MS} -> kill colour;</li>
 *   <li>{@link #onDamage} (LivingEntity.handleDamageEvent, client): the server reports that entity took damage - if it is
 *       the target attacked within {@link #CONFIRM_MS}, the marker fires (crit colour if the swing was a crit).</li>
 * </ul>
 * Each tick is a small liquid-glass capsule with a coloured core; it pops in slightly larger and fades out.
 * Drawn from {@code CrosshairHideMixin} at {@code Hud.extractCrosshair} HEAD, before the crosshair's {@code nextStratum()},
 * so the crosshair stays on top.
 */
public final class HitMarkerModule extends Module {
    public final Setting hitColour  = add(Setting.color("Hit colour", 0xFFFFFFFF));
    public final Setting critColour = add(Setting.color("Crit colour", 0xFFFFB340));
    public final Setting size       = add(Setting.number("Marker size", 5.0D, 3.0D, 10.0D));
    public final Setting duration   = add(Setting.integer("Marker time", 300, 150, 700));
    public final Setting glass      = add(Setting.bool("Glass ticks", true));
    public final Setting killColour = add(Setting.color("Kill colour", 0xFFFF453A));
    public final Setting shape      = add(Setting.mode("Shape", "X", "X", "Cross"));
    public final Setting gapSet     = add(Setting.number("Gap", 4.0D, 2.0D, 10.0D));
    public final Setting thickness  = add(Setting.number("Thickness", 1.4D, 0.8D, 3.0D));
    public final Setting popSet     = add(Setting.number("Pop", 0.45D, 0.0D, 1.0D));
    public final Setting critsOnly  = add(Setting.bool("Crits only", false));

    private static final long CONFIRM_MS = 600L;
    private static final long CRIT_UPGRADE_MS = 200L;
    private static final long KILL_UPGRADE_MS = 400L;
    private static final int GLASS_KNOBS = 0x80FFFF00;   // frost .5, capsule corner, no lift

    private static HitMarkerModule instance;
    private static int targetId = Integer.MIN_VALUE;
    private static long attackNanos;
    private static boolean critPending;
    private static long markerNanos;
    private static boolean markerCrit;
    private static int markerTargetId = Integer.MIN_VALUE;
    private static boolean markerKill;

    public HitMarkerModule() {
        super("HitMarker", "Combat");
        this.enabled = true;
        instance = this;
    }

    private static boolean active() {
        HitMarkerModule m = instance;
        return m != null && m.enabled;
    }

    public static void onAttack(Entity target) {
        if (!active() || target == null) return;
        targetId = target.getId();
        attackNanos = System.nanoTime();
        critPending = false;
    }

    public static void onCrit(Entity target) {
        if (!active() || target == null || target.getId() != targetId) return;
        critPending = true;
    }

    public static void onDamage(LivingEntity e) {
        if (!active() || e == null || e.getId() != targetId) return;
        long now = System.nanoTime();
        if ((now - attackNanos) / 1_000_000L > CONFIRM_MS) return;
        markerNanos = now;
        markerCrit = critPending;
        markerKill = false;
        markerTargetId = targetId;
        targetId = Integer.MIN_VALUE;   // one marker per swing
    }

    /** Server crit animation for entity {@code entityId} (26.2's only crit signal on the client). */
    public static void onCritAnimate(int entityId) {
        if (!active()) return;
        long now = System.nanoTime();
        if (entityId == markerTargetId && markerNanos != 0L && (now - markerNanos) / 1_000_000L <= CRIT_UPGRADE_MS) {
            markerCrit = true;                         // damage event came first: upgrade the shown marker
        } else if (entityId == targetId && (now - attackNanos) / 1_000_000L <= CONFIRM_MS) {
            critPending = true;                        // crit animation came first: the marker will open as a crit
        }
    }

    /** The server's death event for {@code entityId}: if it is the entity just marked, the marker turns into a kill. */
    public static void onDeath(int entityId) {
        if (!active()) return;
        if (entityId == markerTargetId && markerNanos != 0L
                && (System.nanoTime() - markerNanos) / 1_000_000L <= KILL_UPGRADE_MS) {
            markerKill = true;
        }
    }

    /** Called under the crosshair (see class doc). */
    public static void drawUnderCrosshair(GuiGraphicsExtractor g) {
        HitMarkerModule m = instance;
        if (m != null && m.enabled) m.draw(g);
    }

    private void draw(GuiGraphicsExtractor g) {
        if (markerNanos == 0L) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.hud.isHidden() || !mc.options.getCameraType().isFirstPerson()) return;
        float ageMs = (System.nanoTime() - markerNanos) / 1.0e6f;
        float dur = Math.max(50, duration.intValue);
        if (ageMs >= dur) { markerNanos = 0L; return; }

        if (critsOnly.boolValue && !markerCrit) return;
        float t = ageMs / dur;
        float alpha = 1f - t * t;                                   // hold, then fade
        float popK = Math.max(0f, 1f - ageMs / 110f);
        float pop = 1f + (float) popSet.doubleValue * popK * popK;  // starts (1+pop)x, settles in ~110 ms
        float len = (float) size.doubleValue * (markerCrit || markerKill ? 1.25f : 1f);
        float gap = (float) gapSet.doubleValue * pop;
        float coreW = (float) thickness.doubleValue;                // coloured core width
        float half = Math.max(2f, coreW / 2f + 1.3f);               // glass capsule half-height around the core
        int argb = markerKill ? killColour.colorValue : (markerCrit ? critColour.colorValue : hitColour.colorValue);
        double base = "Cross".equals(shape.modeValue) ? 0.0 : 45.0;
        int a = Math.round(((argb >>> 24) & 0xFF) * alpha);
        int core = (a << 24) | (argb & 0xFFFFFF);
        int shadow = (Math.round(0x70 * alpha) << 24);              // thin dark edge so it reads on bright skies

        float cx = (g.guiWidth() - 15) / 2 + 7.5f;
        float cy = (g.guiHeight() - 15) / 2 + 7.5f;
        for (int i = 0; i < 4; i++) {
            g.pose().pushMatrix();
            try {
                g.pose().translate(cx, cy);
                g.pose().rotate((float) Math.toRadians(base + 90.0 * i));
                if (glass.boolValue && GlassPipeline.ensureReady() && GlassPipeline.capsuleUsable()) {
                    int op = Math.round(230 * alpha) & 0xFF;
                    TextureSetup ts = TextureSetup.singleTexture(GlassPipeline.backdropView(), GlassPipeline.sampler());
                    ((GuiGraphicsExtractorAccessor) g).liquidglass$guiRenderState().addGuiElement(
                        new GlassRectRenderState(GlassPipeline.capsule(), ts, g.pose(),
                            Math.round(gap - 1f), -Math.round(half), Math.round(gap + len + 1f), Math.round(half), 6,
                            GLASS_KNOBS | op, null));
                }
                GlassWidgets.fillRound(g, gap - 0.5f, -coreW / 2f - 0.5f, gap + len + 0.5f, coreW / 2f + 0.5f, shadow, coreW / 2f + 0.5f);
                GlassWidgets.fillRound(g, gap, -coreW / 2f, gap + len, coreW / 2f, core, coreW / 2f);
            } finally {
                g.pose().popMatrix();
            }
        }
    }
}
