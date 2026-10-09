package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.AllGlass;
import net.minecraft.client.gui.screen.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * #14 — command usage / parse-error hints above the chat input: opaque black bars → rounded dark scrims at vanilla's
 * opacity.
 *
 * <p><b>1.13.2 has no {@code CommandSuggestor} class</b> (the 1.16+ target): the suggestion/usage UI lives inside
 * {@code ChatScreen}. {@code ChatScreen.render(IIF)V} draws TWO inherited static {@code ChatScreen.fill(IIIII)V}
 * (javap-verified, offsets 23 / 142): ordinal 0 is the chat INPUT-bar background (glassed by
 * {@code ChatInputGlassMixin}); ordinal 1 is the per-line background behind each usage/error string (followed by a
 * {@code TextRenderer.drawWithShadow} at offset 176). Only ordinal 1 is wrapped here into a rounded dark scrim
 * (keeping the original opacity), leaving the input bar to its own mixin. The suggestion-window rows are a different
 * method ({@code ChatScreen$class_4155.method_18549}, handled by {@code SuggestionsListGlideMixin}).
 */
@Mixin(ChatScreen.class)
public abstract class UsageGlassMixin {

    @WrapOperation(method = "render", at = @At(value = "INVOKE", ordinal = 1,
            target = "Lnet/minecraft/client/gui/screen/ChatScreen;fill(IIIII)V"))
    private void s1mp1e$round(int x0, int y0, int x1, int y1, int argb, Operation<Void> original) {
        AllGlass.scrim(x0, y0, x1, y1, 3f, (argb & 0xFF000000) | 0x101014);
        AllGlass.afterFill();
    }
}
