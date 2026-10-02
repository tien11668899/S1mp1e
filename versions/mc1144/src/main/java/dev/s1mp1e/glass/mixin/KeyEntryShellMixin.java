package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.options.KeyBinding;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes a key-binds list entry's name, its binding and its two buttons, which {@link SettingsShell} lays out as one
 * row. 1.14.4: the entry has no {@code update()} — its {@code render} refreshes the edit button's text and the reset
 * button's state every frame — so the shell needs the binding to do the same for the row.
 */
@Mixin(targets = "net.minecraft.client.gui.screen.options.ControlsListWidget$KeyBindingEntry")
public abstract class KeyEntryShellMixin implements SettingsShell.KeyEntry {

    @Shadow @Final private KeyBinding binding;
    @Shadow @Final private String bindingName;
    @Shadow @Final private ButtonWidget editButton;
    @Shadow @Final private ButtonWidget resetButton;

    @Override
    public String s1mp1e$keyName() {
        return this.bindingName;
    }

    @Override
    public KeyBinding s1mp1e$binding() {
        return this.binding;
    }

    @Override
    public ButtonWidget s1mp1e$editButton() {
        return this.editButton;
    }

    @Override
    public ButtonWidget s1mp1e$resetButton() {
        return this.resetButton;
    }
}
