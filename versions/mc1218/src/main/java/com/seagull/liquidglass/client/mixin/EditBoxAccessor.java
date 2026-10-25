package com.seagull.liquidglass.client.mixin;

import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to the EditBox layout fields the chat-close ghost needs (what was visible, and where). */
@Mixin(EditBox.class)
public interface EditBoxAccessor {
   @Accessor("displayPos") int liquidglass$displayPos();
   @Accessor("textX") int liquidglass$textX();
   @Accessor("textY") int liquidglass$textY();
}
