package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.AllGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * #19 F3+F4 switcher slots: each of the four 26x26 slots ({@code drawBackground}) becomes a faint glass-button tile and
 * the current choice ({@code drawSelectionBox}) a lifted one. 1.20.1 draws both with {@code drawTexture} on
 * {@code gamemode_switcher.png} inside the nested {@code GameModeSelectionScreen$ButtonWidget}; HEAD-cancel (not a
 * Redirect — redirects inside a button subclass fail at runtime). The mode icon draws separately and still shows.
 */
@Mixin(targets = "net.minecraft.client.gui.screen.GameModeSelectionScreen$ButtonWidget")
public abstract class GameModeSlotGlassMixin {

    @Inject(method = "drawBackground(Lnet/minecraft/client/gui/DrawContext;)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$slot(DrawContext ctx, CallbackInfo ci) {
        if (!(GlassProgram.ensureReady() && GlassProgram.btnUsable())) return;
        ci.cancel();
        ClickableWidget b = (ClickableWidget) (Object) this;
        float w = b.getWidth() - 2, h = b.getHeight() - 2;
        AllGlass.capsule(ctx, b.getX() + 1, b.getY() + 1, b.getX() + 1 + w, b.getY() + 1 + h, AllGlass.hotbarCorner(w, h), 0f, 0.5f);
    }

    @Inject(method = "drawSelectionBox(Lnet/minecraft/client/gui/DrawContext;)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$selection(DrawContext ctx, CallbackInfo ci) {
        if (!(GlassProgram.ensureReady() && GlassProgram.btnUsable())) return;
        ci.cancel();
        ClickableWidget b = (ClickableWidget) (Object) this;
        float w = b.getWidth() - 2, h = b.getHeight() - 2;
        AllGlass.capsule(ctx, b.getX() + 1, b.getY() + 1, b.getX() + 1 + w, b.getY() + 1 + h, AllGlass.hotbarCorner(w, h), 0.81f, 1f);
    }
}
