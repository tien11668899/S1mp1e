package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.animation.Fade;
import com.seagull.liquidglass.client.render.GlassCorners;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassRectRenderState;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The green recipe-book button (an {@link ImageButton} whose sprite is a whole stone button with the book painted on it)
 * becomes a liquid-glass button like every other: the vanilla sprite is replaced by the BTN glass capsule (hotbar corner
 * via {@link GlassCorners}, same hover lift / screen-open fade as {@code ButtonGlassMixin}) with just the green book —
 * {@code textures/gui/recipe_book_icon.png}, the vanilla sprite with its stone frame stripped, blitted as a plain
 * texture. Without Fabric API the mod's assets are not a resource pack (the shaders are read from the classpath for
 * the same reason), so the PNG is read from the classpath once and registered as a {@link DynamicTexture}; if that
 * fails the vanilla sprite is kept. Other image buttons are untouched.
 */
@Mixin(ImageButton.class)
public abstract class RecipeBookButtonGlassMixin {

   @Unique private static final Identifier LG_ICON = Identifier.fromNamespaceAndPath("liquidglass", "textures/gui/recipe_book_icon.png");
   @Unique private static final float LG_LIFT_ON = 0.81F;
   @Unique private static final float LG_HOVER_MS = 100.0F;
   @Unique private static final WeakHashMap<ImageButton, Fade> lg$hover = new WeakHashMap<>();
   @Unique private static int lg$iconState;   // 0 = not tried, 1 = registered, -1 = failed

   @Unique
   private static boolean lg$iconReady() {
      if (lg$iconState == 0) {
         lg$iconState = -1;
         try (InputStream in = RecipeBookButtonGlassMixin.class.getResourceAsStream("/assets/liquidglass/textures/gui/recipe_book_icon.png")) {
            if (in != null) {
               NativeImage img = NativeImage.read(in);
               Minecraft.getInstance().getTextureManager().register(LG_ICON, new DynamicTexture(() -> "liquidglass:recipe_book_icon", img));
               lg$iconState = 1;
            }
         } catch (Throwable ignored) {
         }
      }
      return lg$iconState == 1;
   }

   @Redirect(method = "extractContents", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
   private void lg$glassBookButton(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h) {
      if (!sprite.getPath().startsWith("recipe_book/button") || !GlassPipeline.ensureReady() || !GlassPipeline.btnUsable()
            || !lg$iconReady()) {
         g.blitSprite(pipeline, sprite, x, y, w, h);
         return;
      }
      ImageButton self = (ImageButton) (Object) this;
      boolean over = self.isHoveredOrFocused();
      Fade hover = lg$hover.get(self);
      if (hover == null) {
         hover = new Fade(over ? 1.0F : 0.0F, LG_HOVER_MS);
         lg$hover.put(self, hover);
      }
      float target = over ? 1.0F : 0.0F;
      if (hover.target() != target) {
         hover.snap(hover.value());
         hover.to(target);
      }
      int liftG = Math.round(255.0F * (1.0F - LG_LIFT_ON * hover.value())) & 0xFF;
      int alphaByte = Math.round(255.0F * ScreenOpenFade.value(Minecraft.getInstance().gui.screen())) & 0xFF;
      int col = (self.active ? 255 : 102) << 24 | GlassCorners.knobByte(w, h) << 16 | liftG << 8 | alphaByte;
      ((GuiGraphicsExtractorAccessor) g).liquidglass$guiRenderState()
            .addGuiElement(new GlassRectRenderState(GlassPipeline.btn(), TextureSetup.noTexture(), g.pose(), x, y, x + w, y + h, 10, col, null));
      g.blit(RenderPipelines.GUI_TEXTURED, LG_ICON, x, y, 0.0F, 0.0F, w, h, 20, 18);
   }
}
