package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassEffects;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.AbstractInventoryScreen;
import net.minecraft.entity.effect.StatusEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The status-effect list beside the survival / creative inventory becomes ONE continuous liquid-glass strip
 * (user choice "A 合成一整條"), like the hotbar stood on its side — 26.2's {@code EffectsInInventoryGlassMixin} ported to
 * 1.20.1, where the effect drawing lives on {@link AbstractInventoryScreen} (shared by the survival + creative inventory;
 * plain containers have none → no panel, correct).
 *
 * <p>1.20.1 draws each effect box in {@code drawStatusEffectBackgrounds} (per entry, uniform 120-wide / 32-compact). We
 * inject at its HEAD, draw the whole list as one strip (uniform width, hotbar corner radius, faint separators) and cancel
 * the per-box loop; the effect icons and name/time text draw afterwards on top, positions/hit areas unchanged. The
 * compact-mode hover tooltip goes through the normal tooltip path, which is promoted to the top layer (E). When glass is
 * unusable we do NOT cancel, so the vanilla per-box sprites still draw.
 */
@Mixin(AbstractInventoryScreen.class)
public abstract class EffectsInInventoryGlassMixin {

    @Inject(method = "drawStatusEffectBackgrounds", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassStrip(DrawContext context, int x, int height,
                                   Iterable<StatusEffectInstance> statusEffects, boolean wide, CallbackInfo ci) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;
        int n = 0;
        for (StatusEffectInstance ignored : statusEffects) n++;
        if (n <= 0) return;
        // Reuse the fresh, undimmed backdrop the inventory panel already grabbed this frame (R4 — never fold onto a
        // stale snapshot; only grab if nothing has been captured yet).
        if (!SceneCapture.hasBackdrop()) SceneCapture.grabNow();
        int width = wide ? 120 : 32;
        int y0 = ((HandledScreenAccessor) (Object) this).s1mp1e$y();
        int y1 = y0 + (n - 1) * height + 32;
        if (GlassEffects.strip(context, x, y0, x + width, y1, height, n, 1.0f)) {
            ci.cancel();   // the whole list is one glass strip
        }
    }
}
