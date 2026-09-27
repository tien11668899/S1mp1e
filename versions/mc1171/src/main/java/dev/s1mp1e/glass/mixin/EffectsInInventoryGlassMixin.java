package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassEffects;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.AbstractInventoryScreen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.effect.StatusEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feature F — the status-effect panel becomes ONE continuous glass strip, placed on the RIGHT of the inventory
 * exactly like 1.18.2 (S9P3, user: 藥水欄應顯示在右邊且不能與背包打架).
 *
 * <p>1.17.1's {@code AbstractInventoryScreen.drawStatusEffects(MatrixStack)} lays the effect column to the LEFT of the
 * survival/creative inventory at {@code x = this.x - 124} in three passes: {@code drawStatusEffectBackgrounds} (the
 * dark rounded box sprite per entry), {@code drawStatusEffectSprites} (icons) and {@code drawStatusEffectDescriptions}
 * (name + time). Verified against yarn 1.17.1+build.65: {@code drawStatusEffectBackgrounds(MatrixStack, int x,
 * int spacing, Iterable)} draws each box with {@code this.drawTexture(MatrixStack, x, y, 0, 166, 140, 32)} — a fixed
 * 140x32 box, advancing {@code y} by {@code spacing} each entry from {@code this.y}.
 *
 * <h3>Side + no-overlap (S9P3, 1.18.2 = reference)</h3>
 * Vanilla shifts the whole inventory RIGHT (in {@code applyStatusEffectOffset}: {@code x = 160 + (width -
 * backgroundWidth - 200) / 2}) so the left column fits, then draws the column at {@code this.x - 124}. Both are undone:
 * the inventory is re-CENTRED at {@code applyStatusEffectOffset} RETURN, and the column's first int local {@code i} in
 * {@code drawStatusEffects} (vanilla {@code this.x - 124}) is rewritten to {@code leftPos + backgroundWidth + 2} — since
 * the three passes all read that same {@code i}, the whole column moves to the right together, never overlapping the
 * panel. When the right space cannot fit the wide 140-px column the strip becomes a COMPACT icon-only column
 * ({@link #COMPACT_W} wide, the name/time pass suppressed).
 *
 * <p>The strip reuses the world+dim backdrop the inventory panel already grabbed this frame (never re-grabs, which would
 * fold the drawn panel into the strip's own backdrop -> self-ghost). Icons and text draw afterwards and stay on top.
 * Chest / plain containers have no effect column, so no strip.
 */
@Mixin(AbstractInventoryScreen.class)
public abstract class EffectsInInventoryGlassMixin {

    /** Vanilla's fixed effect-box width on 1.17.1. */
    private static final int S1MP1E_BOX_W = 140;
    /** Compact icon-only column width: vanilla's icon at x+6, 18 wide, +6 right margin. */
    private static final int COMPACT_W = 30;

    /** {@code AbstractInventoryScreen.drawStatusEffects} — true when the effect column is drawn this open. */
    @Shadow protected boolean drawStatusEffects;

    /** True for the current {@code drawStatusEffectBackgrounds} call: the glass strip replaced the boxes. */
    private boolean s1mp1e$stripActive;
    /** Right space too narrow for the wide column: draw the compact icon-only column and hide name/time. */
    private boolean s1mp1e$compact;
    /** The strip was actually enqueued this {@code drawStatusEffects} call (persists across its passes). */
    private boolean s1mp1e$stripDrawn;

    private HandledScreenAccessor s1mp1e$acc() { return (HandledScreenAccessor) this; }

    // ---- S9P3: keep the inventory CENTRED (undo vanilla's effect shift) --------------------------------------
    @Inject(method = "applyStatusEffectOffset", at = @At("RETURN"))
    private void s1mp1e$unshift(CallbackInfo ci) {
        if (!this.drawStatusEffects) return;   // no effects: vanilla already centred it
        HandledScreenAccessor a = s1mp1e$acc();
        int width = ((Screen) (Object) this).width;
        a.s1mp1e$setX((width - a.s1mp1e$backgroundWidth()) / 2);
    }

    // ---- S9P3: relocate the effect column to the RIGHT of the (centred) inventory ---------------------------
    // Rewrites drawStatusEffects' first int local `i` (vanilla: this.x - 124) to leftPos + backgroundWidth + 2, and
    // decides wide vs compact from the space on the right. Backgrounds / sprites / descriptions share this `i`, so the
    // whole column moves as one.
    @ModifyVariable(method = "drawStatusEffects", at = @At("STORE"), index = 2)
    private int s1mp1e$moveRight(int i) {
        HandledScreenAccessor a = s1mp1e$acc();
        int right = a.s1mp1e$x() + a.s1mp1e$backgroundWidth() + 2;
        int width = ((Screen) (Object) this).width;
        this.s1mp1e$compact = right + S1MP1E_BOX_W > width - 2;   // no room for the wide column
        this.s1mp1e$stripDrawn = false;                          // reset for this drawStatusEffects call
        return right;
    }

    // ---- S9P3: hide the name/time pass in the compact column -----------------------------------------------
    @Inject(method = "drawStatusEffectDescriptions", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$compactNoText(MatrixStack matrices, int x, int spacing,
                                      Iterable<StatusEffectInstance> effects, CallbackInfo ci) {
        if (this.s1mp1e$compact && this.s1mp1e$stripDrawn) ci.cancel();
    }

    @Inject(method = "drawStatusEffectBackgrounds", at = @At("HEAD"))
    private void s1mp1e$effectStrip(MatrixStack matrices, int x, int spacing,
                                    Iterable<StatusEffectInstance> effects, CallbackInfo ci) {
        s1mp1e$stripActive = false;
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;
        int count = 0;
        for (StatusEffectInstance ignored : effects) count++;
        if (count <= 0) return;

        // Reuse the world+dim backdrop the inventory panel already grabbed this frame (its drawBackground redirect).
        // Never re-grab here (would fold the already-drawn panel/items into the strip's own backdrop -> self-ghost).
        if (!SceneCapture.hasBackdrop()) SceneCapture.grabNow();

        int x0 = x;
        int y0 = s1mp1e$acc().s1mp1e$y();
        int x1 = x + (this.s1mp1e$compact ? COMPACT_W : S1MP1E_BOX_W);
        int y1 = y0 + (count - 1) * spacing + 32;
        s1mp1e$stripActive = GlassEffects.strip(x0, y0, x1, y1, spacing, count, 1.0f);
        this.s1mp1e$stripDrawn = s1mp1e$stripActive;
    }

    @Inject(method = "drawStatusEffectBackgrounds", at = @At("RETURN"))
    private void s1mp1e$effectStripEnd(MatrixStack matrices, int x, int spacing,
                                       Iterable<StatusEffectInstance> effects, CallbackInfo ci) {
        s1mp1e$stripActive = false;
    }

    /** Swallow each vanilla per-box sprite while the glass strip is up; otherwise draw it as vanilla. */
    @Redirect(method = "drawStatusEffectBackgrounds",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/AbstractInventoryScreen;"
                            + "drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$suppressBox(AbstractInventoryScreen self, MatrixStack matrices,
                                    int x, int y, int u, int v, int w, int h) {
        if (s1mp1e$stripActive) return;   // strip replaces the boxes
        self.drawTexture(matrices, x, y, u, v, w, h);
    }
}
