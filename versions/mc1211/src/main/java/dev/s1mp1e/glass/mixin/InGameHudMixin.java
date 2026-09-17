package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.glass.anim.Spring;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import dev.s1mp1e.glass.render.ScreenFade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
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
    /** The status bars (health/armor/food/air) + XP bar lift MORE than the hotbar so
     *  they clear the 1.15x-enlarged bar with a clean gap (26.2's DECO_LIFT). */
    private static final int DECO_LIFT = LIFT + 4;

    private Spring s1mp1e$lead, s1mp1e$trail;
    private int    s1mp1e$lastSlot = -1;
    private long   s1mp1e$lastNanos;

    // Backdrop: earliest point in the HUD pass. grabNow (not the time-deduped grab)
    // so the hotbar owns a fresh WORLD backdrop every frame — at the high frame rate
    // of a paused screen the dedup would skip this and leave the hotbar sampling a
    // stale/post-dim backdrop from a later stage, which showed as flicker + dark edges.
    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$grab(DrawContext context, RenderTickCounter tickDelta, CallbackInfo ci) {
        SceneCapture.grabNow();
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
    private void s1mp1e$fadeDrawHud(DrawContext context, RenderTickCounter tickDelta, CallbackInfo ci) {
        if (MinecraftClient.getInstance().currentScreen != null) return;
        ScreenFade.draw();
        ScreenFade.captureFrame();
    }

    // Lift the status bars (health/armor/food/air) + XP bar by DECO_LIFT so the whole
    // bottom HUD cluster rises with the hotbar and keeps its spacing. 1.17.1:
    // pushMatrix/translatef are gone -> mutate the model-view MatrixStack and
    // applyModelViewMatrix() so the shift reaches the shader the vanilla blits use.
    @Inject(method = "renderStatusBars", at = @At("HEAD"))
    private void s1mp1e$liftStatusHead(DrawContext context, CallbackInfo ci) {
        org.joml.Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        mv.translate(0f, -DECO_LIFT, 0f);
        RenderSystem.applyModelViewMatrix();
    }
    @Inject(method = "renderStatusBars", at = @At("RETURN"))
    private void s1mp1e$liftStatusTail(DrawContext context, CallbackInfo ci) {
        RenderSystem.getModelViewStack().popMatrix();
        RenderSystem.applyModelViewMatrix();
    }
    @Inject(method = "renderExperienceBar", at = @At("HEAD"))
    private void s1mp1e$liftXpHead(DrawContext context, int x, CallbackInfo ci) {
        org.joml.Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        mv.translate(0f, -DECO_LIFT, 0f);
        RenderSystem.applyModelViewMatrix();
    }
    @Inject(method = "renderExperienceBar", at = @At("RETURN"))
    private void s1mp1e$liftXpTail(DrawContext context, int x, CallbackInfo ci) {
        RenderSystem.getModelViewStack().popMatrix();
        RenderSystem.applyModelViewMatrix();
    }

    // The XP LEVEL number ("30"): vanilla draws it centred at scaledHeight-35, just above the XP bar. The
    // user wants it up on the health/food row and centred (it then sits in the gap between the health bar
    // on the left and the food bar on the right). That row's logical top is scaledHeight-39, lifted by
    // DECO_LIFT — so we cancel vanilla's renderExperienceLevel and redraw the number at exactly that visual
    // row, screen-centred, keeping vanilla's look (black 4-way outline + green centre, no shadow).
    //
    // The guard replicates vanilla's private shouldRenderExperience() inline — player.getJumpingMount()==null
    // && interactionManager.hasExperienceBar() (both PUBLIC, so Loom remaps them; a @Shadow of the private
    // method would need a mixin-refmap entry that the AP does not emit, and would bind in dev but break in
    // the intermediary-mapped production jar).
    @Inject(method = "renderExperienceLevel", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$xpLevelAtStatusRow(DrawContext context, RenderTickCounter counter, CallbackInfo ci) {
        ci.cancel();   // we fully own the level number now
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.interactionManager == null) return;
        if (mc.player.getJumpingMount() != null) return;          // riding: vanilla shows the jump bar, no XP
        if (!mc.interactionManager.hasExperienceBar()) return;    // creative/spectator: no XP bar
        int level = mc.player.experienceLevel;
        if (level <= 0) return;
        String s = Integer.toString(level);
        TextRenderer tr = mc.textRenderer;
        int x = (context.getScaledWindowWidth() - tr.getWidth(s)) / 2;
        int y = context.getScaledWindowHeight() - 39 - DECO_LIFT;   // health/food visual row
        context.drawText(tr, s, x + 1, y,     0,        false);
        context.drawText(tr, s, x - 1, y,     0,        false);
        context.drawText(tr, s, x,     y + 1, 0,        false);
        context.drawText(tr, s, x,     y - 1, 0,        false);
        context.drawText(tr, s, x,     y,     0x80FF20, false);
    }

    // Replace the vanilla hotbar with the glass bar (scaled by SCALE about
    // bottom-centre) AND redraw the item stacks so they sit INSIDE our scaled frame.
    //
    // Two scale passes, SEQUENCED (never overlapping) — the fix for both the size and
    // the black-item bugs on 1.21.1:
    //   * GLASS FRAME under RenderSystem.getModelViewStack() scaled (GlassRenderer's
    //     raw-GL shader reads its uniforms from there), then POP it.
    //   * ITEMS under context.getMatrices() scaled, with RS model-view back at IDENTITY.
    // 1.21.1's DrawContext.drawItem composes BOTH stacks, so scaling both at once
    // double-scaled items to ~1.32× ("物品變大了"); scaling ONLY RS left the FIRST item
    // black (the first vanilla draw after our raw-GL glass inherited corrupted state that
    // pushing+scaling the DrawContext matrix around the item draw primes away). Sequencing
    // gives a clean 1.15× for both AND primes the state so no item goes black.
    // Plus: context.draw() flushes before the raw-GL glass and after the items;
    // setShader re-establishes the item shader; enableGuiDepthLighting restores the GUI
    // diffuse light (else 3D block models render flat black). 1.21: renderHotbar is
    // (DrawContext, RenderTickCounter) (method_1759).
    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassHotbar(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        if (!SceneCapture.hasBackdrop()) SceneCapture.grabNow();

        int center = mc.getWindow().getScaledWidth() / 2;
        int bottom = mc.getWindow().getScaledHeight() - LIFT;
        int stripX0 = center - 91, stripY0 = bottom - 22;
        int stripX1 = center + 91, stripY1 = bottom;

        // ---- pass 1: glass frame under the RS model-view scale ----
        org.joml.Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        mv.translate(center, bottom, 0);
        mv.scale(SCALE, SCALE, 1f);
        mv.translate(-center, -bottom, 0);
        RenderSystem.applyModelViewMatrix();
        boolean screenOpen = mc.currentScreen != null;   // suppress the shadow ring while a screen dims the bg
        if (screenOpen) dev.s1mp1e.glass.render.GlassProgram.setShadowScale(0f);
        try {
            context.draw(); // flush pending HUD draws before our raw-GL glass

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
        } finally {
            if (screenOpen) dev.s1mp1e.glass.render.GlassProgram.setShadowScale(1f);
            mv.popMatrix();
            RenderSystem.applyModelViewMatrix();   // RS model-view back to IDENTITY for the items
        }

        // ---- pass 2: items under the DrawContext matrix scale (RS is identity now) ----
        net.minecraft.client.util.math.MatrixStack matrices = context.getMatrices();
        matrices.push();
        matrices.translate(center, bottom, 0);
        matrices.scale(SCALE, SCALE, 1f);
        matrices.translate(-center, -bottom, 0);
        try {
            RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
            DiffuseLighting.enableGuiDepthLighting();
            RenderSystem.setShaderColor(0f, 0f, 0f, 0f);   // cache-defeat -> force white so no item is tinted black
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            PlayerEntity player = mc.player;
            for (int i = 0; i < 9; i++) {
                ItemStack stack = player.getInventory().main.get(i);
                if (stack.isEmpty()) continue;
                int ix = stripX0 + 3 + i * 20, iy = stripY0 + 3;
                context.drawItem(player, stack, ix, iy, 0);
                context.drawItemInSlot(mc.textRenderer, stack, ix, iy);
            }
            context.draw();
            DiffuseLighting.disableGuiDepthLighting();
        } finally {
            matrices.pop();
        }
        ci.cancel();
    }
}
