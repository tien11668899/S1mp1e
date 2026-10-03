package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes a key-binds list entry's name, its keyBinding and its two buttons, which {@link SettingsShell} lays out as one
 * row. 1.13.2: the entry has no {@code update()} — its {@code render} refreshes the edit button's text and the reset
 * button's state every frame — so the shell needs the keyBinding to do the same for the row.
 */
@Mixin(net.minecraft.client.gui.screen.options.ControlsListWidget.KeyBindingEntry.class)
public abstract class KeyEntryShellMixin implements SettingsShell.KeyEntry {

    @Shadow @Final private KeyBinding keyBinding;
    @Shadow @Final private String name;
    @Shadow @Final private ButtonWidget keyBindingButton;
    @Shadow @Final private ButtonWidget resetButton;

    @Override
    public String s1mp1e$keyName() {
        return this.name;
    }

    @Override
    public KeyBinding s1mp1e$binding() {
        return this.keyBinding;
    }

    @Override
    public ButtonWidget s1mp1e$editButton() {
        return this.keyBindingButton;
    }

    @Override
    public ButtonWidget s1mp1e$resetButton() {
        return this.resetButton;
    }
}
