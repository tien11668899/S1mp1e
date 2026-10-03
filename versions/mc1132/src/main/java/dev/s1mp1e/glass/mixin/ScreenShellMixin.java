package dev.s1mp1e.glass.mixin;

import java.util.List;

import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.class_4122;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ListWidget;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Gives {@link SettingsShell} the screen's protected child management plus a slot for its per-screen state.
 *
 * <p>1.13.2: the page render itself is handed to the shell in {@code GameRendererTooltipLayerMixin} — every settings
 * page overrides {@code render} and there is no {@code renderWithTooltip} to wrap inside {@code Screen}, so the hook
 * sits on the one {@code Screen.render} call of {@code GameRenderer.render}.
 *
 * <p>1.13.2 keeps two lists (javap-verified, yarn 1.13.2+build.10): {@code field_20307} (every element that takes input,
 * in hit-test order) and {@code buttons} (the widgets {@code Screen.render} draws). {@code addButton} appends to both,
 * {@code addChild} to {@code field_20307} only; there is no {@code clearChildren} (that is 1.17's), {@code init(client,
 * w, h)} clears the two lists itself — so "clear" here is exactly that.
 */
@Mixin(Screen.class)
public abstract class ScreenShellMixin implements SettingsShell.Host {

    @Shadow @Final protected List<class_4122> field_20307;

    @Shadow @Final protected List<ButtonWidget> buttons;

    @Shadow protected abstract <T extends ButtonWidget> T addButton(T button);


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
        this.field_20307.clear();
        this.buttons.clear();
    }

    @Override
    public void s1mp1e$addWidget(ButtonWidget widget) {
        this.addButton(widget);
    }

    @Override
    public void s1mp1e$addList(ListWidget list) {
        this.field_20307.add(list);       // 1.13.2: no addChild, the list of input field_20307 is filled directly
    }

    @Override
    public void s1mp1e$addPane(SettingsShell.Pane pane) {
        this.field_20307.add(pane);
    }
}
