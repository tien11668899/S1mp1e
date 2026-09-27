package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.multiplayer.SocialInteractionsScreen;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Feature A — the Social Interactions panel becomes one refracting liquid-glass plate (26.2's {@code SocialGlassMixin}:
 * the nine-slice {@code BACKGROUND_SPRITE}, width at least 120, becomes a plate).
 *
 * <h3>Seam (verified from the decompiled 1.21.1 {@code SocialInteractionsScreen})</h3>
 * {@code renderBackground} = {@code super.renderBackground} (blur + darkening) then
 * {@code drawGuiTexture(BACKGROUND_TEXTURE, x, 64, 236, screenHeight+16)} and the 12x12 search icon
 * {@code drawGuiTexture(SEARCH_ICON_TEXTURE, x+10, 76, 12, 12)}. Both use the same INVOKE descriptor, so the width gate
 * picks the panel; the search icon, tabs, player rows, heads and ping stay vanilla on top. Fresh backdrop (R4), the
 * container-panel material (panel family keeps its radius), {@link ScreenOpenFade} 150 ms.
 */
@Mixin(SocialInteractionsScreen.class)
public abstract class SocialGlassMixin {

    @Redirect(method = "renderBackground",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/DrawContext;drawGuiTexture(Lnet/minecraft/util/Identifier;IIII)V"))
    private void s1mp1e$glassPanel(DrawContext ctx, Identifier tex, int x, int y, int w, int h) {
        if (w < 120 || !GlassProgram.ensureReady() || !GlassProgram.usable()) {
            ctx.drawGuiTexture(tex, x, y, w, h);
            return;
        }
        float fade = ScreenOpenFade.value(MinecraftClient.getInstance().currentScreen);
        ctx.draw();               // land the blur + darkening first
        SceneCapture.grabNow();   // frame-primary panel: fresh backdrop (R4)
        GlassRenderer.panel(x, y, x + w, y + h, fade);
    }
}
