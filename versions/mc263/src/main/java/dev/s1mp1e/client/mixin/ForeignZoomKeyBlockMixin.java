package dev.s1mp1e.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.s1mp1e.client.module.ForeignZoomKeys;
import dev.s1mp1e.client.module.ZoomModule;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * "Block other zoom" (26.2), layer 1 — generic, keyed only on vanilla API. While {@link ZoomModule#blocksForeignZoom()}
 * is true, a key mapping that {@link ForeignZoomKeys} classifies as another mod's CAMERA zoom key never reports held
 * ({@code isDown()} → false) and never reports a click ({@code consumeClick()} → false, queued clicks drained). Every
 * KeyMapping-based zoom mod reads its key through exactly these two methods (bytecode-verified: Essential
 * {@code ZoomHandler.getZoomState} → {@code isDown}; {@code EssentialKeybinding.tickWorld} → {@code consumeClick}/{@code isDown};
 * Zoomify 2.16.1 → {@code isDown}/{@code consumeClick}), so the foreign zoom simply never starts. The binding itself,
 * {@code options.txt}, the Controls screen and every other key on the same physical key are untouched: 26.2's
 * {@code KeyMapping.MAP} is a {@code Map<Key, List<KeyMapping>>}, the {@code isDown} field keeps its real value, and
 * only the answer handed to the zoom mod changes. Our own zoom reads GLFW directly and is not a KeyMapping.
 *
 * <p>Targets, javap-verified against the 26.2 client jar: {@code public boolean isDown()} ({@code ()Z}, a bare
 * {@code getfield isDown; ireturn}), {@code public boolean consumeClick()} ({@code ()Z}, two IRETURNs), field
 * {@code private int clickCount}, {@code public String getName()}, {@code public KeyMapping$Category getCategory()},
 * record accessor {@code KeyMapping$Category.id()} → {@code Identifier} ({@code toString()} = "namespace:path").
 *
 * <p>{@code @ModifyReturnValue} (MixinExtras, bundled with Fabric Loader, already used by {@code XpLevelMixin}) rather
 * than a cancellable HEAD {@code @Inject}: {@code isDown} is the hottest method in the input path (every movement key,
 * every tick, plus other mods), and this form allocates no {@code CallbackInfoReturnable} and costs one boolean test
 * when the key is not down — the classification only ever runs on a real press while blocking is on.
 */
@Mixin(KeyMapping.class)
public class ForeignZoomKeyBlockMixin {

    @Shadow private int clickCount;

    /** 0 = not classified yet, 1 = not a camera-zoom key, 2 = foreign camera-zoom key. Name and category are final. */
    @Unique private byte s1mp1e$zoomKind;

    @ModifyReturnValue(method = "isDown()Z", at = @At("RETURN"))
    private boolean s1mp1e$blockForeignZoomHeld(boolean down) {
        if (!down || !ZoomModule.blocksForeignZoom()) return down;
        return !s1mp1e$isForeignCameraZoom();
    }

    @ModifyReturnValue(method = "consumeClick()Z", at = @At("RETURN"))
    private boolean s1mp1e$blockForeignZoomClick(boolean clicked) {
        if (!clicked || !ZoomModule.blocksForeignZoom()) return clicked;
        if (!s1mp1e$isForeignCameraZoom()) return true;
        clickCount = 0;   // drop the rest of the queue too, so blocked presses can't replay when blocking stops
        return false;
    }

    @Unique
    private boolean s1mp1e$isForeignCameraZoom() {
        byte kind = s1mp1e$zoomKind;
        if (kind == 0) {
            KeyMapping self = (KeyMapping) (Object) this;
            String name = self.getName();
            String category = null;
            try {
                KeyMapping.Category c = self.getCategory();
                if (c != null) category = String.valueOf(c.id());
            } catch (Throwable ignored) {
                // a mod built a mapping with a broken category — classify on the name alone
            }
            boolean zoom;
            try {
                zoom = ForeignZoomKeys.isCameraZoom(name, category);
            } catch (Throwable t) {
                dev.s1mp1e.client.ErrorOnce.report("ForeignZoomKeys", t);
                zoom = false;   // on failure: never block
            }
            kind = zoom ? (byte) 2 : (byte) 1;
            s1mp1e$zoomKind = kind;
            if (zoom) System.out.println("[S1mp1e] Zoom: blocking foreign zoom key '" + name + "' [" + category + "]");
        }
        return kind == 2;
    }
}
