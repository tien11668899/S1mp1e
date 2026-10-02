package dev.s1mp1e.glass.mixin;

import java.util.List;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.s1mp1e.glass.render.ChatArrival;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wires {@link ChatArrival} into the chat: track arrivals at the top of {@code render}, push the whole line column down by
 * the pending offset right where vanilla sets up the chat pose, and multiply each line's opacity by its entrance fade.
 *
 * <p>1.16.5 ({@code ChatHud.render(MatrixStack, int)}, javap-read): the pose is {@code translate(4.0, 8.0, 0.0)} and
 * THEN {@code scale(chatScale)}, so the offset — measured in chat line heights — is added to that first translate's Y
 * in screen pixels ({@code offset * chatScale}). This line's {@code ChatGlassMixin} draws its glass panel right after
 * that scale, in the pose's local space, so the panel rides the same shift (as on 1.21.1) and stays glued to the lines
 * it backs.
 */
@Mixin(ChatHud.class)
public abstract class ChatArrivalMixin {

   @Shadow @Final private List<ChatHudLine<net.minecraft.text.OrderedText>> visibleMessages;
   @Shadow private int scrolledLines;
   @Shadow @Final private net.minecraft.client.MinecraftClient client;

   /** 1.16.5 has no {@code getLineHeight()}: {@code render} computes {@code 9.0 * (chatLineSpacing + 1.0)} inline. */
   @org.spongepowered.asm.mixin.Unique
   private int getLineHeight() { return (int) (9.0 * (this.client.options.chatLineSpacing + 1.0)); }
   @Shadow public abstract double getChatScale();

   @Inject(method = "render", at = @At("HEAD"))
   private void lg$trackArrivals(MatrixStack matrices, int currentTick, CallbackInfo ci) {
      ChatArrival.track(this.visibleMessages, this.scrolledLines);
   }

   /** The chat pose translate is {@code translate(4, 8, 0)} before the scale; add the arrival column offset to its Y. */
   @ModifyArg(
      method = "render",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/math/MatrixStack;translate(DDD)V", ordinal = 0),
      index = 1
   )
   private double lg$slideColumn(double y) {
      return y + ChatArrival.offset(this.getLineHeight()) * this.getChatScale();
   }

   /** Each visible line's opacity is multiplied by its entrance fade (0 at arrival, easing to 1). */
   @ModifyExpressionValue(
      method = "render",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/ChatHud;getMessageOpacityMultiplier(I)D")
   )
   private double lg$lineEntrance(double opacity, @Local(ordinal = 0) ChatHudLine<?> line) {
      return opacity * ChatArrival.lineFade(line);
   }
}
