package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.AllGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * #19 — F3+F4 switcher slots: each 26x26 slot ({@code drawBackground}) becomes a faint glass-button tile and the
 * current choice ({@code drawSelectionBox}) a lifted one. 1.19.2 draws both with the instance {@code drawTexture} on
 * {@code gamemode_switcher.png} inside the nested {@code GameModeSelectionScreen$ButtonWidget}, after translating to
 * the widget origin; HEAD-cancel before the translate and draw at absolute coords from the widget's {@code x}/{@code y}
 * fields (not a Redirect — redirects inside a button subclass fail at runtime). The mode icon draws separately and
 * still shows.
 */
@Mixin(targets = "net.minecraft.client.gui.screen.GameModeSelectionScreen$ButtonWidget")
public abstract class GameModeSlotGlassMixin {

    @Inject(method = "drawBackground(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/texture/TextureManager;)V",
            at = @At("HEAD"), cancellable = true)
    private void s1mp1e$slot(MatrixStack matrices, TextureManager tm, CallbackInfo ci) {
        if (!(GlassProgram.ensureReady() && GlassProgram.btnUsable())) return;
        ci.cancel();
        ClickableWidget b = (ClickableWidget) (Object) this;
        float w = b.getWidth() - 2, h = b.getHeight() - 2;
        AllGlass.capsule(matrices, b.x + 1, b.y + 1, b.x + 1 + w, b.y + 1 + h, AllGlass.hotbarCorner(w, h), 0f, 0.5f);
    }

    @Inject(method = "drawSelectionBox(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/texture/TextureManager;)V",
            at = @At("HEAD"), cancellable = true)
    private void s1mp1e$selection(MatrixStack matrices, TextureManager tm, CallbackInfo ci) {
        if (!(GlassProgram.ensureReady() && GlassProgram.btnUsable())) return;
        ci.cancel();
        ClickableWidget b = (ClickableWidget) (Object) this;
        float w = b.getWidth() - 2, h = b.getHeight() - 2;
        AllGlass.capsule(matrices, b.x + 1, b.y + 1, b.x + 1 + w, b.y + 1 + h, AllGlass.hotbarCorner(w, h), 0.81f, 1f);
    }
}
