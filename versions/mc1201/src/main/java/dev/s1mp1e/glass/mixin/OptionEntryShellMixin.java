package dev.s1mp1e.glass.mixin;

import java.util.List;

import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.gui.widget.ClickableWidget;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes the widgets of an option-list row (the entry class is protected) to {@link SettingsShell}. */
@Mixin(targets = "net.minecraft.client.gui.widget.OptionListWidget$WidgetEntry")
public abstract class OptionEntryShellMixin implements SettingsShell.EntryWidgets {

    @Shadow @Final List<ClickableWidget> widgets;   // package-private in 1.20.1

    @Override
    public List<ClickableWidget> s1mp1e$widgets() {
        return this.widgets;
    }
}
