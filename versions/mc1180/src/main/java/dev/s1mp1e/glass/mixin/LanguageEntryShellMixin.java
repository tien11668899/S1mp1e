package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.resource.language.LanguageDefinition;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes a language list entry's display name: {@link SettingsShell} shows the list as pick-one rows. 1.18.2: the
 * entry holds the {@code LanguageDefinition} itself and draws its {@code toString()} ("Name (Region)").
 */
@Mixin(targets = "net.minecraft.client.gui.screen.option.LanguageOptionsScreen$LanguageSelectionListWidget$LanguageEntry")
public abstract class LanguageEntryShellMixin implements SettingsShell.ChoiceEntry {

    @Shadow @Final LanguageDefinition languageDefinition;

    @Override
    public Text s1mp1e$choiceLabel() {
        return new net.minecraft.text.LiteralText(this.languageDefinition.toString());
    }
}
