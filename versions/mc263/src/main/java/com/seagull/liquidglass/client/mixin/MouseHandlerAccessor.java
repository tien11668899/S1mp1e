package com.seagull.liquidglass.client.mixin;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 26.3 only updates {@code MouseHandler.isLeftPressed} while no screen/overlay is open (see {@code onButton}: the
 * left/right/middle-pressed flags are written past a {@code screen != null → skip} branch), so under a settings screen
 * it is stale and a held slider never reads as held. {@code activeButton} is written earlier, before that branch, so it
 * stays accurate while a screen is up — it holds the currently-pressed mouse button, or null when none is down.
 */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
   @Accessor("activeButton")
   MouseButtonInfo s1mp1e$activeButton();

   /** DevShot only: simulate the left button being physically held so the slider-drag lens can be shot headlessly. */
   @Accessor("activeButton")
   void s1mp1e$setActiveButton(MouseButtonInfo info);
}
