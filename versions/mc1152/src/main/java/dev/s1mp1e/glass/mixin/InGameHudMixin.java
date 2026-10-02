package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.glass.anim.Spring;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import dev.s1mp1e.glass.render.ScreenFade;
import dev.s1mp1e.glass.ui.OffHandFade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.options.AttackIndicator;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
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
    /** Off-hand slot show/hide + item-swap animation (see {@link OffHandFade}). */
    private final OffHandFade s1mp1e$off = new OffHandFade();

    // Backdrop: earliest point in the HUD pass. grabNow (NOT the time-deduped grab) so the HUD glass owns a
    // fresh WORLD backdrop every frame — at a high in-world frame rate the 3ms dedup would skip the grab and
    // leave the glass sampling a stale/post-dim backdrop from a later stage, which flickers (matches the
    // 1.17.1/1.20.1 references).
    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$grab(float tickDelta, CallbackInfo ci) {
        SceneCapture.grabNow();
    }

    /**
     * System 1 (screen open/close cross-dissolve) — DRAW half, in-world branch.
     * Ported byte-for-byte from the 1.16.5 InGameHudMixin edit.
     *
     * <p>Forge counterpart {@code GlassScreenFadeHandler#onOverlayPost} guarded by
     * {@code type == ALL && currentScreen == null}: back in the world (no screen),
     * draw the dissolve above the HUD then snapshot the finished frame. Same guard,
     * same order ({@link ScreenFade#draw()} then {@link ScreenFade#captureFrame()}).
     *
     * <p>Target {@code InGameHud.render(float)} ({@code render(F)V}, class_329) at
     * {@code TAIL}. 1.15.2 predates MatrixStack, so the handler takes only tickDelta.
     * The {@code currentScreen == null} check keeps this in-world branch mutually
     * exclusive with {@code ScreenFadeMixin}'s screen branch. (A byte-identical
     * handler also lives in {@link InGameHudFadeMixin}; both are registered exactly as
     * in the 1.16.5 source — draw() is a no-op when idle and captureFrame() is
     * throttled, so the duplication is faithfully mirrored.)
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void s1mp1e$fadeDrawHud(float tickDelta, CallbackInfo ci) {
        // HUD modules first: Fabric's own 1.20.1 HudRenderCallback fires at render TAIL, and 1.15.2 has no
        // fabric-rendering-v1, so our HUD dispatch runs here. BEFORE the currentScreen early-return, so HUD
        // modules draw under the ScreenFade dissolve and also while a screen is open (as in the reference).
        dev.s1mp1e.client.HudDispatch.renderAll();
        if (MinecraftClient.getInstance().currentScreen != null) return;
        ScreenFade.draw();
        ScreenFade.captureFrame();
    }

    // Lift the status bars (health/armor/food/air) + XP bar by DECO_LIFT so the whole bottom HUD cluster
    // rises with the hotbar and keeps its spacing. Done via @WrapOperation around the SINGLE INVOKE sites in
    // render(F): the GL push/translate/pop wraps the whole call in try/finally, so the matrix stack stays
    // BALANCED even if another mod cancels renderStatusBars / renderExperienceBar (hard rule 5). 1.15.2 has
    // no MatrixStack on these methods; state goes through the fixed-function RenderSystem model-view.
    @WrapOperation(method = "render",
                   at = @At(value = "INVOKE",
                            target = "Lnet/minecraft/client/gui/hud/InGameHud;renderStatusBars()V"))
    private void s1mp1e$liftStatus(InGameHud self, Operation<Void> original) {
        RenderSystem.pushMatrix();
        RenderSystem.translatef(0f, -DECO_LIFT, 0f);
        try { original.call(self); } finally { RenderSystem.popMatrix(); }
    }
    @WrapOperation(method = "render",
                   at = @At(value = "INVOKE",
                            target = "Lnet/minecraft/client/gui/hud/InGameHud;renderExperienceBar(I)V"))
    private void s1mp1e$liftXp(InGameHud self, int x, Operation<Void> original) {
        RenderSystem.pushMatrix();
        RenderSystem.translatef(0f, -DECO_LIFT, 0f);
        try { original.call(self, x); } finally { RenderSystem.popMatrix(); }
    }

    // Move the XP LEVEL number up onto the status row. renderExperienceBar's only TextRenderer.draw calls
    // ARE the level (its 4-way outline + green centre), so wrapping them retargets exactly those five. y-4
    // turns vanilla's sh-35 into sh-39 within the lifted frame (= mc1211's sh-39-DECO_LIFT), and the ±1
    // outline survives. This MOVES the level; XpFlow never hides it.
    @WrapOperation(method = "renderExperienceBar",
                   at = @At(value = "INVOKE",
                            target = "Lnet/minecraft/client/font/TextRenderer;draw(Ljava/lang/String;FFI)I"))
    private int s1mp1e$xpLevelToStatusRow(TextRenderer font, String text, float x, float y, int color,
                                          Operation<Integer> original) {
        return original.call(font, text, x, y - 4f, color);
    }

    // Replace the vanilla hotbar with the glass bar.
    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassHotbar(float tickDelta, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        // First frame / pipeline race: try to grab a backdrop right here. GlassRenderer silently skips its
        // draw if the backdrop still isn't ready (beginState guards hasBackdrop) and the items are still
        // redrawn below — better late than never (matches the references).
        if (!SceneCapture.hasBackdrop()) SceneCapture.grabNow();
        boolean screenOpen = mc.currentScreen != null;   // suppress the shadow ring while a screen dims the bg

        int center = mc.getWindow().getScaledWidth() / 2;
        int bottom = mc.getWindow().getScaledHeight() - LIFT;

        RenderSystem.pushMatrix();
        RenderSystem.translatef(center, bottom, 0f);
        RenderSystem.scalef(SCALE, SCALE, 1f);
        RenderSystem.translatef(-center, -bottom, 0f);
        if (screenOpen) GlassProgram.setShadowScale(0f);
        try {
            int stripX0 = center - 91, stripY0 = bottom - 22;
            int stripX1 = center + 91, stripY1 = bottom;

            GlassRenderer.glass(stripX0, stripY0, stripX1, stripY1,
                                GlassRenderer.PAD_PILL, 1.0f, 0f, 1.0f,
                                GlassRenderer.FROST_PANEL);

            // Off-hand slot. Vanilla InGameHud.renderHotbar draws the off-hand slot (box + item);
            // because we cancel renderHotbar wholesale (to place items inside the scaled glass bar)
            // it was being dropped. Restored here as one extra glass slot beside the bar, positioned
            // on the side opposite the main arm exactly as vanilla does. Per the off-hand spec the
            // slot uses the SAME material / shape / rounding as the hotbar strip (FROST_PANEL,
            // PAD_PILL, corner 1.0) — NOT a square box — and it fades with the off hand:
            //   * empty <-> filled : the whole slot (glass + item) fades in / out (~150 ms);
            //   * both hands full, off-hand item swapped : the slot stays and only the icons cross.
            // OffHandFade owns that state; here we just draw the glass at its animated opacity.
            ItemStack offhand = mc.player.getOffHandStack();
            Arm       offArm  = mc.player.getMainArm().getOpposite();   // off hand opposite the main arm
            boolean   offLeft = offArm == Arm.LEFT;
            int offBoxX0, offBoxX1;
            if (offLeft) { offBoxX1 = stripX0 - 4; offBoxX0 = offBoxX1 - 22; }
            else         { offBoxX0 = stripX1 + 4; offBoxX1 = offBoxX0 + 22; }
            s1mp1e$off.frame(offhand);
            if (s1mp1e$off.slotVisible()) {
                GlassRenderer.glass(offBoxX0, stripY0, offBoxX1, stripY1,
                                    GlassRenderer.PAD_PILL, 1.0f, 0f, s1mp1e$off.slotAlpha(),
                                    GlassRenderer.FROST_PANEL);
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

            s1mp1e$renderItems(mc, stripX0, stripY0, offBoxX0);

            // Attack-cooldown indicator ("Above hotbar" mode only). Vanilla InGameHud.renderHotbar
            // draws it beside the bar; cancelling renderHotbar dropped it, so it is restored here on
            // the side OPPOSITE the off hand, mid-cooldown only. Not shown with the default
            // "crosshair" setting. Reads only the player's OWN attack cooldown, never target info.
            // Drawn after the item pass, when DiffuseLighting/GL_LIGHTING is off (2D blit).
            if (mc.options.attackIndicator == AttackIndicator.HOTBAR) {
                float cd = mc.player.getAttackCooldownProgress(0f);
                if (cd < 1.0f) {
                    int ay = bottom - 20;
                    int ax = offLeft ? (stripX1 + 6) : (stripX0 - 22);
                    int p  = (int) (cd * 19.0f);
                    RenderSystem.color4f(1f, 1f, 1f, 1f);
                    RenderSystem.enableBlend();
                    mc.getTextureManager().bindTexture(DrawableHelper.GUI_ICONS_LOCATION);
                    // InGameHud extends DrawableHelper; blit(...) is public there. Cast instead of
                    // @Shadow so the annotation processor need not resolve an inherited target.
                    DrawableHelper self = (DrawableHelper) (Object) this;
                    self.blit(ax, ay, 0, 94, 18, 18);
                    self.blit(ax, ay + 18 - p, 18, 112 - p, 18, p);
                }
            }
        } finally {
            if (screenOpen) GlassProgram.setShadowScale(1f);
            RenderSystem.popMatrix();
        }
        ci.cancel();
    }

    /** Redraw the hotbar item stacks the cancelled vanilla pass owned (main slots + off hand). */
    private void s1mp1e$renderItems(MinecraftClient mc, int x0, int y0, int offBoxX0) {
        PlayerEntity player = mc.player;
        ItemRenderer ir = mc.getItemRenderer();
        // 1.15.2: vanilla renderHotbar only brackets the item loop with enable/disableRescaleNormal - the item renderer
        // sets the GUI lights per model itself (javap: no DiffuseLighting call in renderHotbar).
        RenderSystem.enableRescaleNormal();
        try {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = player.inventory.main.get(i);
                if (stack.isEmpty()) continue;
                int ix = x0 + 3 + i * 20, iy = y0 + 3;
                ir.renderGuiItem(stack, ix, iy);
                ir.renderGuiItemOverlay(mc.textRenderer, stack, ix, iy);
            }
            // Off-hand item(s), centred in the glass slot (3px inset, same as the main slots). The
            // OffHandFade plan gives up to two stacks with 0..1 centre-anchored scales: one for a
            // plain fade in/out, two while a both-hands-full swap cross-transitions the icons.
            int oix = offBoxX0 + 3, oiy = y0 + 3;
            s1mp1e$drawOffItem(ir, mc, s1mp1e$off.primaryStack(),   oix, oiy, s1mp1e$off.primaryScale());
            if (s1mp1e$off.crossing()) {
                s1mp1e$drawOffItem(ir, mc, s1mp1e$off.secondaryStack(), oix, oiy, s1mp1e$off.secondaryScale());
            }
        } finally {
            RenderSystem.disableRescaleNormal();
        }
    }

    /** Draw one off-hand item at {@code scale} about the 16px cell centre (the fixed-function
     *  scale/opacity stand-in for a fade). The count/durability overlay is drawn only when the
     *  item is settled at full size and not mid-swap, so it never scales or double-draws. */
    private void s1mp1e$drawOffItem(ItemRenderer ir, MinecraftClient mc,
                                    ItemStack stack, int ix, int iy, float scale) {
        if (stack == null || stack.isEmpty() || scale <= 0.02f) return;
        float cx = ix + 8f, cy = iy + 8f;
        RenderSystem.pushMatrix();
        RenderSystem.translatef(cx, cy, 0f);
        RenderSystem.scalef(scale, scale, 1f);
        RenderSystem.translatef(-cx, -cy, 0f);
        try {
            ir.renderGuiItem(stack, ix, iy);
            if (scale >= 0.999f && !s1mp1e$off.crossing()) {
                ir.renderGuiItemOverlay(mc.textRenderer, stack, ix, iy);
            }
        } finally {
            RenderSystem.popMatrix();
        }
    }
}
