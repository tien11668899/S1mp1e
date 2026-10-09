package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.world.ExperimentsScreen;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * All-glass #26: the create-world Experiments screen paints its own dark tiled-dirt band between header and footer
 * ({@code render}: one {@code drawTexture(OPTIONS_BACKGROUND_TEXTURE, …)} tinted 0.125) — the same chrome a full-width
 * list draws. Dropped with the glass pipeline up, so the toggles sit on the blurred panorama like every other menu.
 */
@Mixin(ExperimentsScreen.class)
public abstract class ExperimentsBandGlassMixin {

    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Lnet/minecraft/util/Identifier;IIFFIIII)V"))
    private void s1mp1e$noBand(DrawContext ctx, Identifier tex, int x, int y, float u, float v, int w, int h, int tw, int th) {
        if (net.minecraft.client.MinecraftClient.getInstance().world == null
                && GlassProgram.ensureReady() && GlassProgram.usable()) return;   // world-less only (spec #26)
        ctx.drawTexture(tex, x, y, u, v, w, h, tw, th);
    }
}
