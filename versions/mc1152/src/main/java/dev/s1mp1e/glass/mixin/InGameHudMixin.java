package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.glass.anim.Spring;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import dev.s1mp1e.glass.render.ScreenFade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Glass hotbar for 1.16.5 — the Fabric counterpart of the 1.8.9/1.12.2
 * GlassHudHandler and of LiquidGlass26's HudHotbarMixin. Same measured 26.2
 * geometry: the whole bar upscaled by {@link #SCALE} about its bottom-centre,
 * a frosted strip spanning centre±91 × 22 tall, and an 18×18 selector on the
 * two-spring rig (lead 55 / trail 30, critically damped).
 *
 * <p>The backdrop is grabbed at {@code render} HEAD (world drawn, HUD not yet)
 * so the glass never samples itself; {@code renderHotbar} is cancelled and the
 * item stacks are redrawn because vanilla paints widget + items in one call.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

    private static final float SCALE = 1.15f;
    /** Lift the hotbar itself off the screen edge (bottom margin). */
    private static final int LIFT = 4;
    /** Status bars (health/armor/food/air) + XP bar lift MORE than the hotbar so they
     *  clear the 1.15x-enlarged bar with a clean gap (26.2's DECO_LIFT). */
    private static final int DECO_LIFT = LIFT + 4;

    private Spring s1mp1e$lead, s1mp1e$trail;
    private int    s1mp1e$lastSlot = -1;
    private long   s1mp1e$lastNanos;

    // Backdrop: earliest point in the HUD pass.
    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$grab(float tickDelta, CallbackInfo ci) {
        SceneCapture.grab();
    }

    /**
     * System 1 (screen open/close cross-dissolve) — DRAW half, in-world branch.
     *
     * <p>Ported byte-for-byte from the 1.16.5 {@code InGameHudMixin} render-TAIL
     * fade edit. Forge counterpart {@code GlassScreenFadeHandler#onOverlayPost(
     * RenderGameOverlayEvent.Post)} guarded by {@code type == ALL &&
     * currentScreen == null}: back in the world (no screen), draw the dissolve
     * above the HUD then snapshot the finished frame. Same guard, same order
     * ({@link ScreenFade#draw()} then {@link ScreenFade#captureFrame()}).
     *
     * <p>1.15.2 delta vs 1.16.5: {@code InGameHud.render} is {@code method_1753}
     * descriptor {@code (F)V} — no {@code MatrixStack} first arg — so the injected
     * signature drops it. {@code ScreenFade} paints in raw GL and takes no matrix,
     * so nothing is forwarded and the geometry is unchanged.
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void s1mp1e$fadeDrawHud(float tickDelta, CallbackInfo ci) {
        if (MinecraftClient.getInstance().currentScreen != null) return;
        ScreenFade.draw();
        ScreenFade.captureFrame();
    }

    // Replace the vanilla hotbar with the glass bar.
    // Lift the status bars (health/armor/food/air) + XP bar by DECO_LIFT so the whole
    // bottom HUD cluster rises with the hotbar and keeps its spacing. 1.15.2: no
    // MatrixStack on these methods; RenderSystem.pushMatrix/translatef still exist.
    @Inject(method = "renderStatusBars", at = @At("HEAD"))
    private void s1mp1e$liftStatusHead(CallbackInfo ci) {
        RenderSystem.pushMatrix();
        RenderSystem.translatef(0f, -DECO_LIFT, 0f);
    }
    @Inject(method = "renderStatusBars", at = @At("RETURN"))
    private void s1mp1e$liftStatusTail(CallbackInfo ci) {
        RenderSystem.popMatrix();
    }
    @Inject(method = "renderExperienceBar", at = @At("HEAD"))
    private void s1mp1e$liftXpHead(int x, CallbackInfo ci) {
        RenderSystem.pushMatrix();
        RenderSystem.translatef(0f, -DECO_LIFT, 0f);
    }
    @Inject(method = "renderExperienceBar", at = @At("RETURN"))
    private void s1mp1e$liftXpTail(int x, CallbackInfo ci) {
        RenderSystem.popMatrix();
    }

    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassHotbar(float tickDelta, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || !SceneCapture.hasBackdrop()) return;

        int center = mc.getWindow().getScaledWidth() / 2;
        int bottom = mc.getWindow().getScaledHeight() - LIFT;

        RenderSystem.pushMatrix();
        RenderSystem.translatef(center, bottom, 0f);
        RenderSystem.scalef(SCALE, SCALE, 1f);
        RenderSystem.translatef(-center, -bottom, 0f);
        try {
            int stripX0 = center - 91, stripY0 = bottom - 22;
            int stripX1 = center + 91, stripY1 = bottom;

            GlassRenderer.glass(stripX0, stripY0, stripX1, stripY1,
                                GlassRenderer.PAD_PILL, 1.0f, 0f, 1.0f,
                                GlassRenderer.FROST_PANEL);

            int   slot        = mc.player.inventory.selectedSlot;
            float slotCenterX = center - 80f + slot * 20f;
            long  now = System.nanoTime();
            float dt  = (s1mp1e$lastNanos == 0L) ? (1f / 60f)
                        : Math.min(0.1f, (now - s1mp1e$lastNanos) * 1e-9f);
            s1mp1e$lastNanos = now;
            if (s1mp1e$lead == null) {
                s1mp1e$lead  = new Spring(slotCenterX, Spring.OMEGA_SNAP, Spring.DAMPING);
                s1mp1e$trail = new Spring(slotCenterX, Spring.OMEGA_MED,  Spring.DAMPING);
                s1mp1e$lastSlot = slot;
            } else if (slot != s1mp1e$lastSlot) {
                s1mp1e$lead.setTarget(slotCenterX);
                s1mp1e$trail.setTarget(slotCenterX);
                s1mp1e$lastSlot = slot;
            }
            s1mp1e$lead.advance(dt);
            s1mp1e$trail.advance(dt);

            float lo = Math.min(s1mp1e$lead.value(), s1mp1e$trail.value());
            float hi = Math.max(s1mp1e$lead.value(), s1mp1e$trail.value());
            int pillX0 = Math.round(lo - 9f), pillX1 = Math.round(hi + 9f);
            int pillY0 = bottom - 20,         pillY1 = bottom - 2;
            GlassRenderer.glass(pillX0, pillY0, pillX1, pillY1,
                                6f, 1.0f, 0.12f, 1.0f, GlassRenderer.FROST_NONE);

            s1mp1e$renderItems(mc, stripX0, stripY0);
        } finally {
            RenderSystem.popMatrix();
        }
        ci.cancel();
    }

    /** Redraw the hotbar item stacks the cancelled vanilla pass owned. */
    private static void s1mp1e$renderItems(MinecraftClient mc, int x0, int y0) {
        PlayerEntity player = mc.player;
        ItemRenderer ir = mc.getItemRenderer();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.inventory.main.get(i);
            if (stack.isEmpty()) continue;
            int ix = x0 + 3 + i * 20, iy = y0 + 3;
            ir.renderGuiItem(stack, ix, iy);
            ir.renderGuiItemOverlay(mc.textRenderer, stack, ix, iy);
        }
    }
}
