package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.gui.components.FocusableTextWidget;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes a key-binds category entry's text: {@link SettingsShell} shows it as the caption above a card. */
@Mixin(targets = "net.minecraft.client.gui.screens.options.controls.KeyBindsList$CategoryEntry")
public abstract class KeyCategoryShellMixin implements SettingsShell.HeadingEntry {

    @Shadow @Final private FocusableTextWidget categoryName;

    @Override
    public Component s1mp1e$heading() {
        return this.categoryName.getMessage();
    }
}
