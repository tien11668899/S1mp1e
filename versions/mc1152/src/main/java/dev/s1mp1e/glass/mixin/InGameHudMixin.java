package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.glass.anim.Spring;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import dev.s1mp1e.glass.render.ScreenFade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.gui.screen.ingame.ContainerScreen;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Glass hotbar + HUD dispatch for 1.15.2 — the Fabric counterpart of the 1.8.9/1.12.2
 * GlassHudHandler and of LiquidGlass26's HudHotbarMixin. Same measured 26.2
 * geometry: the whole bar upscaled by {@link #SCALE} about its bottom-centre,
 * a frosted strip spanning centre±91 × 22 tall, and an 18×18 selector on the
 * two-spring rig (lead 55 / trail 30, critically damped).
 *
 * <p>The backdrop is grabbed at {@code render} HEAD (world drawn, HUD not yet)
 * so the glass never samples itself; {@code renderHotbar} is cancelled and the
 * item stacks are redrawn because vanilla paints widget + items in one call.
 *
 * <p><b>Under a container.</b> The HEAD grab is skipped while a {@link ContainerScreen}
 * (creative / inventory included) is open: the container grabs its OWN dimmed backdrop
 * after its renderBackground dim, and SceneCapture's 3 ms dedup would otherwise make
 * that post-dim grab a no-op (the container panel would refract the un-dimmed world).
 * This is the mc1165 guard. Non-container screens and no screen keep a fresh grab.
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

    // Backdrop: earliest point in the HUD pass. grabNow (NOT the time-deduped grab) so the HUD glass owns a
    // fresh WORLD backdrop every frame, UNLESS a container is open: a ContainerScreen (covers creative and
    // inventory) grabs its own dimmed backdrop after the dim, and this earlier grab must not win the
    // SceneCapture 3ms dedup (mc1165 guard); otherwise the container panel refracts the un-dimmed world.
    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$grab(float tickDelta, CallbackInfo ci) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (!(mc.currentScreen instanceof ContainerScreen)) SceneCapture.grabNow();
            // DEV-ONLY (DevShot tab-list scene): re-press the player-list key here, AFTER Mouse.updateMouse cleared it
            // from the physical key and BEFORE the tab-list check later in this same render, so the vanilla tab list
            // renders in the headless shot window. Inert in production (the flag is only ever set by DevShot).
            if (dev.s1mp1e.client.DevShot.forceTabListKey) mc.options.keyPlayerList.setPressed(true);
        } catch (Throwable t) {
            // a failed grab only leaves the previous backdrop in place
        }
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
        // HUD modules first: OUR HUD dispatch runs here (Fabric's HudRenderCallback, also at render RETURN, is
        // deliberately not used; fabric-api is not a declared dependency). This is the FIRST statement, BEFORE
        // the currentScreen early-return, so HUD modules draw under the ScreenFade dissolve and also while a
        // screen is open (as in the reference). HudDispatch guards each module with its own try/catch; the
        // outer guard here keeps a dispatch failure from skipping the fade below. InGameHudFadeMixin's
        // identical TAIL does NOT dispatch (only this one does), so no double dispatch.
        try {
            dev.s1mp1e.client.HudDispatch.renderAll();
        } catch (Throwable t) {
            // never let the HUD dispatch break the fade
        }
        if (MinecraftClient.getInstance().currentScreen != null) return;
        ScreenFade.draw();
        ScreenFade.captureFrame();
    }

    // Lift the status bars (health/armor/food/air) + XP bar by DECO_LIFT so the whole bottom HUD cluster
    // rises with the hotbar and keeps its spacing. Done via @WrapOperation around the SINGLE INVOKE sites in
    // render(F): the GL push/translate/pop wraps the whole call in try/finally, so the matrix stack stays
    // BALANCED even if another mod cancels renderStatusBars / renderExperienceBar (hard rule 5). 1.15.2 has
    // no MatrixStack on these methods; state goes through RenderSystem's fixed-function model-view.
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

    // Move the XP LEVEL number up onto the status row. renderExperienceBar's only TextRenderer.draw calls ARE
    // the level (its 4-way outline + green centre), so wrapping them retargets exactly those five. y-4 turns
    // vanilla's sh-35 into sh-39 within the lifted frame (= mc1211's sh-39-DECO_LIFT), and the +/-1 outline
    // survives. This MOVES the level; XpFlow never hides it.
    @WrapOperation(method = "renderExperienceBar",
                   at = @At(value = "INVOKE",
                            target = "Lnet/minecraft/client/font/TextRenderer;draw(Ljava/lang/String;FFI)I"))
    private int s1mp1e$xpLevelToStatusRow(TextRenderer font, String text, float x, float y, int color,
                                          Operation<Integer> original) {
        return original.call(font, text, x, y - 4f, color);
    }

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
            if (screenOpen) GlassProgram.setShadowScale(1f);
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
