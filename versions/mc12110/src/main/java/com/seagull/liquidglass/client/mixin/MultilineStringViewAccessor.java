package com.seagull.liquidglass.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The line bounds of the protected record {@code MultilineTextField.StringView}, for {@link MultiLineTypingMixin}.
 * 1.21.x runs on intermediary names, so its {@code beginIndex()} / {@code endIndex()} cannot be looked up by name
 * at runtime (26.x is unobfuscated and reads them reflectively); an invoker is remapped with the mod.
 */
@Mixin(targets = "net.minecraft.client.gui.components.MultilineTextField$StringView")
public interface MultilineStringViewAccessor {
   @Invoker("beginIndex")
   int liquidglass$beginIndex();

   @Invoker("endIndex")
   int liquidglass$endIndex();
}
