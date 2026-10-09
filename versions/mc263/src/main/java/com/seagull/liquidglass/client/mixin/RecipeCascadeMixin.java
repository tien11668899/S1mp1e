package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.RecipeBookSlide;
import com.seagull.liquidglass.client.render.RecipeCascade;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Recipe items cascade in ({@link RecipeCascade}) whenever the page starts showing a different set of recipes.
 * {@code RecipeBookPage.updateButtonsForPage} is the single place a page's content is (re)assigned — on opening, page
 * turn, category switch and search — but it also runs when only craftability changed (inventory ticks): the page's
 * recipe identities are compared with the last cascade, so an unchanged page never re-animates. On an opening the
 * cascade waits until most of the slide-out is done ({@link RecipeBookSlide#cascadeBase}).
 */
public final class RecipeCascadeMixin {
   private RecipeCascadeMixin() {}

   @Mixin(RecipeBookPage.class)
   public abstract static class Page {
      @Shadow @Final private List<RecipeButton> buttons;
      @Shadow private List<RecipeCollection> recipeCollections;
      @Shadow private int currentPage;

      @Unique private Object[] lg$sig;

      @Inject(method = "updateButtonsForPage", at = @At("RETURN"))
      private void lg$cascade(CallbackInfo ci) {
         int per = RecipeBookPage.ITEMS_PER_PAGE;
         int from = Math.max(0, this.currentPage * per);
         int to = this.recipeCollections == null ? from : Math.min(this.recipeCollections.size(), from + per);
         Object[] sig = new Object[Math.max(0, to - from) + 1];
         sig[0] = this.currentPage;
         for (int i = from; i < to; i++) sig[i - from + 1] = this.recipeCollections.get(i);
         if (lg$same(sig, lg$sig)) return;
         lg$sig = sig;
         List<RecipeButton> shown = new ArrayList<>();
         for (RecipeButton b : this.buttons) if (b.visible) shown.add(b);
         RecipeCascade.schedule(shown, RecipeBookSlide.cascadeBase());
      }

      @Unique
      private static boolean lg$same(Object[] a, Object[] b) {
         if (a == null || b == null || a.length != b.length) return false;
         if (!a[0].equals(b[0])) return false;
         for (int i = 1; i < a.length; i++) if (a[i] != b[i]) return false;
         return true;
      }
   }

   /** Each recipe button grows from its centre on its turn; before its turn it isn't drawn at all. */
   @Mixin(RecipeButton.class)
   public abstract static class Button {
      @Unique private boolean lg$scaled;

      @Inject(method = "extractWidgetRenderState", at = @At("HEAD"), cancellable = true)
      private void lg$enter(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
         lg$scaled = false;
         float s = RecipeCascade.scale(this);
         if (s < 0F) { ci.cancel(); return; }
         if (s >= 1F) return;
         RecipeButton self = (RecipeButton) (Object) this;
         float cx = self.getX() + self.getWidth() / 2F, cy = self.getY() + self.getHeight() / 2F;
         g.pose().pushMatrix();
         g.pose().translate(cx, cy);
         g.pose().scale(s, s);
         g.pose().translate(-cx, -cy);
         lg$scaled = true;
      }

      @Inject(method = "extractWidgetRenderState", at = @At("RETURN"))
      private void lg$enterEnd(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
         if (!lg$scaled) return;
         lg$scaled = false;
         g.pose().popMatrix();
      }
   }
}
