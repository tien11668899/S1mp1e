package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.font.GlyphAtlasTexture;
import net.minecraft.client.font.GlyphRenderer;
import net.minecraft.client.font.RenderableGlyph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Anti-aliases the TTF font (1.19.2, upload side). Our PingFang glyphs are baked by FreeType at
 * {@code size x oversample} and displayed at ~9px, so a {@code GL_NEAREST} minification throws away all of
 * FreeType's grayscale anti-aliasing (the "jagged" edges). {@code GL_LINEAR} makes the downsample a smooth
 * average.
 *
 * <p>On 1.19.2 every glyph upload goes through {@code NativeImage.upload(IIIIIIIZZ)V}, which re-applies
 * {@code GL_NEAREST} to the whole atlas each time a new glyph is added. {@code getGlyphRenderer} (javap-verified
 * in yarn 1.19.2+build.28) binds the atlas itself ({@code bindTexture()} at bc 29) before that upload and
 * returns a non-null renderer only on that path, so at RETURN the atlas is still bound on the active unit and
 * we put {@code GL_LINEAR} (9729) back on MIN (10241) and MAG (10240) of {@code GL_TEXTURE_2D} (3553).
 *
 * <p>The draw side (the text render layers' {@code setFilter(false,false)} on every flush) is handled by
 * {@link FontSmoothFilterMixin}, the reference hook; the two together keep the atlas LINEAR at all times.
 * Off the render thread {@code bindTexture()} only records a deferred bind, so we skip rather than touch
 * whatever texture is currently bound. Scoped to glyph atlases only; any failure leaves vanilla filtering.
 */
@Mixin(GlyphAtlasTexture.class)
public class FontSmoothMixin {

    @Inject(method = "getGlyphRenderer", at = @At("RETURN"))
    private void s1mp1e$linearAfterUpload(RenderableGlyph glyph, CallbackInfoReturnable<GlyphRenderer> cir) {
        try {
            if (cir.getReturnValue() == null) return;          // nothing uploaded / atlas not bound
            if (!RenderSystem.isOnRenderThread()) return;      // bind was deferred: don't touch other textures
            GlStateManager._texParameter(3553, 10241, 9729);   // GL_TEXTURE_MIN_FILTER = GL_LINEAR
            GlStateManager._texParameter(3553, 10240, 9729);   // GL_TEXTURE_MAG_FILTER = GL_LINEAR
        } catch (Throwable ignored) {
            // vanilla NEAREST on failure
        }
    }
}
