package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes a language list entry's display name: {@link SettingsShell} shows the list as pick-one rows. */
@Mixin(targets = "net.minecraft.client.gui.screens.options.LanguageSelectScreen$LanguageSelectionList$Entry")
public abstract class LanguageEntryShellMixin implements SettingsShell.ChoiceEntry {

    @Shadow @Final private Component language;

    @Override
    public Component s1mp1e$choiceLabel() {
        return this.language;
    }
}
