package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.StatsScreen;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Feature A — Statistics screen becomes a full glass plate + grey scrim (the tiled dirt / dim dropped).
 *
 * <p>Verified against yarn 1.19.2+build.28: {@code StatsScreen.render} calls {@code this.renderBackground(MatrixStack)}
 * as its first act (the dim gradient over the paused world), then draws the header text and the stat list widgets. This
 * mixin {@link Redirect}s that {@code renderBackground} call to instead grab a fresh backdrop and paint one full-screen
 * refracting glass plate + a grey readability scrim ({@code 0x0E0E14 @ 0xB4}), so the whole screen reads as liquid
 * glass with the stat lists on top. Falls back to the vanilla dim when the glass pipeline is unavailable.
 */
@Mixin(StatsScreen.class)
public abstract class StatsScreenGlassMixin {

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/StatsScreen;"
                            + "renderBackground(Lnet/minecraft/client/util/math/MatrixStack;)V"))
    private void s1mp1e$glassBackground(StatsScreen self, MatrixStack matrices) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            self.renderBackground(matrices);
            return;
        }
        // World is in the framebuffer (rendered behind the screen); we replace the vanilla dim, so grab now and
        // refract the world, laying our own grey scrim for readability. Screen width/height == the window's scaled
        // size (Screen.init sets them from it), read here to avoid an inherited-field @Shadow.
        Window win = MinecraftClient.getInstance().getWindow();
        int w = win.getScaledWidth(), h = win.getScaledHeight();
        SceneCapture.grabNow();
        GlassRenderer.panel(0, 0, w, h, 1.0f);
        if (GlassProgram.roundUsable())
            GlassRenderer.roundRect(0, 0, w, h, 0f, (0xB4 << 24) | 0x0E0E14);
    }

    /**
     * Disable the selected stat list's own tiled-dirt background so the full-screen glass plate shows through. The
     * dirt is gated by {@code EntryListWidget.renderBackground} (verified: {@code render()} tests that field before
     * drawing {@code OPTIONS_BACKGROUND_TEXTURE}); the edge shadow gradients are left on. Only while glass is up.
     */
    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/StatsScreen;getSelectedStatList()"
                            + "Lnet/minecraft/client/gui/widget/AlwaysSelectedEntryListWidget;"))
    private AlwaysSelectedEntryListWidget<?> s1mp1e$stripListDirt(StatsScreen self) {
        AlwaysSelectedEntryListWidget<?> list = self.getSelectedStatList();
        if (list != null && GlassProgram.ensureReady() && GlassProgram.usable()) list.setRenderBackground(false);
        return list;
    }
}
