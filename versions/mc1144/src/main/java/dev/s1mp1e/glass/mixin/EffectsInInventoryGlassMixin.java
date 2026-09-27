package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.glass.render.GlassEffects;
import net.minecraft.client.gui.screen.ingame.AbstractInventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PORT_SPEC feature (F) — the status-effect boxes beside the survival / creative inventory become
 * ONE continuous liquid-glass strip. The 1.14.4 Fabric counterpart of 26.2's
 * {@code EffectsInInventoryGlassMixin} (which targets the extracted {@code EffectsInInventory}).
 *
 * <p>1.14.4 has no {@code EffectsInInventory} helper: {@code AbstractInventoryScreen} draws the
 * effect list itself. From javap:
 * <ul>
 *   <li>{@code drawStatusEffects()} — {@code i = this.x - 124}; {@code spacing = 33} (or
 *       {@code 132/(n-1)} above five effects); sorts the list; then calls the three passes;</li>
 *   <li>{@code method_18642(int x, int spacing, Iterable effects)} — binds the background texture
 *       and {@code blit(x, y, 0, 166, 140, 32)}s one {@code 140 x 32} box per entry, {@code y +=
 *       spacing} (the ONLY {@code blit(IIIIII)} in this method);</li>
 *   <li>{@code method_18643} — effect icons ({@code blit(...,Sprite)}, different descriptor);</li>
 *   <li>{@code method_18644} — name + remaining-time text.</li>
 * </ul>
 *
 * <p>At {@code method_18642} HEAD — before any box is blitted — one strip for the whole list is
 * enqueued (uniform width 140, height {@code (n-1)*spacing + 32}, hotbar corner radius, separators
 * at each entry top) and a flag is set. The per-box {@code blit(IIIIII)} is then {@code @Redirect}ed
 * to a no-op while the flag is set. Icons ({@code method_18643}) and text ({@code method_18644})
 * run afterwards and draw on top, unchanged; positions and hit areas are untouched. When glass is
 * unusable the strip returns {@code false}, the flag stays clear, and the redirect draws the
 * vanilla box as before so nothing disappears.
 *
 * <p>1.14.4's effect list has no per-frame blink (that lives in 1.8.9/1.12.2's box drawer), so a
 * background-only swap cannot break one.
 */
@Mixin(AbstractInventoryScreen.class)
public abstract class EffectsInInventoryGlassMixin {

    /** Fixed 1.14.4 effect-box width (the {@code blit} width argument in method_18642). */
    @Unique private static final int S1MP1E_BOX_W = 140;

    /** True while method_18642 runs with the glass strip enqueued: per-box sprites are then skipped. */
    @Unique private boolean s1mp1e$stripActive;

    @Inject(method = "method_18642", at = @At("HEAD"))
    private void s1mp1e$glassStrip(int x, int spacing, Iterable<?> effects, CallbackInfo ci) {
        this.s1mp1e$stripActive = false;
        if (effects == null) return;
        int count = 0;
        for (Object ignored : effects) count++;
        if (count <= 0) return;
        int top = ((ContainerScreenTopAccessor) this).s1mp1e$top();
        int bottom = top + (count - 1) * spacing + 32;
        float fade = ScreenOpenFade.value(this);
        this.s1mp1e$stripActive =
            GlassEffects.strip(x, top, x + S1MP1E_BOX_W, bottom, spacing, count, fade);
    }

    @Inject(method = "method_18642", at = @At("RETURN"))
    private void s1mp1e$glassStripEnd(int x, int spacing, Iterable<?> effects, CallbackInfo ci) {
        this.s1mp1e$stripActive = false;
    }

    @Redirect(
        method = "method_18642",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screen/ingame/AbstractInventoryScreen;blit(IIIIII)V"
        )
    )
    private void s1mp1e$glassEffectBox(AbstractInventoryScreen self, int x, int y, int u, int v, int w, int h) {
        if (this.s1mp1e$stripActive) {
            return;   // the whole list is one glass strip, enqueued at method_18642 HEAD
        }
        self.blit(x, y, u, v, w, h);   // glass unusable: draw the vanilla box unchanged
    }
}
