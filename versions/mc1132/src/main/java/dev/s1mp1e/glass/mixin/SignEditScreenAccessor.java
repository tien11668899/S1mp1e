package dev.s1mp1e.glass.mixin;

import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.gui.screen.ingame.SignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 1.13.2: the row being typed into lives in the edit screen (the sign only carries the blinking marker row). */
@Mixin(SignEditScreen.class)
public interface SignEditScreenAccessor {
    @Accessor("sign") SignBlockEntity s1mp1e$sign();

    @Accessor("currentRow") int s1mp1e$currentRow();
}
