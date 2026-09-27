package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.font.GlyphAtlasTexture;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The 1.14.4 form of FontSmoothMixin. The whole-game PingFang TTF is drawn from
 * dynamically-built glyph atlases; without linear filtering the up-scaled glyphs stair-step.
 *
 * <p>mc1201's approach (@ModifyVariable on {@code AbstractTexture.setFilter(ZZ)V}) compiles
 * here but does NOTHING: 1.14.4 never calls {@code setFilter} on a font atlas —
 * {@code TextureUtil.prepareImage} leaves the default NEAREST minification. So instead we hook
 * {@link GlyphAtlasTexture}'s constructor at {@code RETURN} (after its own prepareImage has run)
 * and switch the just-built atlas to LINEAR ourselves.
 *
 * <p>Only the TTF atlases turn LINEAR: {@code TtfGlyph.hasColor()} is {@code false} while RGBA
 * bitmap glyphs are {@code true}, so vanilla pixel fonts stay crisp. Wrapped in try/catch so a
 * failure disables the smoothing rather than crashing the game.
 */
@Mixin(GlyphAtlasTexture.class)
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
