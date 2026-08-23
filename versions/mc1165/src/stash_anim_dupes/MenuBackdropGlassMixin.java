package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.MenuBackdrop;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 5 (menu-backdrop blur) — the DRAW.
 *
 * <p>Replaces vanilla's tiled dirt on every world-less screen with the blurred
 * title panorama, exactly as {@link MenuBackdrop#draw()} does for the 1.8.9 and
 * 1.12.2 lines. The panorama itself is grabbed elsewhere (the title-screen capture
 * hook); this mixin only performs the substitution at the point the dirt would be
 * painted.
 *
 * <p><b>Forge counterpart (behaviour reproduced byte-for-byte).</b> The 1.12.2
 * line drove this from an ASM splice at the HEAD of {@code GuiScreen.drawBackground(int)}
 * — {@code MenuBackdropHook.draw(GuiScreen, int)}. That hook did three things, all
 * mirrored below:
 * <ul>
 *   <li>call {@link MenuBackdrop#draw()};</li>
 *   <li>if it returned {@code true} the spliced {@code return} fired and vanilla's
 *       tiled dirt never rendered — here the equivalent is {@code ci.cancel()};</li>
 *   <li>if it returned {@code false} (no panorama captured yet, or the blur program
 *       failed to build) the dirt drew exactly as before — here we simply don't
 *       cancel;</li>
 *   <li>the whole call was wrapped in a try/catch that logged and fell back to the
 *       dirt on any {@code Throwable} ("menu backdrop failed, using dirt") — kept
 *       verbatim so a shader/GL fault can never black-hole the background.</li>
 * </ul>
 * Only the plumbing changes: the Forge ASM early-return becomes a Fabric
 * {@code cancellable} {@code @Inject}.
 *
 * <p><b>Fabric target (verified against yarn 1.16.5 build.10).</b> The 1.16.5
 * equivalent of {@code GuiScreen.drawBackground(int)} is
 * {@code Screen.renderBackgroundTexture(int)} — intermediary {@code method_25434},
 * descriptor {@code (I)V} — which tiles the {@code OPTIONS_BACKGROUND_TEXTURE}
 * (dirt). Injecting at {@code @At("HEAD")}, {@code cancellable = true}.
 *
 * <p>Both {@code renderBackground(MatrixStack)} ({@code method_25420}, descriptor
 * {@code (Lnet/minecraft/client/util/math/MatrixStack;)V}) and
 * {@code renderBackground(MatrixStack, int)} ({@code method_25433}, descriptor
 * {@code (Lnet/minecraft/client/util/math/MatrixStack;I)V}) delegate to
 * {@code renderBackgroundTexture(int)} <em>only when {@code client.world == null}</em>,
 * so this single point covers the main menu, options, multiplayer list, etc. and
 * never touches the in-world dim gradient the world branch draws instead.
 */
@Mixin(Screen.class)
public abstract class MenuBackdropGlassMixin {

    @Inject(method = "renderBackgroundTexture", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$menuBackdrop(int vOffset, CallbackInfo ci) {
        boolean drawn;
        try {
            drawn = MenuBackdrop.draw();
        } catch (Throwable t) {
            // Match the Forge hook: any failure falls back to the vanilla dirt.
            System.out.println("[S1mp1e] menu backdrop failed, using dirt: " + t);
            drawn = false;
        }
        if (drawn) {
            ci.cancel();
        }
    }
}
