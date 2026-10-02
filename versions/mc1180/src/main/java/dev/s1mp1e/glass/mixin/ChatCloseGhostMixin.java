package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.ChatCloseFade;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draw the chat input bar fading out after chat closes. {@code ChatScreen} owns the bar and is gone the instant chat closes,
 * so {@link ChatCloseFade} (armed from {@code ChatScreen.removed()}) lets the HUD paint a ghost of the same frosted glass bar
 * at the same bottom rectangle for ~150 ms, its alpha falling to 0, while the text that was typed lifts off and fades.
 * Hooked right after the {@code chatHud.render(matrices, ticks)} call inside {@code InGameHud.render} (1.18.2 has no
 * separate {@code renderChat}) so it sits with the rest of the chat area. Skipped while a chat screen is open (the
 * screen draws the real bar) and when the glass pipeline is down. The bar is the one this line's
 * {@code ChatInputGlassMixin} draws: hotbar-corner glass + the faint grey scrim.
 */
@Mixin(InGameHud.class)
public abstract class ChatCloseGhostMixin {

   @Inject(method = "render",
           at = @At(value = "INVOKE", shift = At.Shift.AFTER,
                    target = "Lnet/minecraft/client/gui/hud/ChatHud;render(Lnet/minecraft/client/util/math/MatrixStack;I)V"))
   private void lg$chatCloseGhost(MatrixStack hud, float tickDelta, CallbackInfo ci) {
      if (!ChatCloseFade.active()) return;
      // 1.18.2: InGameHud wraps the chatHud.render call in push / translate(0, height - 48) / pop, and this hook runs
      // before that pop — the HUD's stack is still shifted. The bar and the text are in absolute screen coordinates,
      // so they are drawn through a clean stack.
      MatrixStack g = new MatrixStack();
      MinecraftClient mc = MinecraftClient.getInstance();
      if (mc.currentScreen instanceof ChatScreen) return;          // reopened: the screen draws the real bar
      float a = ChatCloseFade.alpha();
      int w = mc.getWindow().getScaledWidth();
      int h = mc.getWindow().getScaledHeight();
      if (a > 0.004F && GlassProgram.ensureReady() && GlassProgram.usable()) {
         int x0 = 2, y0 = h - 14, x1 = w - 2, y1 = h - 2;           // vanilla chat input rectangle
         HudGlass.glassBoxHotbar(x0, y0, x1, y1, 0.9F * a);
         int sa = Math.round(0x33 * a) & 0xFF;
         DrawableHelper.fill(g, x0, y0, x1, y1, sa << 24 | 0x101018);
      }
      // the text that was in the input lifts off: floats up 5 px (ease-out) while fading (ease-in)
      String text = ChatCloseFade.text();
      float q = ChatCloseFade.textProgress();
      if (!text.isEmpty() && q < 1F) {
         float lift = 5.0F * (1F - (1F - q) * (1F - q));
         int alpha = Math.round(255F * (1F - q) * (1F - q)) & 0xFF;
         if (alpha >= 4) {
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            mc.textRenderer.draw(g, text, (float) ChatCloseFade.textX(), ChatCloseFade.textY() - lift, alpha << 24 | 0xE0E0E0);
         }
      }
   }
}
