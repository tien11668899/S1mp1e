package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.glass.anim.Spring;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import dev.s1mp1e.glass.render.ScreenFade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Glass hotbar for 1.17.1 — the Fabric counterpart of the 1.8.9/1.12.2
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
    /** Status bars (health/armor/food/air) + XP bar lift = hotbar lift +
     *  scaled-height compensation + breathing room. Breathing = 8 → total 16px
     *  lift, just enough clearance above the scaled hotbar without floating away. */
    private static final int DECO_LIFT = LIFT + (int)Math.ceil(22 * (SCALE - 1)) + 3;

    private Spring s1mp1e$lead, s1mp1e$trail;
    private int    s1mp1e$lastSlot = -1;
    private long   s1mp1e$lastNanos;

    // Backdrop: earliest point in the HUD pass.
    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$grab(DrawContext context, float tickDelta, CallbackInfo ci) {
        SceneCapture.grab();
    }

    /**
     * System 1 (screen open/close cross-dissolve) — DRAW half, in-world branch.
     *
     * <p>Forge counterpart {@code GlassScreenFadeHandler#onOverlayPost(
     * RenderGameOverlayEvent.Post)} guarded by {@code type == ALL &&
     * currentScreen == null}: back in the world (no screen), draw the dissolve
     * above the HUD then snapshot the finished frame. Same guard, same order
     * ({@link ScreenFade#draw()} then {@link ScreenFade#captureFrame()}) here.
     *
     * <p>Target {@code InGameHud.render(MatrixStack, float)} (intermediary
     * {@code method_1753}, descriptor
     * {@code (Lnet/minecraft/client/util/math/MatrixStack;F)V}; verified against
     * yarn 1.17.1+build.65) at {@code TAIL}. The {@code currentScreen == null} check
     * keeps this in-world branch mutually exclusive with {@code ScreenFadeMixin}'s
     * screen branch, exactly the two Forge Post handlers.
     *
     * <p><b>1.17.1 core-profile status: TRIGGER wired, DRAW is a no-op.</b>
     * {@link ScreenFade} is STUBBED on 1.17.1 (immediate-mode blit illegal under the
     * OpenGL 3.2 core profile), so both calls do nothing and a screen change is a
     * hard cut. Visual-TODO: core-profile rewrite of ScreenFade
     * (CORE_PROFILE_SPEC §7). Kept because it is harmless and goes live the instant
     * ScreenFade is un-stubbed. (The standalone {@link InGameHudFadeMixin} carries
     * the identical injection; both are registered, matching the 1.16.5 line — two
     * no-op calls while stubbed.)
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void s1mp1e$fadeDrawHud(DrawContext context, float tickDelta, CallbackInfo ci) {
        if (MinecraftClient.getInstance().currentScreen != null) return;
        ScreenFade.draw();
        ScreenFade.captureFrame();
    }

    // Lift status bars (health/armor/food/air) + XP bar by DECO_LIFT so the whole
    // bottom HUD cluster rises with the hotbar and keeps its spacing.
    // 1.20+: vanilla draws these via DrawContext helpers whose shader reads the
    // DrawContext's own matrix stack (getMatrices()), NOT RenderSystem's model
    // view. Push/pop THAT stack — mutating RenderSystem does nothing for them.
    @Inject(method = "renderStatusBars", at = @At("HEAD"))
    private void s1mp1e$liftStatusHead(DrawContext context, CallbackInfo ci) {
        context.getMatrices().push();
        context.getMatrices().translate(0f, -DECO_LIFT, 0f);
    }
    @Inject(method = "renderStatusBars", at = @At("RETURN"))
    private void s1mp1e$liftStatusTail(DrawContext context, CallbackInfo ci) {
        context.getMatrices().pop();
    }
    @Inject(method = "renderExperienceBar", at = @At("HEAD"))
    private void s1mp1e$liftXpHead(DrawContext context, int x, CallbackInfo ci) {
        context.getMatrices().push();
        context.getMatrices().translate(0f, -DECO_LIFT, 0f);
    }
    @Inject(method = "renderExperienceBar", at = @At("RETURN"))
    private void s1mp1e$liftXpTail(DrawContext context, int x, CallbackInfo ci) {
        context.getMatrices().pop();
    }

    // Replace the vanilla hotbar with the glass bar (scaled by SCALE about
    // bottom-centre) AND redraw the item stacks so they sit INSIDE our scaled
    // frame instead of the vanilla-position ones.
    //
    // Fix vs the earlier cancel+redraw that produced black items:
    //   1. Scale via DrawContext.getMatrices() (item shader honours this),
    //      NOT via RenderSystem.getModelViewStack() (item shader ignored it).
    //   2. context.draw() flushes any pending batched draws BEFORE our raw GL
    //      glass and AGAIN before item render, so glass state doesn't leak
    //      into the item atlas texture unit.
    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassHotbar(float tickDelta, DrawContext context, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        // If no backdrop grabbed yet (first frame / pipeline race), TRY to grab
        // right here — better late than never. GlassRenderer will silently skip
        // its draw if the backdrop still isn't ready.
        if (!SceneCapture.hasBackdrop()) SceneCapture.grab();

        int center = mc.getWindow().getScaledWidth() / 2;
        int bottom = mc.getWindow().getScaledHeight() - LIFT;
        int stripX0 = center - 91, stripY0 = bottom - 22;
        int stripX1 = center + 91, stripY1 = bottom;

        MatrixStack matrices = context.getMatrices();
        MatrixStack mv       = RenderSystem.getModelViewStack();

        // TWO stacks need the same scale:
        //   * RenderSystem.getModelViewStack — used by GlassRenderer's raw-GL
        //     shader (ProjMat/ModelViewMat uniforms read from RenderSystem).
        //   * context.getMatrices — used by DrawContext.drawItem for item render.
        // Push+scale BOTH so glass frame and items enlarge together and stay
        // pixel-aligned inside the same coordinate space.
        matrices.push();
        matrices.translate(center, bottom, 0);
        matrices.scale(SCALE, SCALE, 1f);
        matrices.translate(-center, -bottom, 0);
        mv.push();
        mv.translate(center, bottom, 0);
        mv.scale(SCALE, SCALE, 1f);
        mv.translate(-center, -bottom, 0);
        RenderSystem.applyModelViewMatrix();
        try {
            context.draw();
            GlassRenderer.glass(stripX0, stripY0, stripX1, stripY1,
                                GlassRenderer.PAD_PILL, 1.0f, 0f, 1.0f,
                                GlassRenderer.FROST_PANEL);

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

            // Items drawn INSIDE the same scaled matrix as the strip — vanilla
            // pitch (20px, i.e. stripX0+3 + i*20 for each slot's left edge) so
            // they line up exactly with the glass frame's 9 slot cells.
            RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
            PlayerEntity player = mc.player;
            for (int i = 0; i < 9; i++) {
                ItemStack stack = player.getInventory().main.get(i);
                if (stack.isEmpty()) continue;
                int ix = stripX0 + 3 + i * 20, iy = stripY0 + 3;
                context.drawItem(player, stack, ix, iy, 0);
                context.drawItemInSlot(mc.textRenderer, stack, ix, iy);
            }
            context.draw();
        } finally {
            matrices.pop();
            mv.pop();
            RenderSystem.applyModelViewMatrix();
        }
        ci.cancel();
    }
}
