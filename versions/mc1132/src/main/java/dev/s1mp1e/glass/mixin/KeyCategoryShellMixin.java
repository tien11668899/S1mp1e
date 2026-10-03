package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.SettingsShell;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes a key-binds category entry's name: {@link SettingsShell} shows it as the caption above a card. */
@Mixin(net.minecraft.client.gui.screen.options.ControlsListWidget.CategoryEntry.class)
public abstract class KeyCategoryShellMixin implements SettingsShell.HeadingEntry {

    @Shadow @Final private String name;

    @Override
    public String s1mp1e$heading() {
        return this.name;
    }
}
