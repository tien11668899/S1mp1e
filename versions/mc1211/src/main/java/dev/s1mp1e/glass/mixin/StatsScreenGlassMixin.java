package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.StatsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feature A — the Statistics screen becomes ONE full-screen liquid-glass plate + a grey readability scrim (26.2's
 * {@code StatsScreenGlassMixin}: {@code extractMenuBackground} HEAD → plate + scrim {@code 0x0E0E14 @ 0xB4}, cancel).
 *
 * <h3>Seam (verified from the decompiled 1.21.1 {@code Screen} / {@code StatsScreen})</h3>
 * {@code StatsScreen} does not override {@code render}/{@code renderBackground}; {@code Screen.render} calls
 * {@code renderBackground(ctx, mouseX, mouseY, delta)} = (panorama when world-less) + {@code applyBlur} +
 * {@code renderDarkening}. This injects that method's HEAD for the stats screen only (a strict pass-through for every
 * other screen), draws the plate over the in-world frame with a fresh backdrop (R4) and cancels vanilla's blur +
 * darkening. The 1.21 list chrome that replaced the old dirt (menu-list background + header/footer separators) is
 * dropped by {@code EntryListBackgroundGlassMixin}, scoped to this screen. Fades in with {@link ScreenOpenFade}
 * (150 ms, synced with the glass buttons). Pipeline down / no world: vanilla untouched.
 */
@Mixin(Screen.class)
public abstract class StatsScreenGlassMixin {

    /** Spec grey scrim: RGB 0x0E0E14 at alpha 0xB4 (the stat rows are many and small). */
    private static final int STATS_SCRIM = 0x0E0E14, STATS_SCRIM_A = 0xB4;

    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$statsPlate(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!((Object) this instanceof StatsScreen)) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || !GlassProgram.ensureReady() || !GlassProgram.usable()) return;
        Screen self = (Screen) (Object) this;
        float fade = ScreenOpenFade.value(self);
        ctx.draw();
        SceneCapture.grabNow();   // frame-primary plate: fresh backdrop (R4)
        GlassRenderer.panel(0, 0, self.width, self.height, fade);
        if (GlassProgram.roundUsable()) {
            int a = Math.round(STATS_SCRIM_A * fade) & 0xFF;
            GlassRenderer.roundRect(0, 0, self.width, self.height, Math.min(self.width, self.height) * 0.0475f,
                    (a << 24) | STATS_SCRIM);
        }
        ci.cancel();
    }
}
