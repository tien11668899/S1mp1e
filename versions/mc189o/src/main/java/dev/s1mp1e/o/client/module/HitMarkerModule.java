package dev.s1mp1e.o.client.module;

import dev.s1mp1e.o.client.Module;
import dev.s1mp1e.o.client.Setting;
import dev.s1mp1e.o.glass.render.GlassProgram;
import dev.s1mp1e.o.glass.render.GlassRenderer;
import dev.s1mp1e.o.glass.render.SceneCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.living.player.LocalClientPlayerEntity;
import net.minecraft.client.render.Window;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.living.LivingEntity;
import net.minecraft.entity.living.effect.StatusEffect;
import dev.s1mp1e.o.event.RenderGameOverlayEvent;
import dev.s1mp1e.o.event.MinecraftForge;
import dev.s1mp1e.o.event.AttackEntityEvent;
import dev.s1mp1e.o.event.EventPriority;
import dev.s1mp1e.o.event.SubscribeEvent;
import dev.s1mp1e.o.event.TickEvent;

/**
 * Hit marker on 1.8.9 (Forge): a brief X (or cross) of four glass ticks around the crosshair when YOUR attack lands.
 * Client-only visual feedback — nothing vanilla's own hurt flash / sound does not already tell you.
 *
 * <p>1.8.9 has no attack cooldown (so no {@code AttackRing}), but a landed hit still fires this from
 * {@code AttackEntityEvent}. The crit is computed exactly as 1.8.9 vanilla does at the swing (falling, not on ground /
 * ladder / water, not blind, not riding — 1.8.9 does NOT exclude sprinting). A kill is caught on the next client tick.
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

    private static final long KILL_WINDOW_MS = 500L;

    private static HitMarkerModule instance;
    private long markerNanos;
    private boolean markerCrit;
    private boolean markerKill;
    private LivingEntity killWatch;
    private long killWatchNanos;

    public HitMarkerModule() {
        super("HitMarker", "Combat");
        this.enabled = true;
        instance = this;
    }

    @Override public void onEnable()  { MinecraftForge.EVENT_BUS.register(this); }
    @Override public void onDisable() { MinecraftForge.EVENT_BUS.unregister(this); }

    @SubscribeEvent(priority = EventPriority.LOW, receiveCanceled = true)
    public void onAttack(AttackEntityEvent e) {
        try {
            if (!enabled) return;
            Minecraft mc = Minecraft.getInstance();
            LocalClientPlayerEntity p = mc.player;
            if (p == null || e.entityPlayer != p) return;
            Entity target = e.target;
            if (!(target instanceof LivingEntity) || !((LivingEntity) target).isAlive()) return;
            boolean crit = p.fallDistance > 0.0F && !p.onGround && !p.isClimbing() && !p.isInWater()
                    && !p.hasStatusEffect(StatusEffect.BLINDNESS) && p.vehicle == null;
            markerNanos = System.nanoTime();
            markerCrit = crit;
            markerKill = false;
            killWatch = (LivingEntity) target;
            killWatchNanos = markerNanos;
        } catch (Throwable t) {
            // never-throw
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        try {
            LivingEntity w = killWatch;
            if (w == null) return;
            if ((System.nanoTime() - killWatchNanos) / 1_000_000L > KILL_WINDOW_MS) { killWatch = null; return; }
            if (w.getHealth() <= 0.0F || w.removed) {
                markerKill = true;
                killWatch = null;
            }
        } catch (Throwable t) {
            killWatch = null;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onRenderCrosshair(RenderGameOverlayEvent.Pre e) {
        if (!enabled || e.type != RenderGameOverlayEvent.ElementType.CROSSHAIRS) return;
        try {
            draw();
        } catch (Throwable t) {
            // cosmetic
        }
    }

    private void draw() {
        if (markerNanos == 0L) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.options.perspective != 0) return;
        float ageMs = (System.nanoTime() - markerNanos) / 1.0e6f;
        float dur = Math.max(50, duration.intValue);
        if (ageMs >= dur) { markerNanos = 0L; return; }
        if (!GlassProgram.ensureReady()) return;
        if (critsOnly.boolValue && !markerCrit) return;

        float t = ageMs / dur;
        float alpha = 1f - t * t;
        float popK = Math.max(0f, 1f - ageMs / 110f);
        float pop = 1f + (float) popSet.doubleValue * popK * popK;
        float len = (float) size.doubleValue * (markerCrit || markerKill ? 1.25f : 1f);
        float gap = (float) gapSet.doubleValue * pop;
        float coreW = (float) thickness.doubleValue;
        float half = Math.max(2f, coreW / 2f + 1.3f);
        int argb = markerKill ? killColour.colorValue : (markerCrit ? critColour.colorValue : hitColour.colorValue);
        double base = "Cross".equals(shape.modeValue) ? 0.0 : 45.0;
        int a = Math.round(((argb >>> 24) & 0xFF) * alpha);
        int core = (a << 24) | (argb & 0xFFFFFF);
        int shadow = (Math.round(0x70 * alpha) << 24);

        Window sr = new Window(mc);
        float cx = sr.getWidth() / 2f;
        float cy = sr.getHeight() / 2f;
        boolean glassOn = glass.boolValue && GlassProgram.lensUsable();
        if (glassOn && !SceneCapture.hasBackdrop()) SceneCapture.forceGrab();
        GlStateManager.disableDepthTest();
        try {
            for (int i = 0; i < 4; i++) {
                GlStateManager.pushMatrix();
                try {
                    GlStateManager.translatef(cx, cy, 0f);
                    GlStateManager.rotatef((float) (base + 90.0 * i), 0f, 0f, 1f);
                    if (glassOn) GlassRenderer.lens(gap - 1f, -half, gap + len + 1f, half, 1.0f, 0f, 0.9f * alpha, 0.5f);
                    GlassRenderer.roundRect(gap - 0.5f, -coreW / 2f - 0.5f, gap + len + 0.5f, coreW / 2f + 0.5f,
                                            coreW / 2f + 0.5f, shadow);
                    GlassRenderer.roundRect(gap, -coreW / 2f, gap + len, coreW / 2f, coreW / 2f, core);
                } finally {
                    GlStateManager.popMatrix();
                }
            }
        } finally {
            GlStateManager.enableDepthTest();
        }
    }
}
