package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.EntryListWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hands the vanilla settings pages to {@link SettingsShell}: their {@code render} is replaced by the shell's, and the
 * shell gets the screen's protected child management plus a slot for its per-screen state.
 *
 * <p>1.20.1: every settings page overrides {@code render} (background, list, title, {@code super.render}), so a HEAD
 * hook on {@code Screen.render} — what the 1.21.1 line uses, where none of them does — would only run after the
 * page had already painted itself. The one call every screen's frame goes through is the {@code this.render(...)}
 * inside the final {@code Screen.renderWithTooltip} (javap-verified: it is what {@code GameRenderer} calls, and it
 * draws the widget tooltip the page queued right after), so that call is wrapped instead: the shell replaces the
 * whole page render, the tooltip pass after it stays vanilla.
 */
@Mixin(Screen.class)
public abstract class ScreenShellMixin implements SettingsShell.Host {

    @Shadow protected abstract <T extends Element & Drawable & Selectable> T addDrawableChild(T drawableElement);

    @Shadow protected abstract <T extends Element & Selectable> T addSelectableChild(T child);

    @Shadow protected abstract void clearChildren();

    @Unique private SettingsShell.State s1mp1e$shellState;

    @WrapOperation(method = "renderWithTooltip",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/Screen;render(Lnet/minecraft/client/gui/DrawContext;IIF)V"))
    private void s1mp1e$settingsShell(Screen self, DrawContext context, int mouseX, int mouseY, float delta,
                                      Operation<Void> original) {
        if (SettingsShell.handles(self)) {
            SettingsShell.render(self, context, mouseX, mouseY, delta);
        } else {
            original.call(self, context, mouseX, mouseY, delta);
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
    public void s1mp1e$addList(EntryListWidget<?> list) {
        this.addDrawableChild(list);
    }

    @Override
    public void s1mp1e$addPane(SettingsShell.Pane pane) {
        this.addSelectableChild(pane);
    }
}
