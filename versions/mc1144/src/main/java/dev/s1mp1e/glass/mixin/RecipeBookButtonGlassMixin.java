package dev.s1mp1e.glass.mixin;

import java.util.WeakHashMap;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.gui.GuiAlpha;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.ContainerExtras;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.widget.TexturedButtonWidget;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The recipe-book open/close button becomes a liquid-glass capsule like every other glass button — the 1.14.4 port of
 * the 1.20.1 / 1.21.1 lines' (26.2's) {@code RecipeBookButtonGlassMixin}. A {@link TexturedButtonWidget} overrides
 * {@code renderButton} outright (its texture is the whole button), so {@code ButtonGlassMixin} never sees it and this
 * button was left a grey vanilla square next to the glass panel.
 *
 * <h3>Seam (javap-read on 1.14.4)</h3>
 * {@code InventoryScreen} / {@code CraftingScreen} / {@code AbstractFurnaceScreen} build the button as a
 * {@code TexturedButtonWidget} with {@code textures/gui/recipe_button.png} (a 20x18 cell, the hovered one 19 px
 * below — there are no GUI sprites yet). {@code TexturedButtonWidget.renderButton} binds its texture and makes one
 * call of the static {@code drawTexture(MatrixStack, x, y, u, v, w, h, texW, texH)}; that call is redirected and
 * gated on the widget's texture, so ONLY the buttons named below are touched — every other
 * {@code TexturedButtonWidget} keeps its vanilla draw.
 *
 * <h3>Look</h3>
 * The {@code BTN} capsule program (no backdrop → no {@code SceneCapture.grab}) with the shared hover-lift ease and the
 * {@link ScreenOpenFade} screen-open ramp, exactly like {@code ButtonGlassMixin}; the vanilla button texture is then
 * drawn back ON TOP so the book icon still reads, as on 1.21.1 (no new asset is shipped).
 *
 * <p>Also the title screen's language ({@code widgets.png} 0,106) and accessibility ({@code accessibility.png} 0,0)
 * buttons: this version bakes the icon into a 20x20 button texture. From 1.20.2 on they are normal buttons with an
 * icon sprite, which the glass button skin turns into a capsule; here the capsule is drawn and the icon is lifted out
 * of the texture ({@link ContainerExtras#iconArt}) — an icon button must not stay a grey vanilla square, nor become an
 * empty capsule.
 */
@Mixin(TexturedButtonWidget.class)
public abstract class RecipeBookButtonGlassMixin {

    @Unique private static final float LIFT_ON = 0.81f;
    @Unique private static final float HOVER_MS = 100f;
    @Unique private static final WeakHashMap<TexturedButtonWidget, Fade> s1mp1e$hover = new WeakHashMap<>();

    @Shadow @Final private Identifier texture;

    @Redirect(method = "renderButton",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/widget/TexturedButtonWidget;blit(IIFFIIII)V"))
    private void s1mp1e$glassBookButton(int x, int y, float u, float v, int w, int h,
                                        int texW, int texH) {
        TexturedButtonWidget btn = (TexturedButtonWidget) (Object) this;
        Identifier texture = this.texture;
        String path = texture.getPath();
        int iu = (int) u, iv = (int) v;
        int iconGrey = 0;
        int baseV = iv;                                   // the un-hovered cell (vanilla adds hoveredVOffset when hovered)
        if ("minecraft".equals(texture.getNamespace()) && w == 20 && h == 20 && iu == 0) {
            if ("textures/gui/widgets.png".equals(path) && (iv == 106 || iv == 126)) { iconGrey = 0xE0; baseV = 106; }
            else if ("textures/gui/accessibility.png".equals(path) && (iv == 0 || iv == 20)) { iconGrey = 0xC0; baseV = 0; }
        }
        if (("textures/gui/recipe_button.png".equals(path) || iconGrey != 0)
                && GlassProgram.ensureReady() && GlassProgram.btnUsable()) {
            boolean over = btn.isHovered() || btn.isFocused();
            Fade hover = s1mp1e$hover.get(btn);
            if (hover == null) {
                hover = new Fade(over ? 1f : 0f, HOVER_MS);
                s1mp1e$hover.put(btn, hover);
            }
            hover.to(over ? 1f : 0f);
            float lift = LIFT_ON * hover.value();
            float opacity = ScreenOpenFade.value(MinecraftClient.getInstance().currentScreen);
            Identifier icon = iconGrey == 0 ? null : ContainerExtras.iconArt(texture, iu, baseV, w, h, iconGrey);
            if (iconGrey != 0 && icon == null) {          // art not recognisable (resource pack): stay vanilla
                DrawableHelper.blit(x, y, u, v, w, h, texW, texH);
                return;
            }
            GlassRenderer.button(x, y, x + w, y + h, 1.0f, lift, opacity, btn.active);
            // the capsule is a raw-GL draw: put back what vanilla set up for its blit
            GlStateManager.enableBlend();
            GlStateManager.blendFuncSeparate(770, 771, 1, 0);
            if (icon != null) {
                net.minecraft.client.MinecraftClient.getInstance().getTextureManager().bindTexture(icon);
                GlStateManager.color4f(1f, 1f, 1f, opacity);
                DrawableHelper.blit(x, y, 0f, 0f, w, h, w, h);
                GlStateManager.color4f(1f, 1f, 1f, 1f);
                return;
            }
            net.minecraft.client.MinecraftClient.getInstance().getTextureManager().bindTexture(texture);
        }
        DrawableHelper.blit(x, y, u, v, w, h, texW, texH);
    }
}
