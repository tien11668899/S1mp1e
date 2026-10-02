package dev.s1mp1e.glass.mixin;

import java.util.function.Function;

import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes a cycling button's caption and current value text separately (its message is "caption: value"). */
@Mixin(CyclingButtonWidget.class)
public abstract class CyclingButtonShellMixin<T> implements SettingsShell.CycleAccess {

    @Shadow @Final private Text optionText;
    @Shadow @Final private boolean optionTextOmitted;
    @Shadow @Final private Function<T, Text> valueToText;
    @Shadow private T value;

    @Override
    public Text s1mp1e$caption() {
        return this.optionText;
    }

    @Override
    public boolean s1mp1e$captionOmitted() {
        return this.optionTextOmitted;
    }

    @Override
    public Text s1mp1e$valueText() {
        return this.valueToText.apply(this.value);
    }
}
