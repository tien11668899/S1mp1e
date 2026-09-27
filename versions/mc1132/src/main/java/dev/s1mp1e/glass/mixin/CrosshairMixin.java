package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.ModuleManager;
import dev.s1mp1e.client.module.CrosshairModule;
import dev.s1mp1e.glass.compat.Mc1132;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the vanilla crosshair with {@link CrosshairModule}'s custom shape (1.13.2 port).
 *
 * <p>On 1.13.2 the crosshair is the private, UNMAPPED {@code InGameHud.method_18366(F)V} (it takes
 * the tick delta; javap-verified against legacy yarn 1.13.2+build.604-v2). {@code render(F)V} invokes
 * it exactly once (right after binding {@code GUI_ICONS_TEXTURE} and enabling blend + alpha test) and
 * sets {@code blendFuncSeparate} after it. We cancel it at HEAD and delegate. Vanilla's screen-open /
 * F1 hiding is inherited for free ({@code render(F)} only reaches it when the HUD is shown); only the
 * first-person gate that the HEAD cancel skips ({@code options.perspective == 0}) is re-added.
 *
 * <p><b>FAIR PLAY.</b> This hook never reads {@code targetedEntity}, {@code result} or any hit
 * result. Vanilla's own body reads them (the spectator block-entity/entity check and the attack
 * indicator); the HEAD cancel skips those reads too, so the custom crosshair is a pure function of
 * its settings and the screen centre. The centre comes from the {@link Mc1132} window bridge
 * ({@code class_4117.method_18321/method_18322}, the same scaled size {@code InGameHud.render} reads).
 */
@Mixin(InGameHud.class)
public class CrosshairMixin {

    @Unique private static boolean s1mp1e$failed;

    @Inject(method = "method_18366(F)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$crosshair(float tickDelta, CallbackInfo ci) {
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
            if (mc == null || mc.player == null || mc.options == null || Mc1132.window() == null) return;
            if (mc.options.perspective != 0) return;         // F5: vanilla shows none -> no-op
            int cx = Mc1132.scaledW() / 2;
            int cy = Mc1132.scaledH() / 2;
            ci.cancel();
            ch.draw(cx, cy);                                  // balances its own matrix push/pop
        } catch (Throwable t) {
            s1mp1e$failed = true;
            System.out.println("[S1mp1e] Crosshair: disabled after render error: " + t);
        }
    }
}
