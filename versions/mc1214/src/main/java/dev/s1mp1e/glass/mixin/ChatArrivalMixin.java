package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.s1mp1e.glass.render.ChatArrival;
import java.util.List;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wires {@link ChatArrival} into the chat: track arrivals at the top of {@code render}, push the whole line column down by
 * the pending offset right where vanilla sets up the chat pose (scale + indent), and multiply each line's opacity by its
 * entrance fade. The offset is added to the chat pose translate's Y arg BEFORE {@code ChatGlassMixin} reads the matrix to
 * enqueue the glass panel, so the panel's top edge follows the slide too.
 */
@Mixin(ChatHud.class)
public abstract class ChatArrivalMixin {

   @Shadow @Final private List<ChatHudLine.Visible> visibleMessages;
   @Shadow private int scrolledLines;
   @Shadow private int getLineHeight() { return 0; }

   @Inject(method = "render", at = @At("HEAD"))
   private void lg$trackArrivals(DrawContext ctx, int currentTick, int mouseX, int mouseY, boolean focused, CallbackInfo ci) {
      ChatArrival.track(this.visibleMessages, this.scrolledLines);
   }

   /** The chat pose translate is {@code translate(4, 0, 0)}; add the arrival column offset to its Y (index 1). */
   @ModifyArg(
      method = "render",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/math/MatrixStack;translate(FFF)V", ordinal = 0),
      index = 1
   )
   private float lg$slideColumn(float y) {
      return y + ChatArrival.offset(this.getLineHeight());
   }

   /** Each visible line's opacity is multiplied by its entrance fade (0 at arrival, easing to 1). */
   @ModifyExpressionValue(
      method = "render",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/ChatHud;getMessageOpacityMultiplier(I)D")
   )
   private double lg$lineEntrance(double opacity, @Local(ordinal = 0) ChatHudLine.Visible line) {
      return opacity * ChatArrival.lineFade(line);
   }
}
