package dev.s1mp1e.glass.mixin;

import java.util.List;

import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.gui.widget.ClickableWidget;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes the widgets of an option-list row to {@link SettingsShell}. 1.18.2: the option list is
 * {@code ButtonListWidget} and its row {@code ButtonEntry} keeps the one or two widgets in {@code buttons}
 * (package-private; javap-verified).
 */
@Mixin(targets = "net.minecraft.client.gui.widget.ButtonListWidget$ButtonEntry")
public abstract class OptionEntryShellMixin implements SettingsShell.EntryWidgets {

    @Shadow @Final List<ClickableWidget> buttons;

    @Override
    public List<ClickableWidget> s1mp1e$widgets() {
        return this.buttons;
    }
}
