package com.seagull.liquidglass.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractRecipeBookScreen.class)
public interface AbstractRecipeBookScreenAccessor {
   @Accessor("widthTooNarrow")
   boolean liquidglass$widthTooNarrow();

   @Accessor("recipeBookComponent")
   RecipeBookComponent<?> liquidglass$recipeBook();
}
