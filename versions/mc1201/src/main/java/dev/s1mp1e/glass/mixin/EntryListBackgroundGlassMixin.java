package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.StatsScreen;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Drops the tiled dirt background an {@link EntryListWidget} paints — but ONLY while the {@link StatsScreen} is open and
 * the glass pipeline is up — so {@code StatsScreenGlassMixin}'s glass plate + grey scrim show through. Every other list
 * (options menus, world/server lists, …) keeps its vanilla dirt untouched: the redirect is a strict pass-through unless
 * the current screen is the stats screen.
 */
@Mixin(EntryListWidget.class)
public abstract class EntryListBackgroundGlassMixin {

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/DrawContext;"
                            + "drawTexture(Lnet/minecraft/util/Identifier;IIFFIIII)V"))
    private void s1mp1e$dropStatsDirt(DrawContext context, Identifier tex, int x, int y, float u, float v,
                                      int w, int h, int tw, int th) {
        if (GlassProgram.ensureReady() && GlassProgram.usable()
                && MinecraftClient.getInstance().currentScreen instanceof StatsScreen) {
            return;   // stats screen is glass now
        }
        context.drawTexture(tex, x, y, u, v, w, h, tw, th);
    }
}
