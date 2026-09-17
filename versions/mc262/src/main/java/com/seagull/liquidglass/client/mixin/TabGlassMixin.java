package com.seagull.liquidglass.client.mixin;

import java.util.WeakHashMap;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.animation.Fade;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassRectRenderState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Liquid-glass tabs for 26.2's {@link MenuTabBar} (Video Settings' 顯示 / 介面 / 偏好 / 品質與效能, and every other
 * tabbed menu).
 *
 * <p>26.2 added {@code MenuTabBar}; its {@code MenuTabButton} is a {@code TabButton}, not an {@code AbstractButton},
 * so {@link ButtonGlassMixin} never reached it and the tabs kept vanilla's square {@code widget/tab*} sprites —
 * plus, for the selected tab, a rectangular {@code MENU_BACKGROUND} block — i.e. a square highlight sitting next to
 * the rounded glass buttons. Verified with javap -c against the 26.2 client jar:
 * {@code MenuTabButton.extractWidgetRenderState(GuiGraphicsExtractor,int,int,float)} makes exactly one
 * {@code blitSprite(RenderPipeline,Identifier,IIII)V} call (no tint argument) and one
 * {@code renderMenuBackground(GuiGraphicsExtractor,IIII)V} call.
 *
 * <p>Now each tab is a rounded glass capsule on the BTN program (same knobs as {@link ButtonGlassMixin}: A = enabled
 * dim, R = corner 1.0, G = 1 − lift, B = opacity):
 * <ul>
 *   <li>selected → full lift ({@link #LIFT_SELECTED}, the buttons' hovered brightness);</li>
 *   <li>hovered but not selected → a lighter lift ({@link #LIFT_HOVER}) so the selected tab stays the brightest;</li>
 *   <li>resting → faint glass, like a resting button.</li>
 * </ul>
 * The lift eases per tab over {@link #FADE_MS} (100 ms, the buttons' hover fade), so switching or hovering a tab
 * fades instead of popping, and the capsule opacity eases in over {@link #OPEN_FADE_MS} when a screen opens. The
 * square menu-background block is suppressed while glass draws; vanilla's 1px underline under the selected label is
 * kept and sits just below the pill (the capsule stops 3px above the tab bottom). When the glass pipeline isn't
 * usable everything falls back to vanilla.
 */
@Mixin(MenuTabBar.MenuTabButton.class)
public abstract class TabGlassMixin {

   /** Selected tab: the same lift a hovered button gets (G ≈ 48). */
   private static final float LIFT_SELECTED = 0.81F;
   /** Hovered, not selected: visibly lit, but below the selected tab. */
   private static final float LIFT_HOVER = 0.5F;
   /** Tab lift ease — the buttons' 100 ms hover fade. */
   private static final float FADE_MS = 100.0F;

   /** Per-tab lift fade; WeakHashMap auto-evicts discarded tabs. Render thread only. */
   private static final WeakHashMap<MenuTabBar.MenuTabButton, Fade> lg$tabFades = new WeakHashMap<>();

   @Redirect(
      method = "extractWidgetRenderState",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"
      )
   )
   private void lg$glassTab(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h) {
      if (!(GlassPipeline.ensureReady() && GlassPipeline.btnUsable())) {
         g.blitSprite(pipeline, sprite, x, y, w, h);
         return;
      }
      MenuTabBar.MenuTabButton self = (MenuTabBar.MenuTabButton)(Object)this;

      // lift: selected > hovered > resting, eased per tab (retargeted from the LIVE value so it never pops)
      float target = self.isSelected() ? LIFT_SELECTED : (self.isHoveredOrFocused() ? LIFT_HOVER : 0.0F);
      Fade fade = lg$tabFades.get(self);
      if (fade == null) {
         fade = new Fade(target, FADE_MS);   // start settled: a freshly built screen doesn't flash
         lg$tabFades.put(self, fade);
      } else if (fade.target() != target) {
         float current = fade.value();
         fade.snap(current);
         fade.to(target);
      }
      int liftG = Math.round(255.0F * (1.0F - fade.value())) & 0xFF;

      // opacity eases in with the screen (tabs are drawn without a tint, so this is the whole opacity); the fade is
      // shared with the glass buttons and sliders so a return to the same screen restarts all of them together
      int opacity = Math.round(255.0F * ScreenOpenFade.value(Minecraft.getInstance().gui.screen())) & 0xFF;

      int col = (self.active ? 255 : 102) << 24 | 0xFF0000 | liftG << 8 | opacity;
      ((GuiGraphicsExtractorAccessor)g)
         .liquidglass$guiRenderState()
         .addGuiElement(new GlassRectRenderState(GlassPipeline.btn(), TextureSetup.noTexture(), g.pose(),
            x + 2, y + 2, x + w - 2, y + h - 3, 10, col, null));
   }

   /** The selected tab's rectangular menu-background block would sit square inside the rounded pill — drop it. */
   @Inject(method = "renderMenuBackground", at = @At("HEAD"), cancellable = true)
   private void lg$noSquareTabBackground(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, CallbackInfo ci) {
      if (GlassPipeline.ensureReady() && GlassPipeline.btnUsable()) {
         ci.cancel();
      }
   }
}
