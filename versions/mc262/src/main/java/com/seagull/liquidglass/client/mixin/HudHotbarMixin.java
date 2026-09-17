package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.animation.Fade;
import com.seagull.liquidglass.client.animation.Spring;
import com.seagull.liquidglass.client.render.GlassPainter;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassRectRenderState;
import com.seagull.liquidglass.client.render.PanelGhost;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({Hud.class})
public abstract class HudHotbarMixin {
   @Shadow
   private int toolHighlightTimer;
   @Shadow
   private ItemStack lastToolHighlight;
   @Shadow
   @Final
   private Minecraft minecraft;
   private static final int STRIP_FROSTED = -2130706433;
   private static final int SEL_SHARP = -7937;
   private static final int SHADOW_PAD = 12;
   private static final float SCALE = 1.15F;
   private static final int DECO_LIFT = 7;
   private static final int F_STRIP_TOP = GlassPainter.argb(0.34F, 14, 18, 24);
   private static final int F_STRIP_BOT = GlassPainter.argb(0.44F, 6, 8, 12);
   private static final int F_SEL_OUTLINE = GlassPainter.argb(0.8F, 255, 255, 255);
   private static final int F_SEL_TOP = GlassPainter.argb(0.34F, 255, 255, 255);
   private static final int F_SEL_BOT = GlassPainter.argb(0.1F, 255, 255, 255);
   private static Spring lg$lead = null;
   private static Spring lg$trail = null;
   private static long lg$lastNanos = 0L;
   private static int lg$lastSlot = -1;
   private static boolean lg$decoPushed = false;
   private static Spring lg$nameW = null;
   private static final Fade lg$nameInFade = new Fade(0.0F, 150.0F);
   private static Component lg$nameCur = null;
   private static Component lg$namePrev = null;
   private static final Fade lg$namePrevFade = new Fade(0.0F, 150.0F);

   @Shadow
   protected abstract Player getCameraPlayer();

   @Shadow
   protected abstract void extractSlot(GuiGraphicsExtractor var1, int var2, int var3, DeltaTracker var4, Player var5, ItemStack var6, int var7);

   @Shadow
   public abstract Font getFont();

   @Inject(
      method = {"extractHotbarAndDecorations"},
      at = {@At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/Hud;extractItemHotbar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
         shift = Shift.AFTER
      )}
   )
   private void lg$liftDecosStart(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
      g.pose().pushMatrix();
      g.pose().translate(0.0F, -7.0F);
      lg$decoPushed = true;
   }

   @Inject(
      method = {"extractHotbarAndDecorations"},
      at = {@At("RETURN")}
   )
   private void lg$liftDecosEnd(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
      if (lg$decoPushed) {
         g.pose().popMatrix();
         lg$decoPushed = false;
      }
   }

   @Inject(
      method = {"extractSelectedItemName"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void lg$glassItemName(GuiGraphicsExtractor g, CallbackInfo ci) {
      if (GlassPipeline.ensureReady() && GlassPipeline.usable()) {
         ci.cancel();
         if (this.toolHighlightTimer > 0 && !this.lastToolHighlight.isEmpty()) {
            MutableComponent str = Component.empty().append(this.lastToolHighlight.getHoverName()).withStyle(this.lastToolHighlight.getRarity().color());
            if (this.lastToolHighlight.has(DataComponents.CUSTOM_NAME)) {
               str.withStyle(ChatFormatting.ITALIC);
            }

            Font font = this.getFont();
            int strWidth = font.width(str);
            if (lg$nameCur == null || !lg$nameCur.getString().equals(str.getString())) {
               if (lg$nameCur != null && lg$nameInFade.value() > 0.1F) {
                  lg$namePrev = lg$nameCur;
                  lg$namePrevFade.snap(1.0F);
                  lg$namePrevFade.to(0.0F);
               }

               lg$nameCur = str;
            }

            float halfW = (float)strWidth * 0.5F;
            if (lg$nameW != null && lg$nameInFade.isVisible()) {
               lg$nameW.setTarget(halfW);
            } else {
               lg$nameW = new Spring(halfW, 30.0F, 1.0F);
            }

            float rem = 0.016666668F;

            while (rem > 0.0F) {
               float hh = Math.min(rem, 0.008333334F);
               lg$nameW.update(hh);
               rem -= hh;
            }

            if (!Float.isFinite(lg$nameW.value())) {
               lg$nameW.snap(halfW);
            }

            lg$nameInFade.to(1.0F);
            lg$namePrevFade.to(0.0F);
            float vanillaFade = Math.min(1.0F, (float)this.toolHighlightTimer / 10.0F);
            float a = lg$nameInFade.value() * vanillaFade;
            if (!(a <= 0.01F)) {
               int cx = g.guiWidth() / 2;
               int y = g.guiHeight() - 59;
               if (this.minecraft.gameMode != null && !this.minecraft.gameMode.canHurtPlayer()) {
                  y += 14;
               }

               int hw = Math.round(lg$nameW.value()) + 6;
               int ab = Math.round(a * 255.0F) & 0xFF;
               GuiRenderState rs = ((GuiGraphicsExtractorAccessor)g).liquidglass$guiRenderState();
               TextureSetup ts = TextureSetup.singleTexture(GlassPipeline.backdropView(), GlassPipeline.sampler());
               rs.addGuiElement(new GlassRectRenderState(GlassPipeline.glass(), ts, g.pose(), cx - hw, y - 4, cx + hw, y + 13, 10, -2130706688 | ab, null));
               if (lg$namePrev != null && lg$namePrevFade.isVisible()) {
                  int pa = Math.round(a * lg$namePrevFade.value() * 255.0F) & 0xFF;
                  int pw = font.width(lg$namePrev);
                  g.text(font, lg$namePrev, cx - pw / 2, y, pa << 24 | 16777215, true);
               }

               int na = Math.round(a * (1.0F - lg$namePrevFade.value()) * 255.0F) & 0xFF;
               if (na > 4) {
                  g.text(font, str, cx - strWidth / 2, y, na << 24 | 16777215, true);
               }
            }
         } else {
            lg$nameInFade.snap(0.0F);
            lg$nameCur = null;
            lg$namePrevFade.snap(0.0F);
         }
      }
   }

   @Inject(
      method = {"extractItemHotbar"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void lg$glassHotbar(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
      Player player = this.getCameraPlayer();
      if (player != null) {
         ci.cancel();
         int center = g.guiWidth() / 2;
         int bottom = g.guiHeight() - 4;
         float ghostA = PanelGhost.alpha();
         if (ghostA > 0.0F && GlassPipeline.ensureReady() && GlassPipeline.usable()) {
            GuiRenderState grs = ((GuiGraphicsExtractorAccessor)g).liquidglass$guiRenderState();
            TextureSetup gts = TextureSetup.singleTexture(GlassPipeline.backdropView(), GlassPipeline.sampler());
            int gb = Math.round(ghostA * 255.0F) & 0xFF;

            for (int[] r : PanelGhost.rects()) {
               grs.addGuiElement(
                  new GlassRectRenderState(GlassPipeline.glass(), gts, g.pose(), r[0], r[1], r[0] + r[2], r[1] + r[3], 12, -2143420672 | gb, null)
               );
            }
         }

         g.pose().pushMatrix();
         g.pose().translate((float)center, (float)bottom);
         g.pose().scale(1.15F, 1.15F);
         g.pose().translate((float)(-center), (float)(-bottom));

         try {
            int stripX0 = center - 91;
            int stripY0 = bottom - 22;
            int stripX1 = center + 91;
            int slot = player.getInventory().getSelectedSlot();
            float slotCenterX = (float)center - 80.0F + (float)slot * 20.0F;
            long now = System.nanoTime();
            float dt = lg$lastNanos == 0L ? 0.016666668F : Math.min(0.1F, (float)(now - lg$lastNanos) * 1.0E-9F);
            lg$lastNanos = now;
            if (lg$lead == null) {
               lg$lead = new Spring(slotCenterX, 55.0F, 1.0F);
               lg$trail = new Spring(slotCenterX, 30.0F, 1.0F);
               lg$lastSlot = slot;
            } else if (slot != lg$lastSlot) {
               lg$lead.setTarget(slotCenterX);
               lg$trail.setTarget(slotCenterX);
               lg$lastSlot = slot;
            }

            float remaining = dt;

            while (remaining > 0.0F) {
               float h = Math.min(remaining, 0.008333334F);
               lg$lead.update(h);
               lg$trail.update(h);
               remaining -= h;
            }

            if (!Float.isFinite(lg$lead.value()) || !Float.isFinite(lg$trail.value())) {
               lg$lead.snap(slotCenterX);
               lg$trail.snap(slotCenterX);
            }

            float lo = Math.min(lg$lead.value(), lg$trail.value());
            float hi = Math.max(lg$lead.value(), lg$trail.value());
            int pillX0 = Math.round(lo - 9.0F);
            int pillX1 = Math.round(hi + 9.0F);
            int pillY0 = bottom - 20;
            int pillY1 = bottom - 2;
            boolean glass = GlassPipeline.ensureReady();
            if (glass && GlassPipeline.usable()) {
               GuiRenderState rs = ((GuiGraphicsExtractorAccessor)g).liquidglass$guiRenderState();
               Matrix3x2fc pose = g.pose();
               TextureSetup ts = TextureSetup.singleTexture(GlassPipeline.backdropView(), GlassPipeline.sampler());
               rs.addGuiElement(new GlassRectRenderState(GlassPipeline.glass(), ts, pose, stripX0, stripY0, stripX1, bottom, 12, -2130706433, null));
               rs.addGuiElement(new GlassRectRenderState(GlassPipeline.glass(), ts, pose, pillX0, pillY0, pillX1, pillY1, 12, -7937, null));
            } else {
               GlassPainter.capsule(g, (float)stripX0, (float)stripY0, 182.0F, 22.0F, 11.0F, F_STRIP_TOP, F_STRIP_BOT);
               GlassPainter.topSpecular(g, (float)stripX0, (float)stripY0, 182.0F, 22.0F, 11.0F, GlassPainter.argb(0.42F, 255, 255, 255));
               GlassPainter.capsuleOutlined(g, (float)pillX0, (float)pillY0, (float)(pillX1 - pillX0), 22.0F, 11.0F, F_SEL_TOP, F_SEL_BOT, F_SEL_OUTLINE);
            }

            int seed = 1;

            for (int i = 0; i < 9; i++) {
               int x = center - 90 + i * 20 + 2;
               int y = bottom - 16 - 3;
               this.extractSlot(g, x, y, deltaTracker, player, player.getInventory().getItem(i), seed++);
            }

            ItemStack offhand = player.getOffhandItem();
            if (!offhand.isEmpty()) {
               boolean leftArm = player.getMainArm().getOpposite() == HumanoidArm.LEFT;
               int oy = bottom - 16 - 3;
               int ox = leftArm ? center - 91 - 26 : center + 91 + 10;
               if (glass && GlassPipeline.usable()) {
                  GuiRenderState rs = ((GuiGraphicsExtractorAccessor)g).liquidglass$guiRenderState();
                  TextureSetup ts = TextureSetup.singleTexture(GlassPipeline.backdropView(), GlassPipeline.sampler());
                  rs.addGuiElement(new GlassRectRenderState(GlassPipeline.glass(), ts, g.pose(), ox - 3, oy - 3, ox + 19, oy + 19, 12, -2130706433, null));
               } else {
                  GlassPainter.capsuleOutlined(g, (float)(ox - 3), (float)(oy - 3), 22.0F, 22.0F, 6.0F, F_STRIP_TOP, F_STRIP_BOT, F_SEL_OUTLINE);
               }

               this.extractSlot(g, ox, oy, deltaTracker, player, offhand, seed++);
            }
         } finally {
            g.pose().popMatrix();
         }
      }
   }
}
