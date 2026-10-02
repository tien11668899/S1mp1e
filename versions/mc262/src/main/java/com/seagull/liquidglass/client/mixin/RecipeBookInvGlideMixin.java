package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.RecipeBookSlide;
import com.seagull.liquidglass.client.render.RecipeBookUnder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Glides the whole inventory when the recipe book opens/closes.
 *
 * <p>The inventory panel is drawn by {@code extractBackground}, which {@code Screen.extractRenderStateWithTooltipAndSubtitles}
 * calls <em>before</em> {@code extractRenderState}; the slots/items/labels are drawn inside {@code extractRenderState}.
 * To move all of them together, {@link RecipeBookSlide} is driven and the {@code leftPos} field is overridden to the
 * animated x at the HEAD of the wrapper method (covering both the panel and the contents) and restored at RETURN so
 * hit-testing keeps the real position. The recipe-book button (an {@link ImageButton} placed relative to leftPos, whose
 * position is a widget field rather than re-read from leftPos) is shifted by the same amount for the frame. Only
 * recipe-book screens in the wide layout are affected. The toggle is detected here (leftPos moved since last frame) so
 * the slide is started and advanced before the panel is drawn.
 */
@Mixin(Screen.class)
public abstract class RecipeBookInvGlideMixin {

   @Unique private int lg$lastLeft = Integer.MIN_VALUE;
   @Unique private boolean lg$overridden;
   @Unique private int lg$saved;
   @Unique private int lg$shift;
   @Unique private final List<ImageButton> lg$shifted = new ArrayList<>();

   @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"))
   private void lg$invBegin(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      lg$overridden = false;
      if (!((Object) this instanceof AbstractRecipeBookScreen)) return;
      RecipeBookUnder.beginFrame(mouseX, mouseY, delta);
      AbstractContainerScreenAccessor pos = (AbstractContainerScreenAccessor) this;
      boolean narrow = ((AbstractRecipeBookScreenAccessor) this).liquidglass$widthTooNarrow();
      int left = pos.liquidglass$leftPos();
      if (lg$lastLeft != Integer.MIN_VALUE && left != lg$lastLeft) {
         if (narrow) RecipeBookSlide.cancel();
         else RecipeBookSlide.start(lg$lastLeft, left);
      }
      lg$lastLeft = left;
      RecipeBookSlide.update();
      if (RecipeBookSlide.active() && !narrow) {
         int animated = Math.round(RecipeBookSlide.inventoryLeft());
         lg$saved = left;
         lg$shift = animated - left;
         pos.liquidglass$setLeftPos(animated);
         lg$overridden = true;
         lg$shifted.clear();
         if (lg$shift != 0) {
            for (GuiEventListener c : ((Screen) (Object) this).children()) {
               if (c instanceof ImageButton b) {
                  b.setX(b.getX() + lg$shift);
                  lg$shifted.add(b);
               }
            }
         }
      }
   }

   @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("RETURN"))
   private void lg$invEnd(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      if ((Object) this instanceof AbstractRecipeBookScreen) RecipeBookUnder.endFrame();
      if (!lg$overridden) return;
      lg$overridden = false;
      ((AbstractContainerScreenAccessor) (Object) this).liquidglass$setLeftPos(lg$saved);
      for (ImageButton b : lg$shifted) b.setX(b.getX() - lg$shift);
      lg$shifted.clear();
   }
}
