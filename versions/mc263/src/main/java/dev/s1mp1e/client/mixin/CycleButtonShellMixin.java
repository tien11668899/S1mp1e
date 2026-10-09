package dev.s1mp1e.client.mixin;

import java.util.function.Function;

import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes a cycle button's caption and current value text separately (its message is "caption: value"). */
@Mixin(CycleButton.class)
public abstract class CycleButtonShellMixin<T> implements SettingsShell.CycleAccess {

    @Shadow @Final private Component name;
    @Shadow @Final private CycleButton.DisplayState displayState;
    @Shadow @Final private Function<T, Component> valueStringifier;
    @Shadow private T value;

    @Override
    public Component s1mp1e$caption() {
        return this.name;
    }

    @Override
    public boolean s1mp1e$captionOmitted() {
        return this.displayState != CycleButton.DisplayState.NAME_AND_VALUE;
    }

    @Override
    public Component s1mp1e$valueText() {
        return this.valueStringifier.apply(this.value);
    }
}
