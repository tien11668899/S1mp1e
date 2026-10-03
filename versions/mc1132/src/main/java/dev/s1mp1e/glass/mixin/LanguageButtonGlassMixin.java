package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.glass.render.ContainerExtras;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.widget.LanguageButton;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The language (globe) button of the title screen.
 *
 * <p>From 1.14 on it is a {@code TexturedButtonWidget} and {@code RecipeBookButtonGlassMixin} turns it into a glass
 * capsule with the globe lifted out of {@code widgets.png} ({@link ContainerExtras#iconArt}). In 1.13.2 it is the
 * separate {@code LanguageButton} class, whose {@code method_891} replaces the whole button draw (so the glass button
 * skin never runs for it) with one {@code drawTexture(x, y, 0, 106 | 126, 20, 20)} - 126 is the hovered cell. That one
 * blit is redirected: same capsule, same hover lift and screen-open ramp, same lifted icon. If the globe cannot be
 * recognised in the texture (resource pack) the vanilla blit stays.
 */
@Mixin(LanguageButton.class)
public abstract class LanguageButtonGlassMixin {

    @Unique private static final float LIFT_ON = 0.81f;
    @Unique private static final float HOVER_MS = 100f;
    @Unique private static final Identifier S1_WIDGETS = new Identifier("minecraft", "textures/gui/widgets.png");
    @Unique private Fade s1mp1e$hover;

    @Redirect(method = "method_891",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/gui/widget/LanguageButton;drawTexture(IIIIII)V"))
    private void s1mp1e$glassGlobe(LanguageButton self, int x, int y, int u, int v, int w, int h) {
        if (u == 0 && w == 20 && h == 20 && (v == 106 || v == 126)
                && GlassProgram.ensureReady() && GlassProgram.btnUsable()) {
            Identifier icon = ContainerExtras.iconArt(S1_WIDGETS, 0, 106, w, h, 0xE0);
            if (icon != null) {
                boolean over = v == 126;
                if (this.s1mp1e$hover == null) {
                    this.s1mp1e$hover = new Fade(over ? 1f : 0f, HOVER_MS);
                }
                this.s1mp1e$hover.to(over ? 1f : 0f);
                float lift = LIFT_ON * this.s1mp1e$hover.value();
                float opacity = ScreenOpenFade.value(MinecraftClient.getInstance().currentScreen);
                GlassRenderer.button(x, y, x + w, y + h, 1.0f, lift, opacity, self.active);
                // the capsule is a raw-GL draw: put back what vanilla set up for its blit
                GlStateManager.enableBlend();
                GlStateManager.blendFuncSeparate(770, 771, 1, 0);
                MinecraftClient.getInstance().getTextureManager().bindTexture(icon);
                GlStateManager.color(1f, 1f, 1f, opacity);
                DrawableHelper.drawTexture(x, y, 0f, 0f, w, h, (float) w, (float) h);
                GlStateManager.color(1f, 1f, 1f, 1f);
                return;
            }
            MinecraftClient.getInstance().getTextureManager().bindTexture(S1_WIDGETS);
            GlStateManager.color(1f, 1f, 1f, 1f);
        }
        self.drawTexture(x, y, u, v, w, h);
    }
}
