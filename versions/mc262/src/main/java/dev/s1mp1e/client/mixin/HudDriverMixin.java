package dev.s1mp1e.client.mixin;

import java.util.List;

import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.ModuleManager;
import dev.s1mp1e.client.S1mp1eHudCtx;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The 26.2 replacement for mc1211's Fabric {@code HudRenderCallback}: once per frame, fans the HUD pass out to
 * every enabled {@link HudRenderer} module.
 *
 * <p>26.2's HUD is a two-phase extract→render pipeline, so modules don't draw pixels — they ENQUEUE
 * {@code g.fill}/{@code g.text}/glass render-states into the frame's {@code GuiRenderState}, which
 * {@code GuiRenderer.render()} flushes later (after {@code GuiRendererGrabMixin} has grabbed the backdrop the
 * glass refracts). We inject at {@code Hud.extractHotbarAndDecorations} HEAD:
 * <ul>
 *   <li>the pose is the clean HUD base there — the recovered {@code HudHotbarMixin} pushes its 7px decoration
 *       lift LATER in the same method and pops it at RETURN, so hooking HEAD avoids racing that pop;</li>
 *   <li>modules compute absolute scaled-GUI coords, so a clean base pose is exactly what they need;</li>
 *   <li>none of the HUD modules overlap the hotbar, so being enqueued before it costs no layering.</li>
 * </ul>
 * Each module runs inside its own pose push/pop, so one that leaves the stack unbalanced (or throws) can't
 * shift the next. {@link Hud#isHidden()} is F1 — honoured here so every module hides with the vanilla HUD.
 *
 * <p>Fair play: this only dispatches drawing; it reads the local camera player and nothing else.
 */
@Mixin(Hud.class)
public abstract class HudDriverMixin {

    @Shadow protected abstract Player getCameraPlayer();
    @Shadow public abstract Font getFont();
    @Shadow public abstract boolean isHidden();

    @Inject(method = "extractHotbarAndDecorations", at = @At("HEAD"))
    private void s1mp1e$driveHud(GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
        if (this.isHidden()) return;
        Player player = this.getCameraPlayer();
        if (player == null) return;

        S1mp1eHudCtx ctx = new S1mp1eHudCtx(g, this.getFont(), delta, player);
        List<Module> all = ModuleManager.all();
        for (int i = 0; i < all.size(); i++) {
            Module m = all.get(i);
            if (!m.enabled || !(m instanceof HudRenderer hr)) continue;
            g.pose().pushMatrix();
            try {
                hr.renderHud(ctx);
            } catch (Throwable t) {
                // one bad module never breaks the HUD pass — but log the first failure so it isn't invisible
                dev.s1mp1e.client.ErrorOnce.report("HUD module " + m.name, t);
            } finally {
                g.pose().popMatrix();
            }
        }
    }
}
