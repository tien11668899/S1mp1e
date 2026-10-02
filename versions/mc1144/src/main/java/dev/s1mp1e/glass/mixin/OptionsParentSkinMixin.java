package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.gui.screen.options.SkinOptionsScreen;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes this settings sub-page's parent screen to {@link SettingsShell} and marks the page as one the shell lays out.
 * 1.14.4 has no {@code GameOptionsScreen} base class (1.15+): every options page extends {@code Screen} directly and
 * keeps its own private {@code parent}, so there is one of these per page (a multi-target mixin cannot shadow fields
 * whose intermediary names differ).
 */
@Mixin(SkinOptionsScreen.class)
public abstract class OptionsParentSkinMixin implements SettingsShell.OptionsAccess {

    @Shadow @Final private Screen parent;

    @Override
    public Screen s1mp1e$parent() {
        return this.parent;
    }
}
