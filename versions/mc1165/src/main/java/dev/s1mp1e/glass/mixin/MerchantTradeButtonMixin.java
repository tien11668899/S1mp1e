package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.client.gui.MerchantGlide;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feature D (villager trades): slides each of the 7 vanilla trade-button frames ({@code MerchantScreen$WidgetButtonPage})
 * up by the eased sub-pixel offset while the trade list glides, so the grey trade-row frame stays locked under its
 * (also-gliding) trade items. Every widget that is NOT a registered gliding trade button is a strict pass-through
 * (one static membership test), so all other buttons are untouched.
 *
 * <p>The trade buttons draw at pose identity ({@code Screen.render}), so the trade-window scissor takes absolute GUI
 * coordinates and the {@code RenderSystem} global-matrix translate slides the frame the same fractional amount as the
 * trade-item overlay ({@code MerchantScrollGlassMixin}). {@code MerchantGlide} is armed only for the merchant screen and
 * only while it is mid-glide.
 */
@Mixin(ClickableWidget.class)
public abstract class MerchantTradeButtonMixin {

    @Unique private boolean s1mp1e$glidePushed;

    @Inject(method = "renderButton", at = @At("HEAD"))
    private void s1mp1e$glideIn(MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        s1mp1e$glidePushed = false;
        ClickableWidget self = (ClickableWidget) (Object) this;
        if (!MerchantGlide.handles(self)) return;
        // Glass on: ButtonGlassMixin paints the trade capsule itself, slid + clipped through MerchantGlide.render, and
        // cancels renderButton — so this vanilla-sprite translate must not open (its RETURN half would never run).
        if (GlassProgram.ensureReady() && GlassProgram.btnUsable()) return;
        GlassWidgets.beginScissor(MerchantGlide.scissorX0(), MerchantGlide.scissorY0(),
                MerchantGlide.scissorX1(), MerchantGlide.scissorY1());
        RenderSystem.pushMatrix();
        RenderSystem.translatef(0f, -MerchantGlide.fracPx(), 0f);
        s1mp1e$glidePushed = true;
    }

    @Inject(method = "renderButton", at = @At("RETURN"))
    private void s1mp1e$glideOut(MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!s1mp1e$glidePushed) return;
        s1mp1e$glidePushed = false;
        RenderSystem.popMatrix();
        GlassWidgets.endScissor();
    }
}
