package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.glass.anim.Spring;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.GuiFlush;
import dev.s1mp1e.glass.render.GuiItems;
import dev.s1mp1e.glass.render.HudLayout;
import dev.s1mp1e.glass.render.SceneCapture;
import dev.s1mp1e.glass.render.ScreenFade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.option.AttackIndicator;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.GameRenderer;
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
 * Glass hotbar for 1.18.2 — the Fabric counterpart of the 1.8.9/1.12.2 GlassHudHandler and of LiquidGlass26's
 * HudHotbarMixin. Same measured 26.2 geometry: the whole bar upscaled by {@link HudLayout#SCALE} about its
 * bottom-centre, a frosted strip spanning centre±91 × 22 tall, and an 18×18 selector on the two-spring rig
 * (lead 55 / trail 30, critically damped).
 *
 * <p>The backdrop is grabbed at {@code render} HEAD (world drawn, HUD not yet) so the glass never samples itself;
 * {@code renderHotbar} is cancelled and the item stacks are redrawn because vanilla paints widget + items in one
 * call.
 *
 * <p><b>S7 reconcile (Tier-A fixes verified against the shipped 1.19.2 jar + user report):</b>
 * <ul>
 *   <li>Grid constants come from {@link HudLayout} (SCALE 1.15, LIFT 4, DECO_LIFT 8) so the XP-flow and hunger
 *       glint modules can never drift from the mixin.</li>
 *   <li>Backdrop grab is {@code grabNow()} (not the 3 ms-deduped {@code grab()}), so the HUD glass owns a fresh
 *       WORLD backdrop every frame instead of a stale/post-dim one.</li>
 *   <li>The status-bar / XP-bar lift is applied to the PASSED {@link MatrixStack} (the same one the vanilla blits
 *       read on 1.18.2), guarded by a per-method pushed flag, and drained at {@code render} TAIL so a cancel by
 *       another mod can never unbalance the stack. The old {@code RenderSystem.getModelViewStack()} lift is
 *       gone.</li>
 *   <li>The XP LEVEL number is nudged onto the lifted health/food row with a conflict-tolerant
 *       {@link WrapOperation} (y-4f), which keeps the ±1 outline offsets.</li>
 *   <li>The hotbar grabs a backdrop if one is missing rather than bailing, suppresses the glass drop-shadow while
 *       a screen dims the background ({@link GlassProgram#setShadowScale}), flushes batched HUD draws
 *       ({@link GuiFlush}) around the raw-GL glass, keeps a SINGLE RenderSystem model-view scale for both the
 *       glass and the items (1.18.2 item models honour it), and draws the stacks through {@link GuiItems#drawStack}
 *       so the count text is scaled with the bar and not lost to ImmediatelyFast's overlay batch.</li>
 * </ul>
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

    private static final float SCALE = HudLayout.SCALE;
    /** Lift the hotbar itself off the screen edge (bottom margin). */
    private static final int LIFT = HudLayout.LIFT;
    /** The status bars (health/armor/food/air) + XP bar lift MORE than the hotbar so they clear the
     *  1.15x-enlarged bar with a clean gap (26.2's DECO_LIFT). */
    private static final int DECO_LIFT = HudLayout.DECO_LIFT;
    /** Vanilla GUI icons atlas — source of the attack-cooldown indicator sprites. */
    private static final Identifier ATTACK_ICONS = new Identifier("textures/gui/icons.png");

    private Spring s1mp1e$lead, s1mp1e$trail;
    private int    s1mp1e$lastSlot = -1;
    private long   s1mp1e$lastNanos;

    /** True while our status-bar lift push is outstanding on the passed MatrixStack. */
    @Unique private boolean s1mp1e$statusPushed;
    /** True while our XP-bar lift push is outstanding on the passed MatrixStack. */
    @Unique private boolean s1mp1e$xpPushed;
    /** True while the horse-jump-bar / mount-health lift pushes are outstanding (all-glass #17). */
    @Unique private boolean s1mp1e$jumpPushed;
    @Unique private boolean s1mp1e$mountHpPushed;

    // Backdrop: earliest point in the HUD pass. grabNow (NOT the time-deduped grab) so the HUD glass owns
    // a fresh WORLD backdrop every frame — at the high frame rate of an in-world HUD the 3ms dedup would
    // skip the grab and leave the glass sampling a stale/post-dim backdrop from a later stage → flicker.
    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$grab(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        SceneCapture.grabNow();
    }

    /**
     * System 1 (screen open/close cross-dissolve) — DRAW half, in-world branch — plus the lift-drain guard.
     *
     * <p>First it drains any status/XP lift push that a mid-method cancel by another mod left outstanding, so
     * the passed MatrixStack can never leak a push across frames. Then, back in the world (no screen), it draws
     * the dissolve above the HUD and snapshots the finished frame — the same guard/order as the Forge
     * {@code GlassScreenFadeHandler#onOverlayPost} handler (both {@link ScreenFade#draw()} then
     * {@link ScreenFade#captureFrame()}).
     *
     * <p><b>1.18.2 core-profile status: TRIGGER wired, DRAW is a no-op.</b> {@link ScreenFade} is STUBBED on the
     * OpenGL 3.2 core profile, so both calls do nothing and a screen change is a hard cut (parity with every
     * Fabric reference). The drain runs regardless of screen state.
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void s1mp1e$fadeDrawHud(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        // Drain any lift a cancel left pushed on the shared MatrixStack (render passes the same instance down).
        if (s1mp1e$statusPushed) { matrices.pop(); s1mp1e$statusPushed = false; }
        if (s1mp1e$xpPushed)     { matrices.pop(); s1mp1e$xpPushed = false; }
        if (s1mp1e$jumpPushed)   { matrices.pop(); s1mp1e$jumpPushed = false; }
        if (s1mp1e$mountHpPushed){ matrices.pop(); s1mp1e$mountHpPushed = false; }
        if (MinecraftClient.getInstance().currentScreen != null) return;
        ScreenFade.draw();
        ScreenFade.captureFrame();
    }

    // Lift the status bars (health/armor/food/air) + XP bar by DECO_LIFT so the whole bottom HUD cluster rises
    // with the hotbar and keeps its spacing. 1.18.2: the vanilla blits read the PASSED MatrixStack (not the
    // RenderSystem model-view), and it is per-frame, so pushing/translating it is the form that both survives
    // ImmediatelyFast batching and cannot accumulate across frames. A per-method flag lets RETURN pop only what
    // HEAD pushed, and render TAIL drains a leftover if a cancel skipped RETURN.
    @Inject(method = "renderStatusBars", at = @At("HEAD"))
    private void s1mp1e$liftStatusHead(MatrixStack m, CallbackInfo ci) {
        m.push();
        m.translate(0f, -DECO_LIFT, 0f);
        s1mp1e$statusPushed = true;
    }
    @Inject(method = "renderStatusBars", at = @At("RETURN"))
    private void s1mp1e$liftStatusTail(MatrixStack m, CallbackInfo ci) {
        if (s1mp1e$statusPushed) { m.pop(); s1mp1e$statusPushed = false; }
    }
    @Inject(method = "renderExperienceBar", at = @At("HEAD"))
    private void s1mp1e$liftXpHead(MatrixStack m, int x, CallbackInfo ci) {
        m.push();
        m.translate(0f, -DECO_LIFT, 0f);
        s1mp1e$xpPushed = true;
    }
    @Inject(method = "renderExperienceBar", at = @At("RETURN"))
    private void s1mp1e$liftXpTail(MatrixStack m, int x, CallbackInfo ci) {
        if (s1mp1e$xpPushed) { m.pop(); s1mp1e$xpPushed = false; }
    }

    // The horse jump bar replaces the XP bar in the same slot; lift it the same amount so it clears the enlarged glass
    // hotbar once both are glass (all-glass #17).
    @Inject(method = "renderMountJumpBar", at = @At("HEAD"))
    private void s1mp1e$liftJumpHead(MatrixStack m, int x, CallbackInfo ci) {
        m.push();
        m.translate(0f, -DECO_LIFT, 0f);
        s1mp1e$jumpPushed = true;
    }
    @Inject(method = "renderMountJumpBar", at = @At("RETURN"))
    private void s1mp1e$liftJumpTail(MatrixStack m, int x, CallbackInfo ci) {
        if (s1mp1e$jumpPushed) { m.pop(); s1mp1e$jumpPushed = false; }
    }
    // Mount health takes the food row's place: lift it with the status bars so its bottom row lines up with the
    // player's (lifted) hearts instead of sitting on the XP / jump bar.
    @Inject(method = "renderMountHealth", at = @At("HEAD"))
    private void s1mp1e$liftMountHealthHead(MatrixStack m, CallbackInfo ci) {
        m.push();
        m.translate(0f, -DECO_LIFT, 0f);
        s1mp1e$mountHpPushed = true;
    }
    @Inject(method = "renderMountHealth", at = @At("RETURN"))
    private void s1mp1e$liftMountHealthTail(MatrixStack m, CallbackInfo ci) {
        if (s1mp1e$mountHpPushed) { m.pop(); s1mp1e$mountHpPushed = false; }
    }

    // The XP LEVEL number ("30") is drawn INSIDE renderExperienceBar on 1.18.2 (no separate level method as in
    // 1.21), 5× via TextRenderer.draw(MatrixStack,String,FFI) — the 4-way ±1 outline plus the green centre.
    // Vanilla puts it at scaledHeight-35, just above the bar; nudging every one of those draws by y-4f lands it
    // on the health/food row (h-39), and since the surrounding matrix is already lifted by DECO_LIFT it sits on
    // the lifted row, centred in the gap between the health bar (left) and food bar (right). @WrapOperation
    // (MixinExtras) is conflict-tolerant, unlike @Redirect (the 26.2 lesson), so it coexists with other mods'
    // hooks on renderExperienceBar.
    @WrapOperation(method = "renderExperienceBar",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/font/TextRenderer;draw(Lnet/minecraft/client/util/math/MatrixStack;Ljava/lang/String;FFI)I"))
    private int s1mp1e$xpLevelToStatusRow(TextRenderer instance, MatrixStack matrices, String text,
                                          float x, float y, int color, Operation<Integer> original) {
        return original.call(instance, matrices, text, x, y - 4f, color);
    }

    // Replace the vanilla hotbar with the glass bar (scaled by SCALE about bottom-centre) AND redraw the item
    // stacks so they sit INSIDE our scaled frame. One RenderSystem model-view scale covers the glass AND the
    // items: on 1.18.2 the GUI item model reads the RS model-view, so a single scale sizes both, and the count
    // text (drawn by GuiItems.drawStack into its own flushed immediate) picks up the same scale.
    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassHotbar(float tickDelta, MatrixStack matrices, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        // No backdrop yet (first frame / pipeline race): TRY to grab right here rather than bail — better late
        // than never. GlassRenderer silently skips its draw if the backdrop still isn't ready.
        if (!SceneCapture.hasBackdrop()) SceneCapture.grabNow();

        int center = mc.getWindow().getScaledWidth() / 2;
        int bottom = mc.getWindow().getScaledHeight() - LIFT;
        int stripX0 = center - 91, stripY0 = bottom - 22;
        int stripX1 = center + 91, stripY1 = bottom;

        // Off-hand slot + attack-cooldown indicator. Vanilla renderHotbar draws BOTH; because we cancel
        // renderHotbar wholesale (to place the item stacks inside the scaled glass bar) they were being
        // dropped on 1.18.2. Restored here, positioned/scaled to the glass bar so they travel with it and
        // gated exactly as vanilla — the box+item only when the off hand holds something, the indicator only
        // in "Above hotbar" mode while a swing is on cooldown. The indicator reads ONLY the player's own
        // attack cooldown (getAttackCooldownProgress) — the same own-state read vanilla makes for its own
        // HUD, never any target/hit information.
        PlayerEntity player = mc.player;
        ItemStack offhand = player.getOffHandStack();
        boolean hasOff = !offhand.isEmpty();
        boolean offLeft = player.getMainArm().getOpposite() == Arm.LEFT;   // off hand sits opposite the main arm
        int offBoxX0, offBoxX1;
        if (offLeft) { offBoxX1 = stripX0 - 4; offBoxX0 = offBoxX1 - 22; }
        else         { offBoxX0 = stripX1 + 4; offBoxX1 = offBoxX0 + 22; }

        // Single RS model-view scale about the bar's bottom-centre — the glass reads it, and so do the item
        // models and count text below.
        MatrixStack mv = RenderSystem.getModelViewStack();
        mv.push();
        mv.translate(center, bottom, 0);
        mv.scale(SCALE, SCALE, 1f);
        mv.translate(-center, -bottom, 0);
        RenderSystem.applyModelViewMatrix();

        boolean screenOpen = mc.currentScreen != null;   // suppress the shadow ring while a screen dims the bg
        if (screenOpen) GlassProgram.setShadowScale(0f);
        try {
            GuiFlush.flush();   // flush pending / ImmediatelyFast-batched HUD draws before the raw-GL glass
            GlassRenderer.glass(stripX0, stripY0, stripX1, stripY1,
                                GlassRenderer.PAD_PILL, 1.0f, 0f, 1.0f, GlassRenderer.FROST_PANEL);

            // Off-hand slot: a single frosted glass square beside the bar (same height as the strip so it
            // reads as one extra slot), only when the off hand holds an item — exactly vanilla's gate.
            if (hasOff) {
                GlassRenderer.glass(offBoxX0, stripY0, offBoxX1, stripY1,
                                    GlassRenderer.PAD_PILL, 0.4f, 0f, 1.0f, GlassRenderer.FROST_PANEL);
            }

            int   slot        = mc.player.getInventory().selectedSlot;
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

            // Items: single RS scale carries the model AND the count (bakedTranslateScale null → no double).
            for (int i = 0; i < 9; i++) {
                ItemStack stack = player.getInventory().main.get(i);
                if (stack.isEmpty()) continue;
                int ix = stripX0 + 3 + i * 20, iy = stripY0 + 3;
                GuiItems.drawStack(player, stack, ix, iy, null);
            }
            // Off-hand item, centred in its glass box (3px inset, same as the main slots).
            if (hasOff) {
                GuiItems.drawStack(player, offhand, offBoxX0 + 3, stripY0 + 3, null);
            }
            GuiFlush.flush();                          // flush items before the RS model-view is popped

            // Attack-cooldown indicator ("Above hotbar" mode only), from the vanilla icons atlas exactly as
            // InGameHud.renderHotbar does: on the side opposite the off hand, mid-cooldown only (cd < 1). Not
            // shown with the default "crosshair" setting. Own cooldown only — never a target/hit read. Drawn
            // under the same RS model-view scale, so it travels with the bar like the glass and items.
            if (mc.options.attackIndicator == AttackIndicator.HOTBAR) {
                float cd = mc.player.getAttackCooldownProgress(0f);
                if (cd < 1.0f) {
                    int ay = bottom - 20;
                    int ax = offLeft ? (stripX1 + 6) : (stripX0 - 22);
                    int p  = (int) (cd * 19.0f);
                    RenderSystem.setShader(GameRenderer::getPositionTexShader);
                    RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                    RenderSystem.setShaderTexture(0, ATTACK_ICONS);
                    RenderSystem.enableBlend();
                    net.minecraft.client.gui.DrawableHelper.drawTexture(matrices, ax, ay, 0f, 94f, 18, 18, 256, 256);
                    net.minecraft.client.gui.DrawableHelper.drawTexture(matrices, ax, ay + 18 - p, 18f, (float) (112 - p), 18, p, 256, 256);
                    GuiFlush.flush();
                }
            }
            DiffuseLighting.disableGuiDepthLighting();
        } finally {
            if (screenOpen) GlassProgram.setShadowScale(1f);
            mv.pop();
            RenderSystem.applyModelViewMatrix();
        }
        ci.cancel();
    }
}
