package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes an options-list header's text: {@link SettingsShell} shows it as the caption above a card. */
@Mixin(targets = "net.minecraft.client.gui.components.OptionsList$HeaderEntry")
public abstract class OptionsHeaderShellMixin implements SettingsShell.HeadingEntry {

    @Shadow @Final private StringWidget widget;

    @Override
    public Component s1mp1e$heading() {
        return this.widget.getMessage();
    }
}
