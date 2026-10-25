package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.AllGlass;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatInputSuggestor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Command usage hints above the chat input: opaque black bars → rounded dark scrims at vanilla's opacity. */
@Mixin(ChatInputSuggestor.class)
public abstract class UsageGlassMixin {

    @WrapOperation(method = "renderMessages", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V"))
    private void s1mp1e$round(DrawContext ctx, int x0, int y0, int x1, int y1, int argb, Operation<Void> original) {
        AllGlass.scrim(ctx, x0, y0, x1, y1, 3f, (argb & 0xFF000000) | 0x101014);
    }
}
