package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.SuggestionsFade;
import net.minecraft.client.gui.screen.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Command suggestions appear / disappear with a short fade instead of popping (26.2 parity). 1.13.2 has no
 * {@code CommandSuggestor} (1.15+): the chat screen owns the suggestion window itself and draws it from its own
 * {@code render}, so the frame is bracketed here and the window reports its draw through
 * {@code SuggestionsListGlideMixin}; see {@link SuggestionsFade}.
 */
@Mixin(ChatScreen.class)
public abstract class CommandSuggestionsFadeMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$suggestionsBegin(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        SuggestionsFade.beginFrame();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void s1mp1e$suggestionsGhost(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        SuggestionsFade.endFrame();
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void s1mp1e$suggestionsReset(CallbackInfo ci) {
        SuggestionsFade.reset();
    }
}
