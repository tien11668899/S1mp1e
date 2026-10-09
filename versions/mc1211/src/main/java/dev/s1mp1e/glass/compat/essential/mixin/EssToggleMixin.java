package dev.s1mp1e.glass.compat.essential.mixin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import dev.s1mp1e.glass.compat.essential.EssentialGlass;
import gg.essential.elementa.UIComponent;
import gg.essential.universal.UMatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Essential's toggle ({@code EssentialToggle}: settings rows, social / wardrobe switches) → the S1mp1e iOS glass switch.
 * Its value / enabled live in Essential's own state classes (not on our compile path), so they are read reflectively.
 */
@Pseudo
@Mixin(targets = "gg.essential.gui.common.EssentialToggle", remap = false)
public abstract class EssToggleMixin {

   @Unique private static Field lg$valueField, lg$enabledField;
   @Unique private static Method lg$getUntracked;
   @Unique private static boolean lg$reflectFailed;

   @Inject(method = "draw(Lgg/essential/universal/UMatrixStack;)V", at = @At("HEAD"), cancellable = true)
   private void lg$glassSwitch(UMatrixStack stack, CallbackInfo ci) {
      if (!EssentialGlass.recording() || lg$reflectFailed) return;
      Boolean value = lg$read(true), enabled = lg$read(false);
      if (value == null) return;
      UIComponent self = (UIComponent) (Object) this;
      if (EssentialGlass.toggle(this, stack, self.getLeft(), self.getTop(), self.getRight(), self.getBottom(),
            value, enabled == null || enabled)) {
         ci.cancel();
      }
   }

   /** Essential 1.5: the toggle extracts (immediately on 1.21.1) instead of drawing — same fields. */
   @Inject(method = "extractComponent(Lgg/essential/elementa/renderer/ElementaExtractor;)V", at = @At("HEAD"), cancellable = true,
         require = 0)
   private void lg$glassSwitchExtract(@org.spongepowered.asm.mixin.injection.Coerce Object extractor, CallbackInfo ci) {
      if (!EssentialGlass.recording() || lg$reflectFailed) return;
      Boolean value = lg$read(true), enabled = lg$read(false);
      if (value == null) return;
      UIComponent self = (UIComponent) (Object) this;
      boolean on = enabled == null || enabled;
      if (EssentialGlass.toggleExtract(this, extractor, self.getLeft(), self.getTop(), self.getRight(), self.getBottom(), value, on)) {
         ci.cancel();
         return;
      }
      UMatrixStack stack = EssentialGlass.stackOf(extractor);
      if (stack != null && EssentialGlass.toggle(this, stack, self.getLeft(), self.getTop(), self.getRight(), self.getBottom(), value, on)) {
         ci.cancel();
      }
   }

   @Unique
   private Boolean lg$read(boolean value) {
      try {
         if (lg$getUntracked == null) {
            Class<?> toggle = Class.forName("gg.essential.gui.common.EssentialToggle", false, getClass().getClassLoader());
            lg$valueField = toggle.getDeclaredField("value");
            lg$valueField.setAccessible(true);
            lg$enabledField = toggle.getDeclaredField("enabled");
            lg$enabledField.setAccessible(true);
            lg$getUntracked = Class.forName("gg.essential.gui.elementa.state.v2.State", false, getClass().getClassLoader())
                  .getMethod("getUntracked");
         }
         Object state = (value ? lg$valueField : lg$enabledField).get(this);
         Object v = state == null ? null : lg$getUntracked.invoke(state);
         return v instanceof Boolean bool ? bool : null;
      } catch (Throwable t) {
         lg$reflectFailed = true;   // Essential changed its fields: leave its own toggle alone
         return null;
      }
   }
}
