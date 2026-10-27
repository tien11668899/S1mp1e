package dev.s1mp1e.glass.mixin;

import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * DevShot only: feeds a mouse movement through vanilla's own path — the accumulated cursor delta, then
 * {@code Mouse.updateMouse} (sensitivity, invert, our Freelook wrap, {@code changeLookDirection}) — without a real
 * mouse, and without the focus / cursor-lock gate of {@code Mouse.tick}, which an automated window may not pass.
 */
@Mixin(Mouse.class)
public interface DevMouseInvoker {
    @Accessor("cursorDeltaX")
    void s1mp1e$setCursorDeltaX(double v);

    @Accessor("cursorDeltaY")
    void s1mp1e$setCursorDeltaY(double v);

    @Invoker("updateMouse")
    void s1mp1e$updateMouse(double timeDelta);
}
