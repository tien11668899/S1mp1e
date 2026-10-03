package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.gui.screen.options.LanguageOptionsScreen;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 1.13.2 has no {@code GameOptionsScreen} base class and a {@code Screen} has no title: every options page keeps its
 * own {@code parent} and its own title String. One tiny mixin per page hands both to the settings shell
 * ({@link SettingsShell.OptionsAccess}); "is a settings page" = {@code SettingsScreen} or this interface.
 */
@Mixin(LanguageOptionsScreen.class)
public abstract class OptionsParentLanguageMixin implements SettingsShell.OptionsAccess {
    @Shadow protected Screen parent;

    @Override
    public Screen s1mp1e$parent() {
        return this.parent;
    }

    @Override
    public String s1mp1e$title() {
        return net.minecraft.client.resource.language.I18n.translate("options.language");
    }
}
