package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.GlassGlideHost;
import dev.s1mp1e.client.gui.ScrollDragOwner;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sub-pixel container-grid glide (D), shared at the {@code HandledScreen} level because the creative item grid is drawn
 * by inherited slot code ({@code HandledScreen.render} → {@code drawSlot}). Every hook is a strict pass-through unless
 * the screen is a {@link GlassGlideHost} that reports it is mid-glide this frame — the only such screen is the creative
 * inventory ({@code CreativeGlassMixin}), so every other container runs the untouched vanilla path.
 *
 * <p>While the host is gliding:
 * <ul>
 *   <li>vanilla's own drawing of the scrolling grid slots is skipped ({@link #s1mp1e$slot}) so the host's eased overlay
 *       is the only item content in the window;</li>
 *   <li>vanilla's hover test of those slots returns false ({@link #s1mp1e$hover}) — no {@code focusedSlot}, so no
 *       mismatched highlight / tooltip on a row that is visibly between positions;</li>
 *   <li>the host's eased overlay is drawn just before {@code drawForeground} ({@link #s1mp1e$overlay}), inside the same
 *       {@code x/y} model-view translate the slots use.</li>
 * </ul>
 * Seams (1.17.1 bytecode): {@code render} invokes {@code drawSlot(MatrixStack,Slot)} (private, invokevirtual, offset
 * 132), {@code isPointOverSlot(Slot,DD)} (offset 142) and {@code drawForeground(MatrixStack,II)} (offset 198) — all
 * inside the {@code RenderSystem.getModelViewStack().translate(x,y)} block.
 *
 * <p>Also: {@link #s1mp1e$release} clears the list screens' vanilla scrollbar-drag flag on {@code mouseReleased}
 * ({@link ScrollDragOwner}) so the held glass lens drops when the button is let go.
 */
@Mixin(HandledScreen.class)
public abstract class ContainerGlideGlassMixin {

    /** Skip vanilla's own drawing of a scrolling grid slot while the host draws that content itself. */
    @WrapOperation(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;"
                            + "drawSlot(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/screen/slot/Slot;)V"))
    private void s1mp1e$slot(HandledScreen<?> self, MatrixStack matrices, Slot slot, Operation<Void> original) {
        if (self instanceof GlassGlideHost && ((GlassGlideHost) self).s1mp1e$gliding()
                && ((GlassGlideHost) self).s1mp1e$isGlideSlot(slot)) {
            return;   // suppressed: the host's eased overlay draws this content
        }
        original.call(self, matrices, slot);
    }

    /** No hovered grid slot mid-glide (the drawn rows are between positions). */
    @WrapOperation(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;"
                            + "isPointOverSlot(Lnet/minecraft/screen/slot/Slot;DD)Z"))
    private boolean s1mp1e$hover(HandledScreen<?> self, Slot slot, double px, double py, Operation<Boolean> original) {
        if (self instanceof GlassGlideHost && ((GlassGlideHost) self).s1mp1e$gliding()
                && ((GlassGlideHost) self).s1mp1e$isGlideSlot(slot)) {
            return false;
        }
        return original.call(self, slot, px, py);
    }

    /** Draw the host's eased grid overlay in the same translated model-view matrix vanilla drew the slots in. */
    @Inject(method = "render",
            at = @At(value = "INVOKE", shift = At.Shift.BEFORE,
                     target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;"
                            + "drawForeground(Lnet/minecraft/client/util/math/MatrixStack;II)V"))
    private void s1mp1e$overlay(MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if ((Object) this instanceof GlassGlideHost && ((GlassGlideHost) (Object) this).s1mp1e$gliding()) {
            ((GlassGlideHost) (Object) this).s1mp1e$drawGlideOverlay(matrices);
        }
    }

    /** Drop the list screens' vanilla scrollbar-drag flag when the button is released (held glass lens ends). */
    @Inject(method = "mouseReleased", at = @At("HEAD"))
    private void s1mp1e$release(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ScrollDragOwner) ((ScrollDragOwner) (Object) this).s1mp1e$endScrollDrag();
    }
}
