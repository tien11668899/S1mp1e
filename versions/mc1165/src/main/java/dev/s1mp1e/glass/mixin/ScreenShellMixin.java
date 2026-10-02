package dev.s1mp1e.glass.mixin;

import java.util.List;

import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.EntryListWidget;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Gives {@link SettingsShell} the screen's protected child management plus a slot for its per-screen state.
 *
 * <p>1.16.5: the page render itself is handed to the shell in {@code GameRendererTooltipLayerMixin} — every settings
 * page overrides {@code render} and there is no {@code renderWithTooltip} to wrap inside {@code Screen}, so the hook
 * sits on the one {@code Screen.render} call of {@code GameRenderer.render}.
 *
 * <p>1.16.5 keeps two lists (javap-verified, yarn 1.16.5+build.10): {@code children} (every element that takes input,
 * in hit-test order) and {@code buttons} (the widgets {@code Screen.render} draws). {@code addButton} appends to both,
 * {@code addChild} to {@code children} only; there is no {@code clearChildren} (that is 1.17's), {@code init(client,
 * w, h)} clears the two lists itself — so "clear" here is exactly that.
 */
@Mixin(Screen.class)
public abstract class ScreenShellMixin implements SettingsShell.Host {

    @Shadow @Final protected List<Element> children;

    @Shadow @Final protected List<ClickableWidget> buttons;

    @Shadow protected abstract <T extends ClickableWidget> T addButton(T button);

    @Shadow protected abstract <T extends Element> T addChild(T child);

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
        this.children.clear();
        this.buttons.clear();
    }

    @Override
    public void s1mp1e$addWidget(ClickableWidget widget) {
        this.addButton(widget);
    }

    @Override
    public void s1mp1e$addList(EntryListWidget<?> list) {
        this.addChild(list);
    }

    @Override
    public void s1mp1e$addPane(SettingsShell.Pane pane) {
        this.addChild(pane);
    }
}
