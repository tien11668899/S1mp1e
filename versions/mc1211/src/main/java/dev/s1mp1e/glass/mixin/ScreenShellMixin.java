package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands the vanilla settings pages to {@link SettingsShell}: their {@code render} (none of them paints anything of
 * its own beyond {@code Screen.render}) is replaced by the shell's, and the shell gets the screen's protected child
 * management plus a slot for its per-screen state.
 */
@Mixin(Screen.class)
public abstract class ScreenShellMixin implements SettingsShell.Host {

    @Shadow protected abstract <T extends Element & Drawable & Selectable> T addDrawableChild(T drawableElement);

    @Shadow protected abstract <T extends Element & Selectable> T addSelectableChild(T child);

    @Shadow protected abstract void clearChildren();

    @Unique private SettingsShell.State s1mp1e$shellState;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$settingsShell(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (SettingsShell.handles(this)) {
            SettingsShell.render((Screen) (Object) this, context, mouseX, mouseY, delta);
            ci.cancel();
        }
    }

    @Override
    public SettingsShell.State s1mp1e$shell() {
        return this.s1mp1e$shellState;
    }

    @Override
    public void s1mp1e$shell(SettingsShell.State state) {
        this.s1mp1e$shellState = state;
    }

    @Override
    public void s1mp1e$clear() {
        this.clearChildren();
    }

    @Override
    public void s1mp1e$addWidget(ClickableWidget widget) {
        this.addDrawableChild(widget);
    }

    @Override
    public void s1mp1e$addPane(SettingsShell.Pane pane) {
        this.addSelectableChild(pane);
    }
}
