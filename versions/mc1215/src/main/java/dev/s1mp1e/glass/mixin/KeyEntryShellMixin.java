package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes a key-binds list entry's name and its two buttons, which {@link SettingsShell} lays out as one row. */
@Mixin(targets = "net.minecraft.client.gui.screen.option.ControlsListWidget$KeyBindingEntry")
public abstract class KeyEntryShellMixin implements SettingsShell.KeyEntry {

    @Shadow @Final private Text bindingName;
    @Shadow @Final private ButtonWidget editButton;
    @Shadow @Final private ButtonWidget resetButton;

    @Override
    public Text s1mp1e$keyName() {
        return this.bindingName;
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
