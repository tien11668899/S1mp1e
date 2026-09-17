package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.ModuleManager;
import dev.s1mp1e.client.module.CrosshairModule;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the vanilla crosshair with {@link CrosshairModule}'s custom shape. Cancels the private
 * {@code Hud.extractCrosshair(GuiGraphicsExtractor, DeltaTracker)} at HEAD and delegates. Vanilla's caller
 * ({@code Hud.extractRenderState}) already skips it when F1 hides the HUD or a LevelLoadingScreen is up, so
 * those are inherited; the HEAD-cancel skips vanilla's own gates, which are re-derived here from NON-TARGET
 * state only:
 * <ul>
 *   <li>not first person (F5) → return, vanilla draws nothing;</li>
 *   <li>spectator: NOT gated (parity with mc1211, which drew the custom shape in spectator too). Vanilla's
 *       spectator rule consults the hit result; delegating to it would make the crosshair appear/vanish
 *       with the target, so we intentionally draw unconditionally and never read the hit result;</li>
 *   <li>debug 3D crosshair entry enabled → return, vanilla draws nothing 2D.</li>
 * </ul>
 * Fair play: never reads {@code hitResult} / {@code crosshairPickEntity}.
 */
@Mixin(Hud.class)
public abstract class CrosshairHideMixin {

    @Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$crosshair(GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
        if (!(ModuleManager.byName("Crosshair") instanceof CrosshairModule ch) || !ch.enabled) return; // vanilla draws
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!mc.options.getCameraType().isFirstPerson()) return;                   // F5: vanilla shows none
        if (mc.debugEntries.isCurrentlyEnabled(DebugScreenEntries.THREE_DIMENSIONAL_CROSSHAIR)) return;
        ci.cancel();
        g.nextStratum(); // same layering vanilla uses before blitting its sprite
        g.pose().pushMatrix();
        try {
            ch.draw(g, g.guiWidth() / 2, g.guiHeight() / 2);
        } catch (Throwable t) {
            dev.s1mp1e.client.ErrorOnce.report("Crosshair draw", t);   // never crash the HUD over a crosshair
        } finally {
            g.pose().popMatrix();
        }
    }
}
