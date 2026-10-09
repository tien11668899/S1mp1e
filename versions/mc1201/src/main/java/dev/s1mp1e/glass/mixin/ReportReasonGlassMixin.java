package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.AllGlass;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.report.AbuseReportReasonScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The report-reason description box (black rect + border) becomes the frosted text-field look, no hard border. 1.20.1's
 * {@code render} has a single {@code fill} (the box); it becomes a scrim.
 */
@Mixin(AbuseReportReasonScreen.class)
public abstract class ReportReasonGlassMixin {

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V"))
    private void s1mp1e$box(DrawContext ctx, int x0, int y0, int x1, int y1, int argb, Operation<Void> original) {
        if (x1 - x0 > 8 && y1 - y0 > 8) AllGlass.scrim(ctx, x0, y0, x1, y1, 4f, 0x2EFFFFFF);
    }
}
