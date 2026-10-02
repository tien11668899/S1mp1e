package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.WorldOptionsScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * The World Options page is not an {@code OptionsSubScreen} (a plain grid of buttons): expose the screen it returns to
 * so {@link SettingsShell} finds the options screen it belongs to. It has no option list.
 */
@Mixin(WorldOptionsScreen.class)
public abstract class WorldOptionsShellMixin implements SettingsShell.OptionsAccess {

    @Shadow @Final private Screen lastScreen;

    @Override
    public Screen s1mp1e$parent() {
        return this.lastScreen;
    }

    @Override
    public OptionsList s1mp1e$body() {
        return null;
    }
}
