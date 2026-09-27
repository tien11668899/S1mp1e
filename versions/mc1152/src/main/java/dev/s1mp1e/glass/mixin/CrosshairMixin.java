package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.ModuleManager;
import dev.s1mp1e.client.module.CrosshairModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the vanilla crosshair with {@link CrosshairModule}'s custom shape. Cancels
 * {@code InGameHud.renderCrosshair()V} (private, no args on 1.15.2 — javap-verified in yarn
 * 1.15.2+build.17) at HEAD and delegates. Vanilla's screen-open / F1 hiding is inherited for
 * free ({@code render(F)} doesn't reach renderCrosshair then); only the first-person gate that
 * the HEAD-cancel skips ({@code options.perspective == 0}) is re-added.
 *
 * <p><b>FAIR PLAY.</b> This hook never reads {@code crosshairTarget}, {@code targetedEntity} or
 * any hit result. The HEAD cancel also skips vanilla's own later reads of them (the spectator
 * check and the attack-indicator branch), so the custom crosshair is a pure function of its
 * settings and the screen centre. {@code InGameHud.scaledWidth/scaledHeight} are private on
 * 1.15.2, so the centre comes from {@code mc.getWindow()}.
 */
@Mixin(InGameHud.class)
public class CrosshairMixin {

    @Unique private static boolean s1mp1e$failed;

    @Inject(method = "renderCrosshair()V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$crosshair(CallbackInfo ci) {
        if (s1mp1e$failed) return;                           // disabled after an error: vanilla draws
        try {
            // Combat ring / hit marker go in BEFORE the crosshair (vanilla sprite or the custom shape below), so the
            // crosshair always stays on top of them.
            try {
                dev.s1mp1e.client.module.AttackRingModule.drawUnderCrosshair();
                dev.s1mp1e.client.module.HitMarkerModule.drawUnderCrosshair();
            } catch (Throwable t) {
                System.out.println("[S1mp1e] combat under-crosshair draw failed: " + t);
            }
            Module m = ModuleManager.byName("Crosshair");
            if (!(m instanceof CrosshairModule) || !m.enabled) return;   // vanilla draws
            CrosshairModule ch = (CrosshairModule) m;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.player == null || mc.options == null) return;
            Window w = mc.getWindow();
            if (w == null) return;
            if (mc.options.perspective != 0) return;         // F5: vanilla shows none -> no-op
            ci.cancel();
            int cx = w.getScaledWidth() / 2;
            int cy = w.getScaledHeight() / 2;
            ch.draw(cx, cy);                                  // balances its own matrix push/pop
        } catch (Throwable t) {
            s1mp1e$failed = true;
            System.out.println("[S1mp1e] Crosshair: disabled after render error: " + t);
        }
    }
}
