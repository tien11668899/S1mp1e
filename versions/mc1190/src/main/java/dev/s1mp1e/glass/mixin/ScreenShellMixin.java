package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.EntryListWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Gives {@link SettingsShell} the screen's protected child management plus a slot for its per-screen state.
 *
 * <p>1.19.2: the page render itself is handed to the shell in {@code GameRendererTooltipLayerMixin} — every settings
 * page overrides {@code render} and there is no {@code renderWithTooltip} to wrap inside {@code Screen}, so the hook
 * sits on the one {@code Screen.render} call of {@code GameRenderer.render}.
 */
@Mixin(Screen.class)
public abstract class ScreenShellMixin implements SettingsShell.Host {

    @Shadow protected abstract <T extends Element & Drawable & Selectable> T addDrawableChild(T drawableElement);

    @Shadow protected abstract <T extends Element & Selectable> T addSelectableChild(T child);

    @Shadow protected abstract void clearChildren();

    @Unique private SettingsShell.State s1mp1e$shellState;

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
