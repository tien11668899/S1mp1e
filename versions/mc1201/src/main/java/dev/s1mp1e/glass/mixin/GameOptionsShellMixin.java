package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.GameOptionsScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes a settings sub-page's parent screen to {@link SettingsShell}. (1.20.1 has no shared {@code body} field:
 * every page keeps its option list under its own name, so the shell finds it among the screen's children.)
 */
@Mixin(GameOptionsScreen.class)
public abstract class GameOptionsShellMixin implements SettingsShell.OptionsAccess {

    @Shadow @Final protected Screen parent;

    @Override
    public Screen s1mp1e$parent() {
        return this.parent;
    }
}
