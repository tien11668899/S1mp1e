package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.StatsScreen;
import net.minecraft.client.gui.widget.EntryListWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops the list chrome an {@link EntryListWidget} paints — the 1.21 menu-list background texture and the header /
 * footer separator strips (the successors of the old tiled dirt) — but ONLY while the {@link StatsScreen} is open in a
 * world and the glass pipeline is up, so {@code StatsScreenGlassMixin}'s glass plate + grey scrim show through. Every
 * other list (options, world/server lists, social, ...) keeps its vanilla chrome: both hooks are strict pass-throughs.
 */
@Mixin(EntryListWidget.class)
public abstract class EntryListBackgroundGlassMixin {

    private static boolean s1mp1e$statsGlass() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.currentScreen instanceof StatsScreen && mc.world != null
                && GlassProgram.ensureReady() && GlassProgram.usable();
    }

    @Inject(method = "drawMenuListBackground", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$dropListBackground(DrawContext ctx, CallbackInfo ci) {
        if (s1mp1e$statsGlass()) ci.cancel();
    }

    @Inject(method = "drawHeaderAndFooterSeparators", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$dropSeparators(DrawContext ctx, CallbackInfo ci) {
        if (s1mp1e$statsGlass()) ci.cancel();
    }
}
