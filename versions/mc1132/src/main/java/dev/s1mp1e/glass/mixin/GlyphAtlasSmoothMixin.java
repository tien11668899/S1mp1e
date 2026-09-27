package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.class_4132;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The 1.13.2 form of FontSmoothMixin. The whole-game PingFang TTF is drawn from
 * dynamically-built glyph atlases; without linear filtering the up-scaled glyphs stair-step.
 *
 * <p>1.13.2 never calls {@code setFilter} on a font atlas — {@code TextureUtil.method_19531}
 * leaves the default NEAREST. So we hook the glyph-atlas texture {@code class_4132}
 * (GlyphAtlasTexture, extends {@code AbstractTexture}, no static initialiser) at its
 * constructor {@code RETURN} (after its own prepareImage has run) and switch the just-built
 * atlas to LINEAR ourselves. {@code setFilter} only issues {@code texParameter} on the BOUND
 * texture, so we bind the atlas first.
 *
 * <p>Only the TTF atlases turn LINEAR: the constructor's {@code hasColor} is {@code false}
 * for TTF glyphs and {@code true} for RGBA bitmap glyphs, so vanilla pixel fonts stay crisp.
 * Wrapped in try/catch so a failure disables the smoothing rather than crashing the game.
 */
@Mixin(class_4132.class)
public class GlyphAtlasSmoothMixin {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void s1mp1e$smoothTtfAtlas(Identifier id, boolean hasColor, CallbackInfo ci) {
        try {
            if (hasColor) return;                       // RGBA bitmap glyphs stay sharp
            AbstractTexture self = (AbstractTexture) (Object) this;
            GlStateManager.bindTexture(self.getGlId());
            self.setFilter(true, false);                // LINEAR mag/min, no mipmap
        } catch (Throwable ignored) { /* leave the atlas at its default NEAREST filter */ }
    }
}
