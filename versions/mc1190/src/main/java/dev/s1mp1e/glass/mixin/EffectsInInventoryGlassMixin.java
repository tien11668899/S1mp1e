package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassEffects;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.screen.ingame.AbstractInventoryScreen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.effect.StatusEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feature F — the status-effect panel becomes ONE continuous glass strip.
 *
 * <p>1.19.2's {@code AbstractInventoryScreen.drawStatusEffects} lays the effect column to the right of the
 * survival/creative inventory in three passes: {@code drawStatusEffectBackgrounds} (the dark rounded box sprite per
 * entry), {@code drawStatusEffectSprites} (icons) and {@code drawStatusEffectDescriptions} (name + time). Verified
 * against yarn 1.19.2+build.28: {@code drawStatusEffectBackgrounds(MatrixStack, int x, int spacing, Iterable, boolean
 * wide)} draws each box with {@code this.drawTexture(MatrixStack,IIIIII)} — {@code (x, y, 0, 166, 120, 32)} wide or
 * {@code (x, y, 0, 198, 32, 32)} compact — advancing {@code y} by {@code spacing} each entry, from {@code this.y}.
 *
 * <p>This mixin, at that method's HEAD, enqueues ONE {@link GlassEffects#strip} spanning the whole column (uniform
 * width = the vanilla box width, faint separators at each entry top after the first, hotbar corner radius) and sets a
 * flag; a {@link Redirect} on the per-box {@code drawTexture} then no-ops while the flag is set, so the strip fully
 * replaces the boxes. Icons and text draw afterwards (later methods) and stay on top and readable. Chest / plain
 * containers have no effect column, so no strip — correct.
 */
@Mixin(AbstractInventoryScreen.class)
public abstract class EffectsInInventoryGlassMixin {

    /** True for the current {@code drawStatusEffectBackgrounds} call: the glass strip replaced the boxes. */
    private boolean s1mp1e$stripActive;

    @Inject(method = "drawStatusEffectBackgrounds", at = @At("HEAD"))
    private void s1mp1e$effectStrip(MatrixStack matrices, int x, int spacing,
                                    Iterable<StatusEffectInstance> effects, boolean wide, CallbackInfo ci) {
        s1mp1e$stripActive = false;
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;
        int count = 0;
        for (StatusEffectInstance ignored : effects) count++;
        if (count <= 0) return;

        // Reuse the world+dim backdrop already grabbed this frame (HUD render HEAD / the inventory panel). The strip
        // sits over the world to the right of the inventory, so world+dim is exactly what it should refract; never
        // re-grab here (would fold the already-drawn panel/items into the strip's own backdrop).
        if (!SceneCapture.hasBackdrop()) SceneCapture.grabNow();

        int boxW = wide ? 120 : 32;
        int x0 = x, y0 = ((HandledScreenAccessor) this).s1mp1e$y();
        int x1 = x + boxW;
        int y1 = y0 + (count - 1) * spacing + 32;
        s1mp1e$stripActive = GlassEffects.strip(x0, y0, x1, y1, spacing, count, 1.0f);
    }

    @Inject(method = "drawStatusEffectBackgrounds", at = @At("RETURN"))
    private void s1mp1e$effectStripEnd(MatrixStack matrices, int x, int spacing,
                                       Iterable<StatusEffectInstance> effects, boolean wide, CallbackInfo ci) {
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
