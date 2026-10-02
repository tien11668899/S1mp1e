package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.options.GameOptionsScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes a settings sub-page's parent screen to {@link SettingsShell}. 1.15.2 already has the {@code GameOptionsScreen}
 * base class with the shared {@code parent} (1.14.4 needs one mixin per page). The resource pack screen extends it
 * too in 1.15.2; the shell leaves that one alone ({@code SettingsShell.handles}).
 */
@Mixin(GameOptionsScreen.class)
public abstract class GameOptionsShellMixin implements SettingsShell.OptionsAccess {

    @Shadow @Final protected Screen parent;

    @Override
    public Screen s1mp1e$parent() {
        return this.parent;
    }
}
