package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.GameOptionsScreen;
import net.minecraft.client.gui.widget.OptionListWidget;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes a settings sub-page's parent screen and option list to {@link SettingsShell}. */
@Mixin(GameOptionsScreen.class)
public abstract class GameOptionsShellMixin implements SettingsShell.OptionsAccess {

    @Shadow @Final protected Screen parent;
    @Shadow protected OptionListWidget body;

    @Override
    public Screen s1mp1e$parent() {
        return this.parent;
    }

    @Override
    public OptionListWidget s1mp1e$body() {
        return this.body;
    }
}
