package dev.s1mp1e.glass.mixin;

import java.util.List;
import java.util.Map;

import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.resource.language.LanguageDefinition;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * The language list for the settings shell. 1.13.2's {@code LanguageOptionsScreen$LanguageSelectionListWidget} is an
 * index based {@code ListWidget} (no entry objects): its lines are {@code languageCodes}, a click on line {@code i}
 * is {@code method_18414(i, button, x, y)} (select + apply, exactly what the shell's choice row then calls) and the
 * current one is {@code isEntrySelected(i)}.
 */
@Mixin(targets = "net.minecraft.client.gui.screen.options.LanguageOptionsScreen$LanguageSelectionListWidget")
public abstract class LanguageListShellMixin implements SettingsShell.ChoiceList {
    @Shadow @Final private List<String> languageCodes;
    @Shadow @Final private Map<String, LanguageDefinition> languageDefinitions;

    @Shadow protected abstract boolean method_18414(int index, int button, double mouseX, double mouseY);

    @Shadow protected abstract boolean isEntrySelected(int index);

    @Override
    public int s1mp1e$count() {
        return this.languageCodes.size();
    }

    @Override
    public String s1mp1e$label(int index) {
        LanguageDefinition d = this.languageDefinitions.get(this.languageCodes.get(index));
        return d == null ? this.languageCodes.get(index) : d.toString();
    }

    @Override
    public boolean s1mp1e$selected(int index) {
        return this.isEntrySelected(index);
    }

    @Override
    public void s1mp1e$pick(int index) {
        this.method_18414(index, 0, 0.0, 0.0);
    }
}
