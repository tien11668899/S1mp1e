package dev.s1mp1e.glass.mixin;

import java.util.ArrayList;
import java.util.List;

import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * The video page's list row: 1.13.2's {@code OptionPairWidget$Pair} holds one or two option widgets in two fields
 * ({@code field_20081} left, {@code field_20082} right or null) - handed to the settings shell as its rows.
 */
@Mixin(net.minecraft.client.gui.widget.OptionPairWidget.Pair.class)
public abstract class OptionEntryShellMixin implements SettingsShell.EntryWidgets {
    @Shadow @Final private ButtonWidget field_20081;
    @Shadow @Final private ButtonWidget field_20082;

    @Override
    public List<ButtonWidget> s1mp1e$widgets() {
        List<ButtonWidget> out = new ArrayList<ButtonWidget>(2);
        if (this.field_20081 != null) out.add(this.field_20081);
        if (this.field_20082 != null) out.add(this.field_20082);
        return out;
    }
}
