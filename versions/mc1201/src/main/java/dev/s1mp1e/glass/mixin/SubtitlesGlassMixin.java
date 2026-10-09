package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.AllGlass;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.SubtitlesHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Subtitle boxes: square black → rounded dark scrims at vanilla's (fading) opacity (matrix baked by AllGlass). */
@Mixin(SubtitlesHud.class)
public abstract class SubtitlesGlassMixin {

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V"))
    private void s1mp1e$round(DrawContext ctx, int x0, int y0, int x1, int y1, int argb, Operation<Void> original) {
        AllGlass.scrim(ctx, x0, y0, x1, y1, Math.min(4f, (y1 - y0) / 2f), argb);
    }
}
