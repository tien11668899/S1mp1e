package com.seagull.liquidglass.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.s1mp1e.client.gui.GuiAlpha;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
 */
@Mixin(ServerSelectionList.OnlineServerEntry.class)
public abstract class ServerEntryPingFadeMixin {

   @Unique private static final float LG_W = 16.0F;       // ~0.33 s
   @Unique private static final float LG_RISE = 3.0F;

   @Shadow @Final private ServerData serverData;

   @Unique private ServerData.State lg$lastState;
   @Unique private long lg$resultNs;

   @Inject(method = "extractContent", at = @At("HEAD"))
   private void lg$watchState(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float delta, CallbackInfo ci) {
      ServerData.State s = this.serverData.state();
      boolean pendingBefore = lg$lastState == ServerData.State.INITIAL || lg$lastState == ServerData.State.PINGING;
      boolean pendingNow = s == ServerData.State.INITIAL || s == ServerData.State.PINGING;
      if (lg$lastState != null && pendingBefore && !pendingNow) lg$resultNs = net.minecraft.util.Util.getNanos();
      lg$lastState = s;
   }

   /** 0..1 progress of the result's entrance (1 = settled / nothing pending). */
   @Unique
   private float lg$p() {
      if (lg$resultNs == 0L) return 1F;
      float t = (net.minecraft.util.Util.getNanos() - lg$resultNs) / 1.0e9F;
      float p = 1F - (1F + LG_W * t) * (float) Math.exp(-LG_W * t);
      if (p >= 0.998F) { lg$resultNs = 0L; return 1F; }
      return p;
   }

   @Unique
   private boolean lg$begin(GuiGraphicsExtractor g) {
      float p = lg$p();
      if (p >= 1F) return false;
      float inv = 1F - p;
      GuiAlpha.push(1F - inv * inv);
      g.pose().pushMatrix();
      g.pose().translate(0F, LG_RISE * inv);
      return true;
   }

   @Unique
   private static void lg$end(GuiGraphicsExtractor g, boolean began) {
      if (!began) return;
      g.pose().popMatrix();
      GuiAlpha.pop();
   }

   @WrapOperation(method = "extractContent", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)V"))
   private void lg$motd(GuiGraphicsExtractor g, Font font, FormattedCharSequence line, int x, int y, int color, Operation<Void> op) {
      boolean b = lg$begin(g);
      try { op.call(g, font, line, x, y, color); } finally { lg$end(g, b); }
   }

   @WrapOperation(method = "extractContent", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
   private void lg$statusText(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int color, Operation<Void> op) {
      boolean b = lg$begin(g);
      try { op.call(g, font, text, x, y, color); } finally { lg$end(g, b); }
   }

   @WrapOperation(method = "extractContent", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
   private void lg$statusIcon(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h, Operation<Void> op) {
      boolean b = lg$begin(g);
      try { op.call(g, pipeline, sprite, x, y, w, h); } finally { lg$end(g, b); }
   }

   @WrapOperation(method = "extractContent", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/screens/multiplayer/ServerSelectionList$OnlineServerEntry;extractIcon(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/resources/Identifier;)V"))
   private void lg$favicon(ServerSelectionList.OnlineServerEntry self, GuiGraphicsExtractor g, int x, int y, Identifier tex, Operation<Void> op) {
      boolean b = lg$begin(g);
      try { op.call(self, g, x, y, tex); } finally { lg$end(g, b); }
   }
}
