package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.AllGlass;
import net.minecraft.client.gui.hud.SubtitlesHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * #16 — subtitle boxes: square black → rounded dark scrims at vanilla's (fading) background opacity. 1.14.4
 * {@code SubtitlesHud.render()V} draws each box with the inherited static {@code fill(IIIII)V} (owner = SubtitlesHud,
 * javap-verified) inside a {@code pushMatrix/translatef/scalef} — the scrim is drawn in that same GL model-view.
 * Identical to the 1.15.2 mixin (both pre-MatrixStack fixed-pipeline).
 */
@Mixin(SubtitlesHud.class)
public abstract class SubtitlesGlassMixin {

    @WrapOperation(method = "render()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/hud/SubtitlesHud;fill(IIIII)V"))
    private void s1mp1e$round(int x0, int y0, int x1, int y1, int argb, Operation<Void> original) {
        AllGlass.scrim(x0, y0, x1, y1, Math.min(4f, (y1 - y0) / 2f), argb);
        AllGlass.afterFill();
    }
}
