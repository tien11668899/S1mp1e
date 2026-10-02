package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes a settings sub-page's parent screen and option list to {@link SettingsShell}. */
@Mixin(OptionsSubScreen.class)
public abstract class OptionsSubScreenShellMixin implements SettingsShell.OptionsAccess {

    @Shadow @Final protected Screen lastScreen;
    @Shadow protected OptionsList list;

    @Override
    public Screen s1mp1e$parent() {
        return this.lastScreen;
    }

    @Override
    public OptionsList s1mp1e$body() {
        return this.list;
    }
}
