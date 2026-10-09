package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.gui.components.FocusableTextWidget;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes an options-list header's text: {@link SettingsShell} shows it as the caption above a card. */
@Mixin(targets = "net.minecraft.client.gui.components.OptionsList$HeaderEntry")
public abstract class OptionsHeaderShellMixin implements SettingsShell.HeadingEntry {

    @Shadow @Final private FocusableTextWidget widget;   // 26.3: was StringWidget

    @Override
    public Component s1mp1e$heading() {
        // 26.3 styles section headings (underline + a pixel-font style); the shell draws its own plain heading, so
        // drop the style and keep only the text (same look as every other shell label).
        return Component.literal(this.widget.getMessage().getString());
    }
}
