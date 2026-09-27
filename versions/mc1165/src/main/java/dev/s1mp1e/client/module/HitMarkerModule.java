package dev.s1mp1e.client.module;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;

/**
 * Hit marker: a brief X of four short glass ticks around the crosshair when YOUR attack is confirmed by the server
 * (visual feedback only - nothing vanilla does not already tell you through the hurt flash / sound). 1.16.5 port of the
 * 26.2 module.
 * <ul>
 *   <li>{@link #onAttack} ({@code PlayerEntity.attack} HEAD, local player): remembers the target and the time;</li>
 *   <li>{@link #onCritAnimate} ({@code ClientPlayNetworkHandler.onEntityAnimation}, {@code CRIT}): the client never
 *       decides crits itself ({@code LivingEntity.damage} returns false client-side, so {@code addCritParticles} is not
 *       reached) - the server's crit animation for the target is the crit signal. It can arrive right after the damage
 *       event, so an already-shown marker is upgraded to the crit colour within {@link #CRIT_UPGRADE_MS};</li>
 *   <li>{@link #onDamage} ({@code LivingEntity.onDamaged}, client): the server reports that entity took damage - if it
 *       is the target attacked within {@link #CONFIRM_MS}, the marker fires.</li>
 * </ul>
 * Each tick is a small liquid-glass capsule (LENS program) with a coloured core; it pops in slightly larger and fades
 * out. Drawn from {@code CrosshairMixin} at {@code InGameHud.renderCrosshair} HEAD, so the crosshair stays on top.
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
        targetId = target.getEntityId();
        attackNanos = System.nanoTime();
        critPending = false;
    }

    public static void onDamage(LivingEntity e) {
        if (!active() || e == null || e.getEntityId() != targetId) return;
        long now = System.nanoTime();
        if ((now - attackNanos) / 1_000_000L > CONFIRM_MS) return;
        markerNanos = now;
        markerCrit = critPending;
        markerKill = false;
        markerTargetId = targetId;
        targetId = Integer.MIN_VALUE;   // one marker per swing
    }

    /** Server crit animation for entity {@code entityId} (the client's only crit signal). */
    public static void onCritAnimate(int entityId) {
        if (!active()) return;
        long now = System.nanoTime();
        if (entityId == markerTargetId && markerNanos != 0L && (now - markerNanos) / 1_000_000L <= CRIT_UPGRADE_MS) {
            markerCrit = true;                         // damage event came first: upgrade the shown marker
        } else if (entityId == targetId && (now - attackNanos) / 1_000_000L <= CONFIRM_MS) {
            critPending = true;                        // crit animation came first: the marker will open as a crit
        }
    }

    /** The server's death status for {@code entityId}: if it is the entity just marked, the marker becomes a kill. */
    public static void onDeath(int entityId) {
        if (!active()) return;
        if (entityId == markerTargetId && markerNanos != 0L
                && (System.nanoTime() - markerNanos) / 1_000_000L <= KILL_UPGRADE_MS) {
            markerKill = true;
        }
    }

    /** Called under the crosshair (see class doc). */
    public static void drawUnderCrosshair(MatrixStack matrices) {
        HitMarkerModule m = instance;
        if (m != null && m.enabled) m.draw(matrices);
    }

    private void draw(MatrixStack matrices) {
        if (markerNanos == 0L) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden || !mc.options.getPerspective().isFirstPerson()) return;
        float ageMs = (System.nanoTime() - markerNanos) / 1.0e6f;
        float dur = Math.max(50, duration.intValue);
        if (ageMs >= dur) { markerNanos = 0L; return; }
        if (!GlassProgram.ensureReady()) return;

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

        int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        float cx = (sw - 15) / 2 + 7.5f;
        float cy = (sh - 15) / 2 + 7.5f;
        boolean glassOn = glass.boolValue && GlassProgram.lensUsable();
        if (glassOn && !SceneCapture.hasBackdrop()) SceneCapture.grabNow();
        RenderSystem.disableDepthTest();
        try {
            for (int i = 0; i < 4; i++) {
                RenderSystem.pushMatrix();
                try {
                    RenderSystem.translatef(cx, cy, 0f);
                    RenderSystem.rotatef((float) (base + 90.0 * i), 0f, 0f, 1f);
                    if (glassOn) GlassRenderer.lens(gap - 1f, -half, gap + len + 1f, half, 1.0f, 0f, 0.9f * alpha, 0.5f);
                    GlassRenderer.roundRect(gap - 0.5f, -coreW / 2f - 0.5f, gap + len + 0.5f, coreW / 2f + 0.5f,
                                            coreW / 2f + 0.5f, shadow);
                    GlassRenderer.roundRect(gap, -coreW / 2f, gap + len, coreW / 2f, coreW / 2f, core);
                } finally {
                    RenderSystem.popMatrix();
                }
            }
        } finally {
            RenderSystem.enableDepthTest();
        }
    }
}
