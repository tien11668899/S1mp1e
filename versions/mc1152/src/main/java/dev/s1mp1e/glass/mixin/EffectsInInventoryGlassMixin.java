package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.GlassEffects;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.PanelGhost;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.AbstractInventoryScreen;
import net.minecraft.entity.effect.StatusEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feature (F) — the status-effect list beside the survival / creative inventory becomes ONE continuous
 * liquid-glass strip ("A 合成一整條"), like the hotbar stood on its side. The 1.15.2 Fabric port of
 * LiquidGlass26's {@code EffectsInInventoryGlassMixin}.
 *
 * <h3>Side + no-overlap (S9P3, 1.18.2 = reference)</h3>
 * Vanilla 1.15.2 draws effects to the LEFT ({@code x = this.x - 124}) and, in {@code applyStatusEffectOffset},
 * shifts the whole inventory RIGHT to make room ({@code x = 160 + (width - backgroundWidth - 200) / 2}). Both
 * are undone here so the design matches 1.18.2: the inventory stays CENTRED (the shift is reverted at
 * {@code applyStatusEffectOffset} RETURN) and the effect column is relocated to the RIGHT
 * ({@code leftPos + imageWidth + 2}, the {@code i} local of {@code offsetGuiForEffects} is rewritten) so it can
 * never overlap the panel. The one relocate moves all three vanilla passes (backgrounds / sprites / names)
 * together, since they share that {@code i}. When the right space cannot fit the wide 140-px column the strip
 * becomes a COMPACT icon-only column ({@link #COMPACT_W} wide, the name/time pass suppressed). The recipe book
 * still owns {@code this.x} in survival (it runs after {@code applyStatusEffectOffset}), so that case stays
 * exactly like vanilla.
 *
 * <h3>1.15.2 vanilla shape (javap-verified, merged jar 1.15.2+build.10)</h3>
 * {@code AbstractInventoryScreen} (shared by {@code InventoryScreen} and {@code CreativeInventoryScreen}).
 * {@code drawStatusEffectBackgrounds(MatrixStack, int x, int spacing, Iterable)} draws, per entry, one
 * {@code 140 x 32} box at {@code (x, this.y + i*spacing)} — the y origin is {@code this.y}, captured as the
 * FIRST box's {@code y} argument here. {@code drawStatusEffectSprites} blits the 18x18 icon at {@code (x+6,
 * y+7)}; {@code drawStatusEffectDescriptions} draws the name/time to the right, both AFTER this method returns,
 * so the strip stays underneath them.
 *
 * <p>The single plate is drawn on the FIRST box's redirect (before any box background), and every box
 * background is then suppressed, so the list reads as one sheet. The strip is FRAME-PRIMARY, so it takes a
 * fresh {@link SceneCapture#grabNow()} (R4). Icons / text / hit areas are untouched (they follow the moved
 * {@code i}). Tooltip-on-top (R1) is unaffected: the hovered-item tooltip is drawn by the subclass
 * {@code render} after the {@code super.render} that owns {@code offsetGuiForEffects}.
 *
 * <p>When glass is unusable the vanilla per-box sprites are drawn as before.
 */
@Mixin(AbstractInventoryScreen.class)
public abstract class EffectsInInventoryGlassMixin {

    /** Vanilla's wide effect-box width on 1.15.2 ({@code drawStatusEffectBackgrounds} blit). */
    @Unique private static final int BOX_W = 140;
    /** Compact icon-only column width: vanilla's icon at x+6, 18 wide, +6 right margin. */
    @Unique private static final int COMPACT_W = 30;

    /** Vanilla-declared on {@code AbstractInventoryScreen}, so it resolves here (unlike the inherited x/y). */
    @Shadow protected boolean offsetGuiForEffects;

    /** True between HEAD and RETURN of {@code drawStatusEffectBackgrounds} when a glass strip is wanted. */
    @Unique private boolean s1mp1e$want;
    /** True once the single plate has actually been enqueued: per-box blits are then skipped. */
    @Unique private boolean s1mp1e$stripActive;
    /** Which box the per-box redirect is on (the plate is drawn on box 0, before any box background). */
    @Unique private int s1mp1e$boxIdx;
    /** {@code spacing} / entry {@code count} captured at HEAD (the per-box redirect does not receive them). */
    @Unique private int s1mp1e$spacing;
    @Unique private int s1mp1e$count;
    /** Right space too narrow for the wide column: draw the compact icon-only column and hide the name/time. */
    @Unique private boolean s1mp1e$compact;
    /** The glass strip was actually enqueued this {@code offsetGuiForEffects} call (persists across its 3 passes). */
    @Unique private boolean s1mp1e$stripDrawn;
    /** Panel-synced open fade (150 ms) so the strip fades in with the inventory panel. */
    @Unique private float s1mp1e$fade;
    @Unique private Fade s1mp1e$stripFade;

    @Unique private HandledScreenAccessor s1mp1e$acc() { return (HandledScreenAccessor) this; }

    // ---- S9P3: keep the inventory CENTRED (undo vanilla's effect shift) --------------------------------------
    @Inject(method = "applyStatusEffectOffset", at = @At("RETURN"))
    private void s1mp1e$unshift(CallbackInfo ci) {
        if (!this.offsetGuiForEffects) return;   // no effects: vanilla already centred it
        HandledScreenAccessor a = s1mp1e$acc();
        int width = ((Screen) (Object) this).width;
        a.s1mp1e$setX((width - a.s1mp1e$backgroundWidth()) / 2);
    }

    // ---- S9P3: relocate the effect column to the RIGHT of the (centred) inventory ---------------------------
    // Rewrites offsetGuiForEffects' first int local `i` (vanilla: this.x - 124) to leftPos + imageWidth + 2, and
    // decides wide vs compact from the space on the right. Because backgrounds / sprites / descriptions all use
    // this same `i`, the whole column moves as one.
    @ModifyVariable(method = "drawStatusEffects", at = @At("STORE"), index = 1)
    private int s1mp1e$moveRight(int i) {
        HandledScreenAccessor a = s1mp1e$acc();
        int right = a.s1mp1e$x() + a.s1mp1e$backgroundWidth() + 2;
        int width = ((Screen) (Object) this).width;
        this.s1mp1e$compact = right + BOX_W > width - 2;   // no room for the wide column
        this.s1mp1e$stripDrawn = false;                    // reset for this offsetGuiForEffects call
        return right;
    }

    // ---- S9P3: hide the name/time pass in the compact column -----------------------------------------------
    @Inject(method = "drawStatusEffectDescriptions", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$compactNoText(int x, int spacing,
                                      Iterable<StatusEffectInstance> effects, CallbackInfo ci) {
        if (this.s1mp1e$compact && this.s1mp1e$stripDrawn) ci.cancel();
    }

    @Inject(method = "drawStatusEffectBackgrounds", at = @At("HEAD"))
    private void s1mp1e$prep(int x, int spacing,
                             Iterable<StatusEffectInstance> effects, CallbackInfo ci) {
        this.s1mp1e$want = false;
        this.s1mp1e$stripActive = false;
        this.s1mp1e$boxIdx = 0;
        if (effects == null) return;
        int n = 0;
        for (StatusEffectInstance ignored : effects) n++;
        if (n <= 0) return;
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;

        if (this.s1mp1e$stripFade == null) {
            this.s1mp1e$stripFade = new Fade(0f, PanelGhost.FADE_MS);
            this.s1mp1e$stripFade.snap(0f);
        }
        this.s1mp1e$stripFade.to(1f);
        this.s1mp1e$fade = this.s1mp1e$stripFade.value();
        this.s1mp1e$spacing = spacing;
        this.s1mp1e$count = n;
        this.s1mp1e$want = true;
    }

    @Inject(method = "drawStatusEffectBackgrounds", at = @At("RETURN"))
    private void s1mp1e$end(int x, int spacing,
                            Iterable<StatusEffectInstance> effects, CallbackInfo ci) {
        this.s1mp1e$want = false;
        this.s1mp1e$stripActive = false;
    }

    /**
     * The whole effect list is ONE glass plate. On the FIRST box (its {@code y} arg is {@code this.y},
     * the strip top), a fresh {@link SceneCapture#grabNow()} is taken (R4) and the single plate is drawn —
     * before any box background. Every box background is then suppressed. The plate is {@link #COMPACT_W}
     * wide in the compact column, else the vanilla wide box width. If the strip could not be enqueued (glass
     * unusable), the vanilla box is drawn so the panel never disappears.
     */
    @Redirect(method = "drawStatusEffectBackgrounds",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/AbstractInventoryScreen;blit(IIIIII)V"))
    private void s1mp1e$box(AbstractInventoryScreen<?> self, int x, int y, int u, int v, int w, int h) {
        if (!this.s1mp1e$want) {
            self.blit(x, y, u, v, w, h);
            return;
        }
        if (this.s1mp1e$boxIdx == 0) {
            int x0 = x, y0 = y;
            int x1 = x + (this.s1mp1e$compact ? COMPACT_W : BOX_W);
            int y1 = y0 + (this.s1mp1e$count - 1) * this.s1mp1e$spacing + h;
            // 2026-10-04：沿用 HUD 這一幀在 InGameHud.render 開頭拍的「只有世界」背景（26.2 的做法）；在暗色漸層之後重拍會讓整個背包發暗
            if (!SceneCapture.hasBackdrop()) SceneCapture.grabNow();
            this.s1mp1e$stripActive =
                    GlassEffects.strip(x0, y0, x1, y1, this.s1mp1e$spacing, this.s1mp1e$count, this.s1mp1e$fade);
            this.s1mp1e$stripDrawn = this.s1mp1e$stripActive;
        }
        this.s1mp1e$boxIdx++;
        if (this.s1mp1e$stripActive) return;                            // strip drew: suppress this box background
        self.blit(x, y, u, v, w, h);                          // glass unusable: keep the vanilla box
    }
}
