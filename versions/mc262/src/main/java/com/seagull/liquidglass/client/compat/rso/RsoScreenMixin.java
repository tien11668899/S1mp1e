package com.seagull.liquidglass.client.compat.rso;

import com.seagull.liquidglass.client.compat.RsoGlass;
import me.flashyreese.mods.reeses_sodium_options.client.gui.SodiumVideoOptionsScreen;
import me.flashyreese.mods.reeses_sodium_options.client.gui.frame.tab.TabFrame;
import me.flashyreese.mods.reeses_sodium_options.client.gui.search.SearchTextFieldWidget;
import me.flashyreese.mods.reeses_sodium_options.client.gui.widget.FlatButtonWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the frame during which RSO's widgets are restyled ({@link RsoGlass#active}), and after each layout pass
 * re-spaces the top toolbar (search | donate | hide) and the bottom one (undo | apply | done) onto the uniform
 * {@link RsoGlass#GAP} — RSO leaves 2 / 5 px there and 6 px between the toolbar and the content.
 */
@Mixin(SodiumVideoOptionsScreen.class)
public abstract class RsoScreenMixin {
   @Shadow private FlatButtonWidget applyButton;
   @Shadow private FlatButtonWidget closeButton;
   @Shadow private FlatButtonWidget undoButton;
   @Shadow private FlatButtonWidget donateButton;
   @Shadow private FlatButtonWidget hideDonateButton;
   @Shadow private SearchTextFieldWidget searchTextField;
   @Shadow private TabFrame tabFrame;

   @Inject(method = "init", at = @At("RETURN"))
   private void lg$respace(CallbackInfo ci) {
      RsoGlass.respaceToolbars(this.searchTextField, this.donateButton, this.hideDonateButton,
            this.undoButton, this.applyButton, this.closeButton, this.tabFrame);
   }

   @Inject(method = "extractRenderState", at = @At("HEAD"))
   private void lg$begin(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      RsoGlass.begin();
   }

   @Inject(method = "extractRenderState", at = @At("RETURN"))
   private void lg$end(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      RsoGlass.end();
   }
}
