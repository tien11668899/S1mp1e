package com.seagull.liquidglass.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seagull.liquidglass.client.render.ChatArrival;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.joml.Matrix3x2f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wires {@link ChatArrival} into the chat: track arrivals at the top of the public extract, push the whole line column
 * down by the pending offset right after vanilla sets up the chat pose (scale + indent), and multiply each line's
 * opacity by its entrance fade.
 */
@Mixin(ChatComponent.class)
public abstract class ChatArrivalMixin {

   @Shadow @Final private List<GuiMessage.Line> trimmedMessages;
   @Shadow private int chatScrollbarPos;
   @Shadow private int getLineHeight() { return 0; }

   @Inject(
      method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V",
      at = @At("HEAD")
   )
   private void lg$trackArrivals(GuiGraphicsExtractor g, Font font, int tickCount, int mouseX, int mouseY,
                                 ChatComponent.DisplayMode mode, boolean focused, CallbackInfo ci) {
      ChatArrival.track(this.trimmedMessages, this.chatScrollbarPos);
   }

   @WrapOperation(
      method = "extractRenderState(Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;IILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;)V",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;updatePose(Ljava/util/function/Consumer;)V")
   )
   private void lg$slideColumn(ChatComponent.ChatGraphicsAccess access, Consumer<Matrix3x2f> setup, Operation<Void> original) {
      original.call(access, setup);
      float off = ChatArrival.offset(this.getLineHeight());
      if (off > 0.01F) access.updatePose(m -> m.translate(0.0F, off));
   }

   /** {@code AlphaCalculator} is private: modify the returned value and take the line from the loop's local instead. */
   @ModifyExpressionValue(
      method = "forEachLine",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/components/ChatComponent$AlphaCalculator;calculate(Lnet/minecraft/client/multiplayer/chat/GuiMessage$Line;)F")
   )
   private float lg$lineEntrance(float alpha, @Local GuiMessage.Line line) {
      return alpha * ChatArrival.lineFade(line);
   }
}
