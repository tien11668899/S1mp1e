package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.s1mp1e.client.module.ForeignZoomKeys;
import dev.s1mp1e.client.module.ZoomModule;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * "Block other zoom" (yarn 1.16.5 — same source as 1.21.1 / 1.20.1), layer 1 — generic, keyed only on
 * vanilla API. While {@link ZoomModule#blocksForeignZoom()} is true, a key binding that
 * {@link ForeignZoomKeys} classifies as another mod's CAMERA zoom key never reports held
 * ({@code isPressed()} → false) and never reports a click ({@code wasPressed()} → false, queued presses
 * drained). KeyBinding-based zoom mods read their key through exactly these two methods, so the foreign
 * zoom never starts. The binding, {@code options.txt} and the Controls screen are untouched; only the
 * answer handed to the zoom mod changes. Our own zoom reads GLFW directly and is not a KeyBinding.
 *
 * <p>Targets javap-verified on yarn 1.16.5+build.10: {@code public boolean isPressed()},
 * {@code public boolean wasPressed()}, {@code private int timesPressed}, {@code public String getTranslationKey()},
 * {@code public String getCategory()}. Loom's mixin AP writes the refmap.
 *
 * <p>{@code @ModifyReturnValue} (MixinExtras, bundled with Fabric Loader ≥0.15) instead of a cancellable
 * HEAD {@code @Inject}: {@code isPressed} is the hottest method in the input path, and this form allocates
 * nothing and costs one boolean test when the key is not pressed.
 */
@Mixin(KeyBinding.class)
public class ForeignZoomKeyBlockMixin {

    @Shadow private int timesPressed;

    /** 0 = not classified yet, 1 = not a camera-zoom key, 2 = foreign camera-zoom key. Key + category are final. */
    @Unique private byte s1mp1e$zoomKind;

    @ModifyReturnValue(method = "isPressed()Z", at = @At("RETURN"))
    private boolean s1mp1e$blockForeignZoomHeld(boolean pressed) {
        if (!pressed || !ZoomModule.blocksForeignZoom()) return pressed;
        return !s1mp1e$isForeignCameraZoom();
    }

    @ModifyReturnValue(method = "wasPressed()Z", at = @At("RETURN"))
    private boolean s1mp1e$blockForeignZoomClick(boolean clicked) {
        if (!clicked || !ZoomModule.blocksForeignZoom()) return clicked;
        if (!s1mp1e$isForeignCameraZoom()) return true;
        timesPressed = 0;   // drop the rest of the queue too, so blocked presses can't replay when blocking stops
        return false;
    }

    @Unique
    private boolean s1mp1e$isForeignCameraZoom() {
        byte kind = s1mp1e$zoomKind;
        if (kind == 0) {
            KeyBinding self = (KeyBinding) (Object) this;
            String name = self.getTranslationKey();
            String category = self.getCategory();
            boolean zoom;
            try {
                zoom = ForeignZoomKeys.isCameraZoom(name, category);
            } catch (Throwable t) {
                System.out.println("[S1mp1e] Zoom: foreign zoom key check failed for '" + name + "': " + t);
                zoom = false;   // on failure: never block
            }
            kind = zoom ? (byte) 2 : (byte) 1;
            s1mp1e$zoomKind = kind;
            if (zoom) System.out.println("[S1mp1e] Zoom: blocking foreign zoom key '" + name + "' [" + category + "]");
        }
        return kind == 2;
    }
}
