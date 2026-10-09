package com.seagull.liquidglass.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seagull.liquidglass.client.render.GuiAmbient;
import com.seagull.liquidglass.client.render.RecipeBookSlide;
import com.seagull.liquidglass.client.render.RecipeBookUnder;
import dev.s1mp1e.client.gui.GuiAlpha;
import dev.s1mp1e.client.gui.RecipeBookHost;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The recipe book's normal draw (above the inventory). While the book slides out from / back under the inventory it is
 * drawn UNDER the inventory panel instead ({@link RecipeBookUnder}, called from the panel), so this draw is skipped for
 * that frame. If the panel wasn't glass (no glass pipeline) the book is drawn here with the same slide + fade as a
 * fallback. The inventory glide itself lives in {@link RecipeBookInvGlideMixin}.
 */
@Mixin(AbstractRecipeBookScreen.class)
public abstract class RecipeBookSlideScreenMixin {

   @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/screens/recipebook/RecipeBookComponent;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"))
   private void lg$book(RecipeBookComponent<?> book, GuiGraphicsExtractor g, int mouseX, int mouseY, float delta,
                        Operation<Void> original) {
      if (RecipeBookUnder.alreadyDrawn(this)) return;
      if (!RecipeBookSlide.active() || !((Object) book instanceof RecipeBookHost host)) {
         original.call(book, g, mouseX, mouseY, delta);
         return;
      }
      float fade = RecipeBookSlide.bookFade();
      GuiAmbient.set(null, fade);
      g.pose().pushMatrix();
      g.pose().translate(RecipeBookSlide.bookShift(host.liquidglass$xOrigin()), 0F);
      GuiAlpha.push(fade);
      try {
         original.call(book, g, mouseX, mouseY, delta);
      } finally {
         GuiAlpha.pop();
         g.pose().popMatrix();
         GuiAmbient.clear();
      }
   }
}
