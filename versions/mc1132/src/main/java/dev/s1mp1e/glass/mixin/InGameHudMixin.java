package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.glass.anim.Spring;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
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
 *   <li>{@code render(F)V} = {@code method_9420} — MAPPED, targeted as {@code "render"}.</li>
 *   <li><b>renderHotbar</b> = {@code b(F)V} = {@code method_9425} — binds WIDGETS
 *       (field {@code g}/{@code field_6288}), blits the 182×22 sprite and loops the
 *       9 stacks through {@code renderHotbarItem}. Cancelled + replaced here.</li>
 *   <li><b>renderStatusBars</b> = {@code o()V} = {@code method_18371} — health / armour
 *       / food / air (reads {@code getHealth} + {@code heartJumpEndTick}). Lifted by
 *       {@link #DECO_LIFT}. (Called only when {@code hasStatusBars()}.)</li>
 *   <li><b>renderExperienceBar</b> = {@code b(I)V} = {@code method_9432} — the XP bar
 *       (profiler {@code "expBar"}, reads {@code experienceProgress}). Takes the bar
 *       {@code x}. Lifted by {@link #DECO_LIFT}. (The sibling {@code a(I)V}/
 *       {@code method_9426} is the mount jump bar {@code "jumpBar"} — NOT lifted.)</li>
 *   <li><b>renderHotbarItem</b> = {@code a(IIFLaog;Late;)V} = {@code method_9422} —
 *       draws one stack's model + count/durability overlay + cooldown pop in a single
 *       call (field {@code k}/{@code field_20063} is the GUI item renderer). Reached via
 *       the {@code @Invoker} {@code s1mp1e$renderHotbarItem}; it is vanilla's own item
 *       painter, so the redraw is pixel-identical to the cancelled pass. Self-skips
 *       empties.</li>
 * </ul>
 *
 * <p>The GUI-scaled screen size lives in {@code InGameHud}'s own int fields
 * {@code field_20061} (scaledWidth) / {@code field_20062} (scaledHeight) — set at
 * {@code render} HEAD from the (unmapped) {@code Window}, and exactly what vanilla
 * {@code renderHotbar} uses to place the bar — read here via the {@code @Accessor}s
 * {@code s1mp1e$scaledWidth}/{@code s1mp1e$scaledHeight}. That avoids both the unmapped
 * {@code Window} and any projection-inversion rounding.
 * {@code PlayerInventory.selectedSlot} is MAPPED ({@code field_3966}); the item list
 * {@code main} is not, reached via {@link PlayerInventoryAccessor}.
 *
 * <p>{@code GlStateManager.translatef/scalef} do not exist at this tier — the methods
 * are named {@code translate(FFF)}/{@code scale(FFF)} ({@code method_9816}/
 * {@code method_9800}).
 *
 * <p>No screen-fade tail here: {@link InGameHudFadeMixin} already owns the in-world
 * {@code render} TAIL dissolve, so replicating it would double-draw.
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

    // Backdrop: earliest point in the HUD pass.
    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$grab(float tickDelta, CallbackInfo ci) {
        SceneCapture.grab();
    }

    // Lift the status bars (health/armor/food/air) + XP bar by DECO_LIFT so the whole
    // bottom HUD cluster rises with the hotbar and keeps its spacing. 1.13.2: no
    // MatrixStack; state goes through GlStateManager (translate, not translatef).
    @Inject(method = "method_18371", at = @At("HEAD"))
    private void s1mp1e$liftStatusHead(CallbackInfo ci) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(0f, (float) -DECO_LIFT, 0f);
    }
    @Inject(method = "method_18371", at = @At("RETURN"))
    private void s1mp1e$liftStatusTail(CallbackInfo ci) {
        GlStateManager.popMatrix();
    }
    @Inject(method = "method_9432", at = @At("HEAD"))
    private void s1mp1e$liftXpHead(int x, CallbackInfo ci) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(0f, (float) -DECO_LIFT, 0f);
    }
    @Inject(method = "method_9432", at = @At("RETURN"))
    private void s1mp1e$liftXpTail(int x, CallbackInfo ci) {
        GlStateManager.popMatrix();
    }

    // renderHotbar = method_9425 (b(F)V). Cancel vanilla, draw the glass bar.
    @Inject(method = "method_9425", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassHotbar(float tickDelta, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || !SceneCapture.hasBackdrop()) return;

        int center = s1mp1e$scaledWidth() / 2;
        int bottom = s1mp1e$scaledHeight() - LIFT;

        GlStateManager.pushMatrix();
        GlStateManager.translate((float) center, (float) bottom, 0f);
        GlStateManager.scale(SCALE, SCALE, 1f);
        GlStateManager.translate((float) -center, (float) -bottom, 0f);
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

            s1mp1e$renderItems(mc, tickDelta, stripX0, stripY0);
        } finally {
            GlStateManager.popMatrix();
        }
        ci.cancel();
    }

    /**
     * Redraw the nine hotbar stacks the cancelled vanilla pass owned. Uses vanilla's
     * own {@code renderHotbarItem} ({@code method_9422}) so model + count/durability +
     * cooldown pop are all identical; it self-skips empties. Wrapped in
     * {@link DiffuseLighting} exactly as vanilla {@code renderHotbar} wraps its loop
     * ({@code DiffuseLighting.enable()}/{@code disable()}, {@code cfr.c}/{@code cfr.a}) —
     * without it the GUI item models render flat/dark.
     */
    private void s1mp1e$renderItems(MinecraftClient mc, float tickDelta, int x0, int y0) {
        PlayerEntity player = mc.player;
        DefaultedList<ItemStack> main = ((PlayerInventoryAccessor) player.inventory).s1mp1e$main();

        GlStateManager.enableRescaleNormal();
        DiffuseLighting.enable();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = main.get(i);
            int ix = x0 + 3 + i * 20, iy = y0 + 3;
            s1mp1e$renderHotbarItem(ix, iy, tickDelta, player, stack);
        }
        DiffuseLighting.disable();
        GlStateManager.disableRescaleNormal();
    }
}
