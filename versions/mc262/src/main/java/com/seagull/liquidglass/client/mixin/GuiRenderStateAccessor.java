package com.seagull.liquidglass.client.mixin;

import java.util.List;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The GUI strata list (elements are the package-private {@code GuiRenderState$Node}) and the blur split point, for
 *  {@link com.seagull.liquidglass.client.render.TooltipLayer#promote}. */
@Mixin({GuiRenderState.class})
public interface GuiRenderStateAccessor {
   @Accessor("strata")
   List<Object> liquidglass$strata();

   @Accessor("firstStratumAfterBlur")
   int liquidglass$firstStratumAfterBlur();

   @Accessor("firstStratumAfterBlur")
   void liquidglass$setFirstStratumAfterBlur(int value);
}
