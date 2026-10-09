package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.AllGlass;
import net.minecraft.client.gui.screen.report.AbuseReportReasonScreen;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * #11 — the report-reason description box (black rect + border) becomes the frosted text-field look, no hard border.
 * 1.19.2 {@code render} fills the box with {@code DrawableHelper.fill}; it becomes a scrim (only the large box, not any
 * incidental small fills).
 */
@Mixin(AbuseReportReasonScreen.class)
public abstract class ReportReasonGlassMixin {

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screen/report/AbuseReportReasonScreen;fill(Lnet/minecraft/client/util/math/MatrixStack;IIIII)V"))
    private void s1mp1e$box(MatrixStack matrices, int x0, int y0, int x1, int y1, int argb, Operation<Void> original) {
        if (x1 - x0 > 8 && y1 - y0 > 8) AllGlass.scrim(matrices, x0, y0, x1, y1, 4f, 0x2EFFFFFF);
        else original.call(matrices, x0, y0, x1, y1, argb);
    }
}
