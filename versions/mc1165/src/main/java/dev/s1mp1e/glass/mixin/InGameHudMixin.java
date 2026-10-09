package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.glass.anim.Spring;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.HudLayout;
import dev.s1mp1e.glass.render.SceneCapture;
import dev.s1mp1e.glass.render.ScreenFade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.AttackIndicator;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Glass hotbar for 1.16.5 — the Fabric counterpart of the 1.8.9/1.12.2
 * GlassHudHandler and of LiquidGlass26's HudHotbarMixin. Same measured 26.2
 * geometry: the whole bar upscaled by {@link HudLayout#SCALE} about its
 * bottom-centre, a frosted strip spanning centre±91 × 22 tall, and an 18×18
 * selector on the two-spring rig (lead 55 / trail 30, critically damped).
 *
 * <p>The backdrop is grabbed at {@code render} HEAD (world drawn, HUD not yet) so
 * the glass never samples itself; {@code renderHotbar} is cancelled and the item
 * stacks are redrawn because vanilla paints widget + items in one call.
 *
 * <p><b>Under open screens.</b> The glass hotbar is kept under NON-container screens
 * (chat, pause, the S1mp1e config, the HUD editor): at {@code render} HEAD the world
 * is grabbed unless a {@link HandledScreen} is open (a container grabs its own dimmed
 * backdrop later), and the bar draws with its drop shadow suppressed while a screen
 * dims the background. Under a {@link HandledScreen} the vanilla (hidden) HUD hotbar
 * draws instead, because the only backdrop then is the dimmed container grab.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

    private Spring s1mp1e$lead, s1mp1e$trail;
    private int    s1mp1e$lastSlot = -1;
    private long   s1mp1e$lastNanos;

    /** Depth of unbalanced DECO_LIFT pushes, so a mod that cancels renderStatusBars /
     *  renderExperienceBar can't leave the GL matrix stack unbalanced (S8P2). */
    @Unique private int s1mp1e$decoDepth;

    // Backdrop: earliest point in the HUD pass. At InGameHud.render HEAD the framebuffer holds ONLY the
    // world (the HUD is not drawn yet, and a container screen applies its dim later, in Screen.render), so a
    // forced grabNow() here captures the clean world for the glass hotbar even while a container is open —
    // giving the under-screen hotbar the exact same backdrop it has in-world (S9P1). This grab never
    // interferes with the container panels: they each call SceneCapture.grabNow() themselves right before
    // drawing (ContainerGlass/CreativeGlassMixin), which ignores the de-dup clock and re-copies the dimmed
    // world+panel backdrop they need — so there is no dedup collision and no flicker at uncapped fps (R4).
    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$grab(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        SceneCapture.grabNow();
    }

    /**
     * System 1 (screen open/close cross-dissolve) — DRAW half, in-world branch —
     * plus the DECO_LIFT stack-balance cleanup (S8P2).
     *
     * <p>Forge counterpart {@code GlassScreenFadeHandler#onOverlayPost(
     * RenderGameOverlayEvent.Post)} guarded by {@code type == ALL &&
     * currentScreen == null}: back in the world (no screen), draw the dissolve above
     * the HUD then snapshot the finished frame. Same guard, same order
     * ({@link ScreenFade#draw()} then {@link ScreenFade#captureFrame()}) here.
     *
     * <p>Before the fade, any DECO_LIFT push a cancelled renderStatusBars /
     * renderExperienceBar left on the GL matrix stack is popped, so the stack is
     * balanced for the rest of the frame (and the fade draws un-shifted).
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void s1mp1e$fadeDrawHud(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        while (s1mp1e$decoDepth > 0) { RenderSystem.popMatrix(); s1mp1e$decoDepth--; }
        if (MinecraftClient.getInstance().currentScreen != null) return;
        ScreenFade.draw();
        ScreenFade.captureFrame();
    }

    // Lift the status bars (health/armor/food/air) + XP bar by DECO_LIFT so the whole
    // bottom HUD cluster rises with the hotbar and keeps its spacing. On 1.16.5 the GL
    // fixed-function modelview (RenderSystem.pushMatrix/translatef) reaches the
    // DrawableHelper/TextRenderer draws these methods use, so this lifts them. The push
    // is depth-counted so a cancelled body can't unbalance the stack (cleaned in render TAIL).
    @Inject(method = "renderStatusBars", at = @At("HEAD"))
    private void s1mp1e$liftStatusHead(MatrixStack m, CallbackInfo ci) {
        RenderSystem.pushMatrix();
        RenderSystem.translatef(0f, -HudLayout.DECO_LIFT, 0f);
        s1mp1e$decoDepth++;
    }
    @Inject(method = "renderStatusBars", at = @At("RETURN"))
    private void s1mp1e$liftStatusTail(MatrixStack m, CallbackInfo ci) {
        if (s1mp1e$decoDepth > 0) { RenderSystem.popMatrix(); s1mp1e$decoDepth--; }
    }
    @Inject(method = "renderExperienceBar", at = @At("HEAD"))
    private void s1mp1e$liftXpHead(MatrixStack m, int x, CallbackInfo ci) {
        RenderSystem.pushMatrix();
        RenderSystem.translatef(0f, -HudLayout.DECO_LIFT, 0f);
        s1mp1e$decoDepth++;
    }
    @Inject(method = "renderExperienceBar", at = @At("RETURN"))
    private void s1mp1e$liftXpTail(MatrixStack m, int x, CallbackInfo ci) {
        if (s1mp1e$decoDepth > 0) { RenderSystem.popMatrix(); s1mp1e$decoDepth--; }
    }
    // #17: lift the horse-jump bar + mount-health hearts the same DECO_LIFT so they rise with the cluster instead of
    // sitting pressed onto the raised hotbar. ContextualBarGlassMixin draws the glass jump bar at y-DECO_LIFT to match.
    @Inject(method = "renderMountJumpBar", at = @At("HEAD"))
    private void s1mp1e$liftJumpHead(MatrixStack m, int x, CallbackInfo ci) {
        RenderSystem.pushMatrix();
        RenderSystem.translatef(0f, -HudLayout.DECO_LIFT, 0f);
        s1mp1e$decoDepth++;
    }
    @Inject(method = "renderMountJumpBar", at = @At("RETURN"))
    private void s1mp1e$liftJumpTail(MatrixStack m, int x, CallbackInfo ci) {
        if (s1mp1e$decoDepth > 0) { RenderSystem.popMatrix(); s1mp1e$decoDepth--; }
    }
    @Inject(method = "renderMountHealth", at = @At("HEAD"))
    private void s1mp1e$liftMountHealthHead(MatrixStack m, CallbackInfo ci) {
        RenderSystem.pushMatrix();
        RenderSystem.translatef(0f, -HudLayout.DECO_LIFT, 0f);
        s1mp1e$decoDepth++;
    }
    @Inject(method = "renderMountHealth", at = @At("RETURN"))
    private void s1mp1e$liftMountHealthTail(MatrixStack m, CallbackInfo ci) {
        if (s1mp1e$decoDepth > 0) { RenderSystem.popMatrix(); s1mp1e$decoDepth--; }
    }

    // Move the XP LEVEL number ("30") from vanilla's y = scaledHeight-35 up to the
    // health/food status row. renderExperienceBar draws it with 5 TextRenderer.draw
    // calls (4-way black outline + green centre); wrapping that INVOKE with no ordinal
    // retargets exactly those five, shifting each y by -4 (sh-35 -> sh-39) while keeping
    // the ±1 outline offsets. The surrounding matrix is already lifted by DECO_LIFT, so
    // this lands on the lifted status-bar row, centred in the health↔food gap.
    @WrapOperation(method = "renderExperienceBar",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/font/TextRenderer;draw(Lnet/minecraft/client/util/math/MatrixStack;Ljava/lang/String;FFI)I"))
    private int s1mp1e$xpLevelToStatusRow(TextRenderer tr, MatrixStack m, String s, float x, float y, int c,
                                          Operation<Integer> original) {
        return original.call(tr, m, s, x, y - 4f, c);
    }

    // Replace the vanilla hotbar with the glass bar.
    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassHotbar(float tickDelta, MatrixStack matrices, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || !SceneCapture.hasBackdrop()) return;
        // The glass hotbar is kept under EVERY screen, containers included (S9P1): renderHotbar runs during
        // the HUD pass (before Screen.render dims/draws the container), so the backdrop here is the clean
        // world grabbed at render HEAD — the under-screen hotbar refracts exactly what it does in-world,
        // never reverting to the vanilla texture. The drop shadow is suppressed while any screen dims the
        // background (screenOpen), and the container panels take their OWN fresh grabNow() afterwards, so the
        // shared backdrop does not collide (no dedup flicker, R4).
        boolean screenOpen = mc.currentScreen != null;

        int center = mc.getWindow().getScaledWidth() / 2;
        int bottom = mc.getWindow().getScaledHeight() - HudLayout.LIFT;

        RenderSystem.pushMatrix();
        RenderSystem.translatef(center, bottom, 0f);
        RenderSystem.scalef(HudLayout.SCALE, HudLayout.SCALE, 1f);
        RenderSystem.translatef(-center, -bottom, 0f);
        if (screenOpen) GlassProgram.setShadowScale(0f);   // no drop-shadow ring under a dimming screen
        try {
            int stripX0 = center - 91, stripY0 = bottom - 22;
            int stripX1 = center + 91, stripY1 = bottom;

            // Off-hand slot + attack-cooldown indicator. Vanilla InGameHud.renderHotbar draws BOTH;
            // because we cancel renderHotbar wholesale (to place items inside the scaled glass bar)
            // they were being dropped. Restored here, positioned/scaled to the glass bar so they
            // travel with it, and gated exactly as vanilla: the box+item only when the off hand holds
            // something, the indicator only in "Above hotbar" mode while a swing is on cooldown. The
            // indicator reads only the player's OWN attack cooldown (getAttackCooldownProgress) — the
            // same own-state read vanilla makes for its own HUD, never any target/hit information.
            PlayerEntity player = mc.player;
            ItemStack offhand = player.getOffHandStack();
            boolean hasOff = !offhand.isEmpty();
            boolean offLeft = player.getMainArm().getOpposite() == Arm.LEFT;  // off hand opposite main arm
            int offBoxX0, offBoxX1;
            if (offLeft) { offBoxX1 = stripX0 - 4; offBoxX0 = offBoxX1 - 22; }
            else         { offBoxX0 = stripX1 + 4; offBoxX1 = offBoxX0 + 22; }

            GlassRenderer.glass(stripX0, stripY0, stripX1, stripY1,
                                GlassRenderer.PAD_PILL, 1.0f, 0f, 1.0f,
                                GlassRenderer.FROST_PANEL);

            // Off-hand slot: one frosted glass square beside the bar (matching the strip height so it
            // reads as one extra slot), drawn only when the off hand holds something — exactly as vanilla.
            if (hasOff) {
                GlassRenderer.glass(offBoxX0, stripY0, offBoxX1, stripY1,
                                    GlassRenderer.PAD_PILL, 0.4f, 0f, 1.0f, GlassRenderer.FROST_PANEL);
            }

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

            s1mp1e$renderItems(mc, stripX0, stripY0, offhand, hasOff, offBoxX0);

            // Attack-cooldown indicator ("Above hotbar" mode only), drawn from the vanilla icons
            // atlas exactly as InGameHud.renderHotbar does, on the side opposite the off hand and
            // mid-cooldown only (cd < 1). Not shown with the default "crosshair" setting. Under the
            // same RS model-view scale as the bar, so it rides with the scaled hotbar.
            if (mc.options.attackIndicator == AttackIndicator.HOTBAR) {
                float cd = mc.player.getAttackCooldownProgress(0f);
                if (cd < 1.0f) {
                    int ay = bottom - 20;
                    int ax = offLeft ? (stripX1 + 6) : (stripX0 - 22);
                    int p  = (int) (cd * 19.0f);
                    mc.getTextureManager().bindTexture(new Identifier("textures/gui/icons.png"));
                    RenderSystem.color4f(1f, 1f, 1f, 1f);
                    DrawableHelper.drawTexture(matrices, ax, ay,          0f, 94f,       18, 18, 256, 256);
                    DrawableHelper.drawTexture(matrices, ax, ay + 18 - p, 18f, 112f - p, 18, p,  256, 256);
                }
            }
        } finally {
            if (screenOpen) GlassProgram.setShadowScale(1f);
            RenderSystem.popMatrix();
        }
        ci.cancel();
    }

    /** Redraw the hotbar item stacks the cancelled vanilla pass owned, plus the off-hand item. */
    private static void s1mp1e$renderItems(MinecraftClient mc, int x0, int y0,
                                           ItemStack offhand, boolean hasOff, int offBoxX0) {
        PlayerEntity player = mc.player;
        ItemRenderer ir = mc.getItemRenderer();
        DiffuseLighting.enableGuiDepthLighting();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.inventory.main.get(i);
            if (stack.isEmpty()) continue;
            int ix = x0 + 3 + i * 20, iy = y0 + 3;
            ir.renderInGuiWithOverrides(player, stack, ix, iy);
            ir.renderGuiItemOverlay(mc.textRenderer, stack, ix, iy);
        }
        // Off-hand item, centred in its glass box (3px inset, same as the main slots).
        if (hasOff) {
            int oix = offBoxX0 + 3, oiy = y0 + 3;
            ir.renderInGuiWithOverrides(player, offhand, oix, oiy);
            ir.renderGuiItemOverlay(mc.textRenderer, offhand, oix, oiy);
        }
        DiffuseLighting.disableGuiDepthLighting();
    }
}
