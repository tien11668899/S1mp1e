package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes a key-binds list entry's name and its two buttons, which {@link SettingsShell} lays out as one row. */
@Mixin(targets = "net.minecraft.client.gui.screens.options.controls.KeyBindsList$KeyEntry")
public abstract class KeyEntryShellMixin implements SettingsShell.KeyEntry {

    @Shadow @Final private Component name;
    @Shadow @Final private Button changeButton;
    @Shadow @Final private Button resetButton;

    @Override
    public Component s1mp1e$keyName() {
        return this.name;
    }

    @Override
    public Button s1mp1e$editButton() {
        return this.changeButton;
    }

    @Override
    public Button s1mp1e$resetButton() {
        return this.resetButton;
    }
}
