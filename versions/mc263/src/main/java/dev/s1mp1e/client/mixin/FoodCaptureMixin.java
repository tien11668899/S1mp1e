package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.ModuleManager;
import dev.s1mp1e.client.module.HungerSaturationHudModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the food bar's TRUE on-screen rectangle and paints {@link HungerSaturationHudModule}'s saturation
 * glint on top of it. 26.2 port of mc1211 {@code InGameHudFoodMixin}.
 *
 * <p>Target {@code private void Hud.extractFood(GuiGraphicsExtractor, Player, int yLineBase, int xRight)}
 * (javap-verified against the 26.2 client jar). The logical rect is {@code [xRight-81, yLineBase] ..
 * [xRight, yLineBase+9]} (10 icons, {@code xo = xRight - i*8 - 9}, 9 px tall). The recovered
 * {@code HudHotbarMixin} lifts the decorations through {@code g.pose()} (a {@code Matrix3x2fStack}), so both
 * corners are transformed through that LIVE pose — no hard-coded lift.
 *
 * <p>Injected at RETURN (not HEAD as in mc1211) so the glint is enqueued AFTER the food sprites: 26.2's
 * {@code GuiRenderState} layers an element above earlier intersecting elements, so this is what keeps the
 * glint on top of the drumsticks. Pure observe + extra overlay; never cancels vanilla.
 */
@Mixin(Hud.class)
public abstract class FoodCaptureMixin {

    @Inject(method = "extractFood", at = @At("RETURN"))
    private void s1mp1e$captureFood(GuiGraphicsExtractor g, Player player, int yLineBase, int xRight, CallbackInfo ci) {
        Module m = ModuleManager.byName("HungerHUD");
        if (!(m instanceof HungerSaturationHudModule h) || !h.enabled) return;
        try {
            Vector2f a = g.pose().transformPosition(xRight - 81f, (float) yLineBase, new Vector2f());
            Vector2f b = g.pose().transformPosition((float) xRight, yLineBase + 9f, new Vector2f());
            HungerSaturationHudModule.captureFood(a.x, a.y, b.x, b.y);
            h.paintOnFood(g);
        } catch (Throwable t) {
            dev.s1mp1e.client.ErrorOnce.report("HungerHUD paint", t);
        }
    }
}
