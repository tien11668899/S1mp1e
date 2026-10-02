package dev.s1mp1e.glass.compat.sodium;

import dev.s1mp1e.glass.compat.SodiumGlass;
import me.jellysquid.mods.sodium.client.gui.options.control.CyclingControl;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes {@code CyclingControl}'s private value names (indexed by the enum ordinal). */
@Mixin(value = CyclingControl.class, remap = false)
public abstract class SodiumCyclingControlMixin implements SodiumGlass.CycleInfo {
    @Shadow @Final private String[] names;   // 0.2.0: plain Strings
    @org.spongepowered.asm.mixin.Unique private Text[] s1mp1e$nameTexts;

    @Override
    public Text[] s1mp1e$names() {
        if (this.s1mp1e$nameTexts == null) {
            Text[] t = new Text[this.names.length];
            for (int i = 0; i < t.length; i++) t[i] = new net.minecraft.text.LiteralText(String.valueOf(this.names[i]));
            this.s1mp1e$nameTexts = t;
        }
        return this.s1mp1e$nameTexts;
    }
}
