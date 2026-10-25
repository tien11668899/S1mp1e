package dev.s1mp1e.glass.mixin;

import java.util.Optional;
import net.minecraft.client.resource.language.ReorderingUtil;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Style;
import net.minecraft.text.TextVisitFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Performance (port of mc263 {@code BidiFastPathMixin}): every string drawn goes through
 * {@code TranslationStorage.reorder} → {@code ReorderingUtil.reorder}, which runs ICU's bidirectional algorithm (plus
 * Arabic shaping) on it every frame — 3.6% of the render thread in the 26.3 4K benchmark (docs/PERFORMANCE.md), for HUD
 * text that never needs it. Text with no right-to-left or explicit-embedding character, in a left-to-right UI language,
 * comes out of that algorithm in its logical order with nothing mirrored or shaped, so it gets the plain in-order
 * sequence instead — the same one vanilla's non-bidi {@code Language.reorder} returns. Everything else (Arabic, Hebrew,
 * embedding controls, an RTL UI language) still goes through vanilla.
 */
@Mixin(ReorderingUtil.class)
public abstract class BidiFastPathMixin {

    @Inject(method = "reorder", at = @At("HEAD"), cancellable = true)
    private static void s1mp1e$leftToRightFastPath(StringVisitable text, boolean rightToLeft,
                                                   CallbackInfoReturnable<OrderedText> cir) {
        if (rightToLeft || s1mp1e$needsBidi(text)) return;
        cir.setReturnValue(visitor -> text.visit((style, contents) -> TextVisitFactory.visitFormatted(contents, style, visitor)
                ? Optional.empty() : StringVisitable.TERMINATE_VISIT, Style.EMPTY).isPresent());
    }

    private static boolean s1mp1e$needsBidi(StringVisitable text) {
        return text.visit(s -> s1mp1e$hasBidiChar(s) ? StringVisitable.TERMINATE_VISIT : Optional.empty()).isPresent();
    }

    /** True for any character that can make the bidi algorithm reorder, mirror or shape: strong RTL, Arabic letters
     *  and digits, and the explicit embedding / override / isolate controls. Everything below U+0590 is skipped
     *  without a table lookup. */
    private static boolean s1mp1e$hasBidiChar(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x0590) continue;
            switch (Character.getDirectionality(cp)) {
                case Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                     Character.DIRECTIONALITY_ARABIC_NUMBER,
                     Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING, Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE,
                     Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING, Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE,
                     Character.DIRECTIONALITY_POP_DIRECTIONAL_FORMAT,
                     Character.DIRECTIONALITY_LEFT_TO_RIGHT_ISOLATE, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ISOLATE,
                     Character.DIRECTIONALITY_FIRST_STRONG_ISOLATE, Character.DIRECTIONALITY_POP_DIRECTIONAL_ISOLATE -> {
                    return true;
                }
                default -> { }
            }
        }
        return false;
    }
}
