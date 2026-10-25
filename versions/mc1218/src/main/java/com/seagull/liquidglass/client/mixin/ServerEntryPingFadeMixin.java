package com.seagull.liquidglass.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.s1mp1e.client.gui.GuiAlpha;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server list: when a server's ping comes back, the parts that the result replaces — the MOTD lines, the favicon, the
 * ping-bars icon and the player-count / status text — fade in and rise into place instead of swapping in one frame. The
 * server name, which never changes, is left alone. The trigger is the entry's {@link ServerData#state()} leaving the
 * pending states ({@code INITIAL} / {@code PINGING}); every wrapped draw takes the same eased alpha ({@link GuiAlpha}) and
 * a small downward offset that closes to 0.
 *
 * <p>1.21.8: the entry draws in {@code render(g, index, top, left, width, height, mouseX, mouseY, hovered, delta)} and the
 * favicon through {@code drawIcon} (26.x: {@code extractContent} / {@code renderIcon}).
 */
@Mixin(ServerSelectionList.OnlineServerEntry.class)
public abstract class ServerEntryPingFadeMixin {

   @Unique private static final float LG_W = 16.0F;       // ~0.33 s
   @Unique private static final float LG_RISE = 3.0F;

   @Shadow @Final private ServerData serverData;

   @Unique private ServerData.State lg$lastState;
   @Unique private long lg$resultNs;

   @Inject(method = "render", at = @At("HEAD"))
   private void lg$watchState(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                             boolean hovered, float delta, CallbackInfo ci) {
      ServerData.State s = this.serverData.state();
      boolean pendingBefore = lg$lastState == ServerData.State.INITIAL || lg$lastState == ServerData.State.PINGING;
      boolean pendingNow = s == ServerData.State.INITIAL || s == ServerData.State.PINGING;
      if (lg$lastState != null && pendingBefore && !pendingNow) lg$resultNs = net.minecraft.Util.getNanos();
      lg$lastState = s;
   }

   /** 0..1 progress of the result's entrance (1 = settled / nothing pending). */
   @Unique
   private float lg$p() {
      if (lg$resultNs == 0L) return 1F;
      float t = (net.minecraft.Util.getNanos() - lg$resultNs) / 1.0e9F;
      float p = 1F - (1F + LG_W * t) * (float) Math.exp(-LG_W * t);
      if (p >= 0.998F) { lg$resultNs = 0L; return 1F; }
      return p;
   }

   @Unique
   private boolean lg$begin(GuiGraphics g) {
      float p = lg$p();
      if (p >= 1F) return false;
      float inv = 1F - p;
      GuiAlpha.push(1F - inv * inv);
      g.pose().pushMatrix();
      g.pose().translate(0F, LG_RISE * inv);
      return true;
   }

   @Unique
   private static void lg$end(GuiGraphics g, boolean began) {
      if (!began) return;
      g.pose().popMatrix();
      GuiAlpha.pop();
   }

   @WrapOperation(method = "render", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)V"))
   private void lg$motd(GuiGraphics g, Font font, FormattedCharSequence line, int x, int y, int color, Operation<Void> op) {
      boolean b = lg$begin(g);
      try { op.call(g, font, line, x, y, color); } finally { lg$end(g, b); }
   }

   @WrapOperation(method = "render", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
   private void lg$statusText(GuiGraphics g, Font font, Component text, int x, int y, int color, Operation<Void> op) {
      boolean b = lg$begin(g);
      try { op.call(g, font, text, x, y, color); } finally { lg$end(g, b); }
   }

   @WrapOperation(method = "render", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/ResourceLocation;IIII)V"))
   private void lg$statusIcon(GuiGraphics g, RenderPipeline pipeline, ResourceLocation sprite, int x, int y, int w, int h, Operation<Void> op) {
      boolean b = lg$begin(g);
      try { op.call(g, pipeline, sprite, x, y, w, h); } finally { lg$end(g, b); }
   }

   @WrapOperation(method = "render", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/screens/multiplayer/ServerSelectionList$OnlineServerEntry;drawIcon(Lnet/minecraft/client/gui/GuiGraphics;IILnet/minecraft/resources/ResourceLocation;)V"))
   private void lg$favicon(ServerSelectionList.OnlineServerEntry self, GuiGraphics g, int x, int y, ResourceLocation tex, Operation<Void> op) {
      boolean b = lg$begin(g);
      try { op.call(self, g, x, y, tex); } finally { lg$end(g, b); }
   }
}
