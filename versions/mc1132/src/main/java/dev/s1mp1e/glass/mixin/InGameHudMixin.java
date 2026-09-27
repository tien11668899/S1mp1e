package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.glass.anim.Spring;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import dev.s1mp1e.glass.ui.OffHandFade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.collection.DefaultedList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Glass hotbar for 1.13.2 (Legacy Fabric, yarn build.604) — the last version to
 * gain it. Byte-for-byte the same measured 26.2 geometry as the 1.14.4 sibling
 * {@code InGameHudMixin}: the whole bar upscaled by {@link #SCALE} about its
 * bottom-centre, a frosted strip spanning centre±91 × 22 tall, an 18-wide
 * selector on the two-spring rig (lead 55 / trail 30, critically damped), and the
 * nine item stacks redrawn on top.
 *
 * <p>The backdrop is grabbed at {@code render} HEAD (world drawn, HUD not yet) so
 * the glass never samples itself; {@code renderHotbar} is cancelled and the item
 * stacks are redrawn because vanilla paints the widget sprite + items in one call.
 *
 * <h3>1.13.2 unmapped-name recon (verified against the merged jar + tiny)</h3>
 * Same tier as 1.14.4 (immediate-mode {@link GlStateManager}, no MatrixStack), but
 * {@code InGameHud}'s private HUD helpers are UNMAPPED in build.604, so they are
 * targeted by their intermediary {@code method_xxxx} names:
 * <ul>
 *   <li>{@code render(F)V} = {@code method_9420} — MAPPED, targeted as {@code "render"}
 *       (exactly one return).</li>
 *   <li><b>renderHotbar</b> = {@code b(F)V} = {@code method_9425} — binds WIDGETS
 *       (field {@code g}/{@code field_6288}), blits the 182×22 sprite and loops the
 *       9 stacks through {@code renderHotbarItem}. Cancelled + replaced here.</li>
 *   <li><b>renderStatusBars</b> = {@code o()V} = {@code method_18371} — health / armour
 *       / food / air (private; ONE {@code invokespecial} from {@code render}, offset 352,
 *       called only when {@code hasStatusBars()}). Lifted by {@link #DECO_LIFT}.</li>
 *   <li><b>renderExperienceBar</b> = {@code b(I)V} = {@code method_9432} — the XP bar
 *       (profiler {@code "expBar"}; ONE {@code invokevirtual} from {@code render}, offset
 *       411). Lifted by {@link #DECO_LIFT}; its five {@code TextRenderer.method_18355}
 *       draws (4 outline + green centre) ARE the level number. (The sibling
 *       {@code a(I)V}/{@code method_9426} is the mount jump bar and {@code method_18372}
 *       the mount health — neither is lifted, as in every reference.)</li>
 *   <li><b>renderHotbarItem</b> = {@code a(IIFLaog;Late;)V} = {@code method_9422} —
 *       draws one stack's model + count/durability overlay + cooldown pop in a single
 *       call (field {@code k}/{@code field_20063} is the GUI item renderer). Reached via
 *       the {@code @Invoker} {@code s1mp1e$renderHotbarItem}; it is vanilla's own item
 *       painter, so the redraw is pixel-identical to the cancelled pass. Self-skips
 *       empties.</li>
 * </ul>
 *
 * <p><b>DECO_LIFT balance.</b> The lift is applied by {@code @WrapOperation} around the
 * SINGLE invoke site of each method in {@code render(F)V} (MixinExtras handles the
 * private {@code invokespecial}, as mc1144 did for the private renderStatusBars): the
 * push/translate/pop wraps the whole call in try/finally, so the matrix stack stays
 * BALANCED even if another mod cancels the method at HEAD (hard rule 5). There are no
 * HEAD/RETURN push/pop injects on these methods any more, so the lift is applied once.
 *
 * <p>The GUI-scaled screen size lives in {@code InGameHud}'s own int fields
 * {@code field_20061} (scaledWidth) / {@code field_20062} (scaledHeight) — set at
 * {@code render} HEAD from the {@code Window} ({@code class_4117}), and exactly what
 * vanilla {@code renderHotbar} uses to place the bar — read here via the
 * {@code @Accessor}s {@code s1mp1e$scaledWidth}/{@code s1mp1e$scaledHeight}.
 * {@code PlayerInventory.selectedSlot} is MAPPED ({@code field_3966}); the item list
 * {@code main} is reached via {@link PlayerInventoryAccessor}.
 *
 * <p>{@code GlStateManager.translatef/scalef} do not exist at this tier — the methods
 * are named {@code translate(FFF)}/{@code scale(FFF)} ({@code method_9816}/
 * {@code method_9800}).
 *
 * <p>No screen-fade tail here: {@link InGameHudFadeMixin} already owns the in-world
 * {@code render} TAIL dissolve (and the HUD-module dispatch), so replicating it would
 * double-draw.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

    private static final float SCALE = 1.15f;
    /** Lift the hotbar itself off the screen edge (bottom margin). */
    private static final int LIFT = 4;
    /** Status bars + XP bar lift MORE than the hotbar so they clear the 1.15x-enlarged
     *  bar with a clean gap (26.2's DECO_LIFT). */
    private static final int DECO_LIFT = LIFT + 4;

    // InGameHud's own scaled-dim fields (unmapped) — the exact values vanilla
    // renderHotbar positions with. Accessor + invoker declared inline on the class mixin.
    @Accessor("field_20061")
    abstract int s1mp1e$scaledWidth();
    @Accessor("field_20062")
    abstract int s1mp1e$scaledHeight();
    @Invoker("method_9422")
    abstract void s1mp1e$renderHotbarItem(int x, int y, float tickDelta,
                                          PlayerEntity player, ItemStack stack);

    private Spring s1mp1e$lead, s1mp1e$trail;
    private int    s1mp1e$lastSlot = -1;
    private long   s1mp1e$lastNanos;
    /** Off-hand slot show/hide + item-swap animation (see {@link OffHandFade}). */
    private final OffHandFade s1mp1e$off = new OffHandFade();
    /** Set once the glass hotbar has thrown: from then on vanilla draws the hotbar (hard rule 6). */
    private static boolean s1mp1e$hotbarFailed;

    // Backdrop: earliest point in the HUD pass. grabNow (NOT the time-deduped grab) so the HUD glass owns a
    // fresh WORLD backdrop every frame — at a high in-world frame rate the 3ms dedup would skip the grab and
    // leave the glass sampling a stale/post-dim backdrop from a later stage, which flickers (matches the
    // mc1144/1.17.1/1.20.1 references).
    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$grab(float tickDelta, CallbackInfo ci) {
        try {
            SceneCapture.grabNow();
        } catch (Throwable t) {
            // no backdrop this frame; the glass draws skip themselves
        }
    }

    // Lift the status bars (health/armor/food/air) + XP bar by DECO_LIFT so the whole bottom HUD cluster
    // rises with the hotbar and keeps its spacing. @WrapOperation around the SINGLE invoke site of each in
    // render(F)V; the push/translate/pop wraps the whole call in try/finally, so the matrix stack stays
    // BALANCED even if another mod cancels the method (hard rule 5). 1.13.2: no MatrixStack; state goes
    // through GlStateManager (translate, not translatef).
    @WrapOperation(method = "render",
                   at = @At(value = "INVOKE",
                            target = "Lnet/minecraft/client/gui/hud/InGameHud;method_18371()V"))
    private void s1mp1e$liftStatus(InGameHud self, Operation<Void> original) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(0f, (float) -DECO_LIFT, 0f);
        try { original.call(self); } finally { GlStateManager.popMatrix(); }
    }

    @WrapOperation(method = "render",
                   at = @At(value = "INVOKE",
                            target = "Lnet/minecraft/client/gui/hud/InGameHud;method_9432(I)V"))
    private void s1mp1e$liftXp(InGameHud self, int x, Operation<Void> original) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(0f, (float) -DECO_LIFT, 0f);
        try { original.call(self, x); } finally { GlStateManager.popMatrix(); }
    }

    // Move the XP LEVEL number up onto the status row. renderExperienceBar's (method_9432) only
    // TextRenderer.draw calls (method_18355; exactly five: the 4-way outline + the green centre) ARE the
    // level, so wrapping them retargets exactly those five. y-4 turns vanilla's sh-35 into sh-39 within the
    // lifted frame (= mc1211's sh-39-DECO_LIFT), and the ±1 outline survives. This MOVES the level;
    // XpFlow never hides it.
    @WrapOperation(method = "method_9432",
                   at = @At(value = "INVOKE",
                            target = "Lnet/minecraft/client/font/TextRenderer;method_18355(Ljava/lang/String;FFI)I"))
    private int s1mp1e$xpLevelToStatusRow(TextRenderer font, String text, float x, float y, int color,
                                          Operation<Integer> original) {
        return original.call(font, text, x, y - 4f, color);
    }

    // renderHotbar = method_9425 (b(F)V). Cancel vanilla, draw the glass bar.
    @Inject(method = "method_9425", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassHotbar(float tickDelta, CallbackInfo ci) {
        if (s1mp1e$hotbarFailed) return;   // an earlier failure handed the hotbar back to vanilla
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        try {
            // First frame / pipeline race: try to grab a backdrop right here instead of bailing to vanilla.
            // GlassRenderer silently skips its draw if the backdrop still isn't ready (beginState guards
            // hasBackdrop) and the items are still redrawn below (matches the references).
            if (!SceneCapture.hasBackdrop()) SceneCapture.grabNow();
            s1mp1e$drawGlassHotbar(mc, tickDelta);
        } catch (Throwable t) {
            // Disable the glass hotbar for the session; vanilla draws it from now on (and this frame).
            s1mp1e$hotbarFailed = true;
            return;
        }
        ci.cancel();
    }

    private void s1mp1e$drawGlassHotbar(MinecraftClient mc, float tickDelta) {
        // Suppress the glass drop-shadow ring while a screen dims the scene; shadowScale is persistent, so
        // it is restored in finally.
        boolean screenOpen = mc.currentScreen != null;

        int center = s1mp1e$scaledWidth() / 2;
        int bottom = s1mp1e$scaledHeight() - LIFT;

        GlStateManager.pushMatrix();
        GlStateManager.translate((float) center, (float) bottom, 0f);
        GlStateManager.scale(SCALE, SCALE, 1f);
        GlStateManager.translate((float) -center, (float) -bottom, 0f);
        if (screenOpen) GlassProgram.setShadowScale(0f);
        try {
            int stripX0 = center - 91, stripY0 = bottom - 22;
            int stripX1 = center + 91, stripY1 = bottom;

            GlassRenderer.glass(stripX0, stripY0, stripX1, stripY1,
                                GlassRenderer.PAD_PILL, 1.0f, 0f, 1.0f,
                                GlassRenderer.FROST_PANEL);

            // Off-hand slot. Vanilla renderHotbar draws a box + item beside the bar when the off hand
            // holds something; because we cancel renderHotbar wholesale (to place the items inside the
            // scaled glass bar) it was being dropped. Restored here as one extra glass slot, positioned
            // and scaled with the bar so it travels with it, on the side opposite the main arm
            // (1.13.2's yarn-misnamed getDurability() is the main-arm HandOption; default right-handed
            // -> off on the left). Per the off-hand spec the slot uses the SAME material / shape /
            // rounding as the hotbar strip (FROST_PANEL, PAD_PILL, corner 1.0) — NOT a square box — and
            // it fades with the off hand: empty<->filled fades the whole slot (glass + item); a
            // both-hands-full swap keeps the slot and only crosses the icons. OffHandFade owns that
            // state. This reads only the player's OWN inventory, never any target/hit information.
            PlayerEntity player = mc.player;
            ItemStack offhand = player.getOffHandStack();
            boolean offLeft = true;
            try {
                net.minecraft.client.gui.screen.options.HandOption offArm =
                        player.getDurability().method_13037();   // opposite of the main arm = off arm
                offLeft = offArm == net.minecraft.client.gui.screen.options.HandOption.LEFT;
            } catch (Throwable ignored) {}
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

            s1mp1e$renderItems(mc, tickDelta, stripX0, stripY0, offBoxX0);

            // Attack-cooldown indicator ("Above hotbar" mode only, options.field_13290 == 2). Vanilla
            // renderHotbar draws it beside the bar; cancelling renderHotbar dropped it, so it is
            // restored here on the side OPPOSITE the off hand, mid-cooldown only, drawn from the vanilla
            // icons atlas exactly as vanilla does. Not shown with the default "crosshair" setting.
            // getAttackCooldownProgress (method_13275) reads only the player's OWN cooldown. Drawn after
            // the item pass, when DiffuseLighting/GL_LIGHTING is off (a 2D blit).
            if (mc.options.field_13290 == 2) {
                float cd = mc.player.method_13275(0.0F);
                if (cd < 1.0f) {
                    int ay = bottom - 20;
                    int ax = offLeft ? (stripX1 + 6) : (stripX0 - 22);
                    int p  = (int) (cd * 19.0f);
                    GlStateManager.color(1f, 1f, 1f, 1f);
                    GlStateManager.enableBlend();
                    mc.getTextureManager().bindTexture(DrawableHelper.GUI_ICONS_TEXTURE);
                    // InGameHud extends DrawableHelper; drawTexture(...) is public there. Cast instead
                    // of @Shadow so the annotation processor need not resolve an inherited target.
                    DrawableHelper self = (DrawableHelper) (Object) this;
                    self.drawTexture(ax, ay, 0, 94, 18, 18);
                    self.drawTexture(ax, ay + 18 - p, 18, 112 - p, 18, p);
                }
            }
        } finally {
            if (screenOpen) GlassProgram.setShadowScale(1f);
            GlStateManager.popMatrix();
        }
    }

    /**
     * Redraw the nine hotbar stacks the cancelled vanilla pass owned. Uses vanilla's
     * own {@code renderHotbarItem} ({@code method_9422}) so model + count/durability +
     * cooldown pop are all identical; it self-skips empties. Wrapped in
     * {@link DiffuseLighting} exactly as vanilla {@code renderHotbar} wraps its loop
     * ({@code DiffuseLighting.enable()}/{@code disable()}, {@code cfr.c}/{@code cfr.a}) —
     * without it the GUI item models render flat/dark.
     */
    private void s1mp1e$renderItems(MinecraftClient mc, float tickDelta, int x0, int y0, int offBoxX0) {
        PlayerEntity player = mc.player;
        DefaultedList<ItemStack> main = ((PlayerInventoryAccessor) player.inventory).s1mp1e$main();

        GlStateManager.enableRescaleNormal();
        DiffuseLighting.enable();
        try {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = main.get(i);
                int ix = x0 + 3 + i * 20, iy = y0 + 3;
                s1mp1e$renderHotbarItem(ix, iy, tickDelta, player, stack);
            }
            // Off-hand item(s), centred in the glass slot (3px inset, same as the main slots). The
            // OffHandFade plan gives up to two stacks with 0..1 centre-anchored scales: one for a plain
            // fade in/out, two while a both-hands-full swap cross-transitions the icons.
            int oix = offBoxX0 + 3, oiy = y0 + 3;
            s1mp1e$drawOffItem(tickDelta, player, s1mp1e$off.primaryStack(),   oix, oiy, s1mp1e$off.primaryScale());
            if (s1mp1e$off.crossing()) {
                s1mp1e$drawOffItem(tickDelta, player, s1mp1e$off.secondaryStack(), oix, oiy, s1mp1e$off.secondaryScale());
            }
        } finally {
            DiffuseLighting.disable();
            GlStateManager.disableRescaleNormal();
        }
    }

    /** Draw one off-hand item at {@code scale} about the 16px cell centre (the fixed-function
     *  scale/opacity stand-in for a fade). Uses vanilla's own {@code renderHotbarItem} so the
     *  model + count/durability + cooldown pop are identical; it self-skips empties. */
    private void s1mp1e$drawOffItem(float tickDelta, PlayerEntity player,
                                    ItemStack stack, int ix, int iy, float scale) {
        if (stack == null || stack.isEmpty() || scale <= 0.02f) return;
        float cx = ix + 8f, cy = iy + 8f;
        GlStateManager.pushMatrix();
        GlStateManager.translate(cx, cy, 0f);
        GlStateManager.scale(scale, scale, 1f);
        GlStateManager.translate(-cx, -cy, 0f);
        try {
            s1mp1e$renderHotbarItem(ix, iy, tickDelta, player, stack);
        } finally {
            GlStateManager.popMatrix();
        }
    }
}
