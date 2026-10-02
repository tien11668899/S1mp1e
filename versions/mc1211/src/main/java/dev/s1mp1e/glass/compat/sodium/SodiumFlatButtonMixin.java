package dev.s1mp1e.glass.compat.sodium;

import dev.s1mp1e.glass.compat.SodiumGlass;
import net.caffeinemc.mods.sodium.client.gui.widgets.FlatButtonWidget;
import net.caffeinemc.mods.sodium.client.util.Dim2i;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes {@code FlatButtonWidget}'s private state (the page buttons and undo / apply / done / donate), which
 * {@link SodiumGlass} needs to draw them as sidebar entries and glass capsules.
 */
@Mixin(value = FlatButtonWidget.class, remap = false)
public abstract class SodiumFlatButtonMixin implements SodiumGlass.FlatBtn {
    @Shadow private boolean enabled;
    @Shadow private boolean visible;
    @Shadow @Final private Dim2i dim;

    @Override
    public boolean s1mp1e$enabled() {
        return this.enabled;
    }

    @Override
    public boolean s1mp1e$visible() {
        return this.visible;
    }

    @Override
    public Dim2i s1mp1e$dim() {
        return this.dim;
    }
}
