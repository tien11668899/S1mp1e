package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassScreenPanels;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.StatsScreen;
import net.minecraft.client.gui.widget.EntryListWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Injects at the seam right AFTER the vanilla dirt quad flush and before the rows.

/**
 * PORT_SPEC feature (A) — the statistics screen becomes a glass plate + grey readability scrim.
 * 1.14.4 counterpart of 26.2's {@code StatsScreenGlassMixin}.
 *
 * <p><b>Why the shared list widget, not {@code StatsScreen}.</b> 26.2 cancels the stats screen's own
 * {@code extractMenuBackground}. 1.14.4 has no equivalent: {@code StatsScreen.render}'s main branch
 * never calls {@code renderBackground()} — the visible dark background is the tiled dirt quad that
 * the shared {@code EntryListWidget} draws behind its rows (its {@code renderBackground()} hook is
 * empty; javap-verified). So the only place to replace that dirt is inside
 * {@code EntryListWidget.render}. To avoid regressing every OTHER list screen (controls, language,
 * video...) the injection is GATED on {@code minecraft.currentScreen instanceof StatsScreen}.
 *
 * <p>The seam: {@code render} draws the dirt quad ({@code Tessellator.draw()}), then
 * {@code renderList(IIIIF)}. {@code @Inject} just before {@code renderList} overpaints the dirt with
 * one refracting glass plate over the list bounds + the grey scrim (0x0E0E14 @ 0xB4); the stat rows,
 * header, scrollbar and the screen's title / tabs / buttons then draw on top, unchanged. Fresh
 * {@code grabNow()} inside the helper (rule R4). When glass is unusable the vanilla dirt stays.
 */
@Mixin(EntryListWidget.class)
public abstract class StatsListGlassMixin {

    @Shadow protected int top;
    @Shadow protected int bottom;
    @Shadow protected int left;
    @Shadow protected int right;

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/Tessellator;draw()V",
            ordinal = 0,
            shift = At.Shift.AFTER
        )
    )
    private void s1mp1e$statsGlass(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!(mc.currentScreen instanceof StatsScreen)) return;   // scope to the stats screen only
        GlassScreenPanels.stats(mc.currentScreen, this.left, this.top, this.right, this.bottom);
    }
}
