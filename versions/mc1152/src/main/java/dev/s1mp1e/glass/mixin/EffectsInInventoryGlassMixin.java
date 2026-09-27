package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.glass.render.GlassEffects;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.AbstractInventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PORT_SPEC feature (F) — the status-effect boxes beside the survival / creative inventory become
 * ONE continuous liquid-glass strip, placed on the RIGHT of the inventory exactly like 1.18.2 (S9P3,
 * user: 藥水欄應顯示在右邊且不能與背包打架).
 *
 * <p>1.15.2 has no {@code EffectsInInventory} helper: {@code AbstractInventoryScreen} draws the
 * effect list itself (yarn 1.15.2+build.17, javap-verified):
 * <ul>
 *   <li>{@code applyStatusEffectOffset()} — with effects, shifts the inventory RIGHT
 *       ({@code x = 160 + (width - containerWidth - 200) / 2}) and sets {@code offsetGuiForEffects};</li>
 *   <li>{@code drawStatusEffects()} — {@code i = this.x - 124} ({@code istore_1}); then the three
 *       passes, all fed that same {@code i};</li>
 *   <li>{@code drawStatusEffectBackgrounds(int x, int spacing, Iterable)} — one {@code 140 x 32}
 *       {@code blit} per entry from {@code this.y}, {@code y += spacing};</li>
 *   <li>{@code drawStatusEffectSprites} / {@code drawStatusEffectDescriptions} — icons / name + time.</li>
 * </ul>
 *
 * <h3>Side + no-overlap</h3>
 * The shift is undone at {@code applyStatusEffectOffset} RETURN (the inventory stays CENTRED) and
 * {@code i} is rewritten to {@code x + containerWidth + 2}, so the whole column (all three passes)
 * moves to the right of the panel and can never overlap it. When the right space cannot fit the
 * 140-px column the strip becomes a COMPACT icon-only column ({@link #COMPACT_W} wide, the name/time
 * pass suppressed).
 *
 * <p>At {@code drawStatusEffectBackgrounds} HEAD one strip for the whole list is enqueued (uniform
 * width, separators at each entry top, hotbar corner radius) and the per-box {@code blit} is
 * redirected to a no-op; icons and text draw on top unchanged. When glass is unusable the vanilla
 * boxes draw as before.
 */
@Mixin(AbstractInventoryScreen.class)
public abstract class EffectsInInventoryGlassMixin {

    /** Fixed 1.15.2 effect-box width (the {@code blit} width argument in drawStatusEffectBackgrounds). */
    @Unique private static final int S1MP1E_BOX_W = 140;
    /** Compact icon-only column width: vanilla's icon at x+6, 18 wide, +6 right margin. */
    @Unique private static final int COMPACT_W = 30;

    /** {@code AbstractInventoryScreen.offsetGuiForEffects} — true when the effect column is drawn. */
    @Shadow protected boolean offsetGuiForEffects;

    /** True while drawStatusEffectBackgrounds runs with the glass strip enqueued: per-box blits are skipped. */
    @Unique private boolean s1mp1e$stripActive;
    /** Right space too narrow for the wide column: compact icon-only column, name/time hidden. */
    @Unique private boolean s1mp1e$compact;
    /** The strip was actually enqueued this drawStatusEffects call (persists across its passes). */
    @Unique private boolean s1mp1e$stripDrawn;

    @Unique private ContainerScreenTopAccessor s1mp1e$acc() { return (ContainerScreenTopAccessor) this; }

    // ---- keep the inventory CENTRED (undo vanilla's effect shift) ----------------------------------
    @Inject(method = "applyStatusEffectOffset", at = @At("RETURN"))
    private void s1mp1e$unshift(CallbackInfo ci) {
        if (!this.offsetGuiForEffects) return;   // no effects: vanilla already centred it
        ContainerScreenTopAccessor a = s1mp1e$acc();
        int width = ((Screen) (Object) this).width;
        a.s1mp1e$setLeft((width - a.s1mp1e$xSize()) / 2);
    }

    // ---- relocate the effect column to the RIGHT of the (centred) inventory -----------------------
    @ModifyVariable(method = "drawStatusEffects", at = @At("STORE"), index = 1)
    private int s1mp1e$moveRight(int i) {
        ContainerScreenTopAccessor a = s1mp1e$acc();
        int right = a.s1mp1e$left() + a.s1mp1e$xSize() + 2;
        int width = ((Screen) (Object) this).width;
        this.s1mp1e$compact = right + S1MP1E_BOX_W > width - 2;
        this.s1mp1e$stripDrawn = false;
        return right;
    }

    // ---- hide the name/time pass in the compact column ---------------------------------------------
    @Inject(method = "drawStatusEffectDescriptions", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$compactNoText(int x, int spacing, Iterable<?> effects, CallbackInfo ci) {
        if (this.s1mp1e$compact && this.s1mp1e$stripDrawn) ci.cancel();
    }

    @Inject(method = "drawStatusEffectBackgrounds", at = @At("HEAD"))
    private void s1mp1e$glassStrip(int x, int spacing, Iterable<?> effects, CallbackInfo ci) {
        this.s1mp1e$stripActive = false;
        if (effects == null) return;
        int count = 0;
        for (Object ignored : effects) count++;
        if (count <= 0) return;
        int top = s1mp1e$acc().s1mp1e$top();
        int bottom = top + (count - 1) * spacing + 32;
        float fade = ScreenOpenFade.value(this);
        int w = this.s1mp1e$compact ? COMPACT_W : S1MP1E_BOX_W;
        this.s1mp1e$stripActive = GlassEffects.strip(x, top, x + w, bottom, spacing, count, fade);
        this.s1mp1e$stripDrawn = this.s1mp1e$stripActive;
    }

    @Inject(method = "drawStatusEffectBackgrounds", at = @At("RETURN"))
    private void s1mp1e$glassStripEnd(int x, int spacing, Iterable<?> effects, CallbackInfo ci) {
        this.s1mp1e$stripActive = false;
    }

    @Redirect(
        method = "drawStatusEffectBackgrounds",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screen/ingame/AbstractInventoryScreen;blit(IIIIII)V"
        )
    )
    private void s1mp1e$glassEffectBox(AbstractInventoryScreen self, int x, int y, int u, int v, int w, int h) {
        if (this.s1mp1e$stripActive) return;   // the whole list is one glass strip
        self.blit(x, y, u, v, w, h);             // glass unusable: draw the vanilla box unchanged
    }
}
