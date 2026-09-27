package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassSurface;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.StatsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Statistics screen → one full-screen liquid-glass plate + a grey readability scrim (26.2's {@code StatsScreenGlassMixin}
 * ported to 1.20.1). The tiled dirt list background + top/bottom shadow strips are dropped by
 * {@code EntryListBackgroundGlassMixin} (scoped to this screen) so the glass shows.
 *
 * <p><b>Injection point (fix):</b> the old build drew at {@code render} HEAD, so the whole thing landed BEFORE the
 * stat-list content — which was fine for z-order but the plate refraction came out black. Root cause: at {@code render}
 * HEAD the frame's world had not been composited into MC's main framebuffer for a plain (non-container) screen in the way
 * a mid-render grab sees it, so the forced {@code grabNow()} captured an empty/dark buffer. We now inject at the
 * {@code AlwaysSelectedEntryListWidget.render} INVOKE (BEFORE) — the exact analogue of {@code AdvancementsGlassMixin},
 * which grabs after the screen dim and refracts correctly — so the backdrop is the real world and the plate refracts it.
 * The stat rows/icons/text then draw on top (their dirt background is dropped), readable over the grey scrim.
 */
@Mixin(StatsScreen.class)
public abstract class StatsScreenGlassMixin {

    @Inject(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/widget/AlwaysSelectedEntryListWidget;"
                            + "render(Lnet/minecraft/client/gui/DrawContext;IIF)V"))
    private void s1mp1e$glassBackground(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;
        Screen self = (Screen) (Object) this;
        // Frame-primary surface: fresh backdrop this frame (R4). The world is already in the framebuffer at this
        // mid-render point (list not drawn yet), so the plate refracts the real world like the advancements window.
        SceneCapture.grabNow();
        boolean ok = GlassSurface.plate(0, 0, self.width, self.height, 1.0f);
        if (!ok) {
            // pipeline down: flat dark fill so the screen is not naked
            GlassSurface.scrim(context, 0, 0, self.width, self.height, 0f, 0x99101014);
        }
        // Grey readability scrim over the glass (spec: RGB 0x0E0E14, alpha 0xB4) so the many small rows stay legible.
        GlassSurface.scrim(context, 0, 0, self.width, self.height, 0f, 0xB40E0E14);
    }
}
