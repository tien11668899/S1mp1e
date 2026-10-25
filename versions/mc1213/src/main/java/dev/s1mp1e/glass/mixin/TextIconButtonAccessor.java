package dev.s1mp1e.glass.mixin;

import net.minecraft.client.gui.widget.TextIconButtonWidget;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read the icon of a {@link TextIconButtonWidget} (language / accessibility buttons) so the glass button can draw it. */
@Mixin(TextIconButtonWidget.class)
public interface TextIconButtonAccessor {
    @Accessor("texture") Identifier s1mp1e$texture();
    @Accessor("textureWidth") int s1mp1e$textureWidth();
    @Accessor("textureHeight") int s1mp1e$textureHeight();
}
