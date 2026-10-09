package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands the vanilla settings pages to {@link SettingsShell}: their {@code extractRenderState} (the background is
 * extracted before it and stays vanilla) is replaced by the shell's, and the shell gets the screen's protected child
 * management plus a slot for its per-screen state.
 */
@Mixin(Screen.class)
public abstract class ScreenShellMixin implements SettingsShell.Host {

    @Shadow protected abstract <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T widget);

    @Shadow protected abstract <T extends GuiEventListener & NarratableEntry> T addWidget(T widget);

    @Shadow protected abstract void clearWidgets();

    @Unique private SettingsShell.State s1mp1e$shellState;

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$settingsShell(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (SettingsShell.handles(this)) {
            SettingsShell.extract((Screen) (Object) this, graphics, mouseX, mouseY, delta);
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
        this.clearWidgets();
    }

    @Override
    public void s1mp1e$addWidget(AbstractWidget widget) {
        this.addRenderableWidget(widget);
    }

    @Override
    public void s1mp1e$addPane(SettingsShell.Pane pane) {
        this.addWidget(pane);
    }
}
