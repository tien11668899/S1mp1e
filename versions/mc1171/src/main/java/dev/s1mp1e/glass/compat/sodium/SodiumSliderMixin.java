package dev.s1mp1e.glass.compat.sodium;

import dev.s1mp1e.glass.compat.SodiumGlass;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells {@link SodiumGlass} whether the integer slider element's thumb is being dragged, so the glass thumb morphs
 * into a refracting lens while it is held and the slider stays out although the cursor left the row. (The element
 * class is package-private.)
 *
 * <p>Sodium 0.4.4 has no {@code sliderHeld} flag (0.5 added it): its {@code mouseClicked} sets the value and returns
 * true when the press was on the slider, and {@code mouseDragged} keeps following while the pointer stays inside. So
 * the flag is kept here: set when {@code mouseClicked} accepted a press, dropped as soon as the left button is up
 * (the element gets no release event of its own). {@code mouseClicked} is an {@code Element} method — intermediary
 * {@code method_25402} in a production jar, the yarn name in the dev runtime — so both names are listed.
 */
@Mixin(targets = "me.jellysquid.mods.sodium.client.gui.options.control.SliderControl$Button", remap = false)
public abstract class SodiumSliderMixin implements SodiumGlass.SliderRow {

    @Unique private boolean s1mp1e$held;

    @Inject(method = {"mouseClicked", "method_25402"}, at = @At("RETURN"))
    private void s1mp1e$press(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (button == 0 && Boolean.TRUE.equals(cir.getReturnValue())) this.s1mp1e$held = true;
    }

    @Override
    public boolean s1mp1e$held() {
        if (!this.s1mp1e$held) return false;
        long window = MinecraftClient.getInstance().getWindow().getHandle();
        if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) this.s1mp1e$held = false;
        return this.s1mp1e$held;
    }
}
