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
 * Feature F — the status-effect panel beside the survival / creative inventory becomes ONE continuous vertical glass
 * strip. 1.21.1 (DrawContext) port of 26.2's {@code EffectsInInventoryGlassMixin} + {@code GlassEffects}.
 *
 * <h3>The seam (verified from 1.21.1 bytecode, yarn 1.21.1+build.3)</h3>
 * {@code AbstractInventoryScreen.drawStatusEffects(DrawContext, int mouseX, int mouseY)} computes
 * {@code x = this.x + backgroundWidth + 2}, {@code wide = (width - x) >= 120}, {@code spacing = 33} (or
 * {@code 132/(size-1)} above five effects), then calls {@code drawStatusEffectBackgrounds(ctx, x, spacing, effects,
 * wide)} — a plain loop drawing {@code effect_background_large} (120x32) per entry when wide, else
 * {@code effect_background_small} (32x32), stepping {@code y += spacing} from {@code this.y}. The icons and text are in
 * the SEPARATE {@code drawStatusEffectSprites}/{@code drawStatusEffectDescriptions} methods, so we can replace the whole
 * background loop with one strip and leave everything else vanilla.
 *
 * <p>We {@code @Inject} {@code drawStatusEffectBackgrounds} at {@code HEAD}, {@code cancellable = true}: draw the strip
 * once and {@code ci.cancel()} to skip the per-box loop. If the glass pipeline is down we return without cancelling, so
 * vanilla draws its boxes (never a missing panel). Chest / plain containers are not {@code AbstractInventoryScreen}s, so
 * they show no panel — correct. Applies to both {@code InventoryScreen} and {@code CreativeInventoryScreen}.
 *
 * <p>Fresh {@code grabNow()} (rule R4): the strip is a frame-primary surface to the right of the inventory that must
 * refract the clean world+dim beneath it. We {@code ctx.draw()} to flush the batched slots/dim into the framebuffer
 * first, then grab, then draw the immediate-GL strip; vanilla's later icon/text batch flushes on top.
 */
@Mixin(AbstractInventoryScreen.class)
public abstract class EffectsInInventoryGlassMixin {

    @Inject(method = "drawStatusEffectBackgrounds("
                   + "Lnet/minecraft/client/gui/DrawContext;IILjava/lang/Iterable;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassEffectStrip(DrawContext ctx, int x, int spacing,
                                         Iterable<StatusEffectInstance> effects, boolean wide, CallbackInfo ci) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;
        int count = 0;
        for (StatusEffectInstance ignored : effects) count++;
        if (count <= 0) return;

        int boxW = wide ? 120 : 32;
        int x0 = x, y0 = ((HandledScreenAccessor) (Object) this).s1mp1e$y();
        int x1 = x0 + boxW;
        int y1 = y0 + (count - 1) * spacing + 32;

        // Flush batched GUI draws (dim, panel, slots, items) so the backdrop contains them, then grab a FRESH copy
        // (grabNow, not the deduped grab) so the strip deterministically refracts the clean world+dim every frame (R4).
        ctx.draw();
        SceneCapture.grabNow();

        // fades in with the panel (26.2: opacity = the panel open fade) — the shared 150 ms screen-open ramp
        float fade = dev.s1mp1e.client.gui.ScreenOpenFade.value(
                net.minecraft.client.MinecraftClient.getInstance().currentScreen);
        if (GlassEffects.strip(ctx, x0, y0, x1, y1, spacing, count, fade)) {
            ci.cancel();
        }
    }
}
