package dev.s1mp1e.glass.compat.sodium;

import dev.s1mp1e.glass.compat.SodiumGlass;
import me.jellysquid.mods.sodium.client.gui.widgets.FlatButtonWidget;
import me.jellysquid.mods.sodium.client.util.Dim2i;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes {@code FlatButtonWidget}'s private state (the page buttons and undo / apply / done / donate), which
 * {@link SodiumGlass} needs to draw them as sidebar entries and glass capsules. Sodium 0.4.4: the label has no getter
 * yet, so it is exposed here too.
 */
@Mixin(value = FlatButtonWidget.class, remap = false)
public abstract class SodiumFlatButtonMixin implements SodiumGlass.FlatBtn {
    @Shadow private boolean enabled;
    @Shadow private boolean visible;
    @Shadow @Final private Dim2i dim;
    @Shadow @Final private Text label;

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

    @Override
    public Text s1mp1e$label() {
        return this.label;
    }
}
