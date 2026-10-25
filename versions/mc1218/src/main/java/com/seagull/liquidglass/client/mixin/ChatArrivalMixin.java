package com.seagull.liquidglass.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.seagull.liquidglass.client.render.ChatArrival;
import java.util.List;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wires {@link ChatArrival} into the chat: track arrivals at the top of {@code render}, push the whole line column
 * down by the pending offset right after vanilla sets up the chat pose (scale + indent), and multiply each line's
 * opacity by its entrance fade.
 *
 * <p>1.21.8 (javap): {@code render(g, tickCount, mouseX, mouseY, focused)} pushes the pose, {@code scale(s, s)} and
 * {@code translate(4, 0)} (the first translate — the queue indicator's is the second); {@code forEachLine} hands each
 * line's opacity to the (package-private) {@code LineConsumer.accept(x, y0, y1, line, index, alpha)} for both the
 * background and the text pass. 26.x: {@code ChatGraphicsAccess.updatePose} / {@code AlphaCalculator.calculate}.
 */
@Mixin(ChatComponent.class)
public abstract class ChatArrivalMixin {

   @Shadow @Final private List<GuiMessage.Line> trimmedMessages;
   @Shadow private int chatScrollbarPos;
   @Shadow private int getLineHeight() { return 0; }

   @Inject(method = "render", at = @At("HEAD"))
   private void lg$trackArrivals(GuiGraphics g, int tickCount, int mouseX, int mouseY, boolean focused, CallbackInfo ci) {
      ChatArrival.track(this.trimmedMessages, this.chatScrollbarPos);
   }

   @Inject(method = "render", at = @At(value = "INVOKE", ordinal = 0, shift = At.Shift.AFTER,
         target = "Lorg/joml/Matrix3x2fStack;translate(FF)Lorg/joml/Matrix3x2f;"))
   private void lg$slideColumn(GuiGraphics g, int tickCount, int mouseX, int mouseY, boolean focused, CallbackInfo ci) {
      float off = ChatArrival.offset(this.getLineHeight());
      if (off > 0.01F) g.pose().translate(0.0F, off);
   }

   @ModifyArg(method = "forEachLine", index = 5, at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/components/ChatComponent$LineConsumer;accept(IIILnet/minecraft/client/GuiMessage$Line;IF)V"))
   private float lg$lineEntrance(float alpha, @Local GuiMessage.Line line) {
      return alpha * ChatArrival.lineFade(line);
   }
}
