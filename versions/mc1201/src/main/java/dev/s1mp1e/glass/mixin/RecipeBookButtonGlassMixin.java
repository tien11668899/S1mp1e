package dev.s1mp1e.glass.mixin;

import java.util.WeakHashMap;

import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TexturedButtonWidget;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The recipe-book open/close button becomes a liquid-glass capsule like every other glass button — the 1.20.1 port of
 * the 1.21.1 line's (26.2's) {@code RecipeBookButtonGlassMixin}. {@code ButtonGlassMixin} deliberately leaves every
 * {@link TexturedButtonWidget} vanilla (its texture is the whole button) and skips container screens altogether, so
 * this button was left a grey vanilla square next to the glass panel.
 *
 * <h3>Seam (decompiled 1.20.1)</h3>
 * {@code InventoryScreen} / {@code CraftingScreen} / {@code AbstractFurnaceScreen} build the button as a
 * {@code TexturedButtonWidget} with {@code textures/gui/recipe_button.png} (a 20x18 cell, the hovered one 19 px
 * below — there are no GUI sprites yet). {@code TexturedButtonWidget.renderButton} is one call of the inherited
 * {@code drawTexture(DrawContext, Identifier, x, y, u, v, hoveredVOffset, w, h, texW, texH)} helper; that call is
 * redirected and gated on the texture path, so ONLY the recipe-book button is touched — every other
 * {@code TexturedButtonWidget} (the title screen's language / accessibility buttons, …) keeps its vanilla draw.
 *
 * <h3>Look</h3>
 * The {@code BTN} capsule program (no backdrop → no {@code SceneCapture.grab}) with the shared hover-lift ease and the
 * {@link ScreenOpenFade} screen-open ramp, exactly like {@code ButtonGlassMixin}; the vanilla button texture is then
 * drawn back ON TOP so the book icon still reads, as on 1.21.1 (no new asset is shipped).
 */
@Mixin(TexturedButtonWidget.class)
public abstract class RecipeBookButtonGlassMixin {

    @Unique private static final float LIFT_ON = 0.81f;
    @Unique private static final float HOVER_MS = 100f;
    @Unique private static final WeakHashMap<TexturedButtonWidget, Fade> s1mp1e$hover = new WeakHashMap<>();

    @Redirect(method = "renderButton",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/widget/TexturedButtonWidget;drawTexture("
                            + "Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/util/Identifier;IIIIIIIII)V"))
    private void s1mp1e$glassBookButton(TexturedButtonWidget btn, DrawContext ctx, Identifier texture, int x, int y,
                                        int u, int v, int hoveredVOffset, int w, int h, int texW, int texH) {
        String path = texture.getPath();
        // The title screen's language (widgets.png 0,106) and accessibility (accessibility.png 0,0) buttons: 1.20.1 bakes
        // the icon into a 20x20 button texture. From 1.20.2 on they are normal buttons with an icon sprite, which the
        // glass button skin turns into a capsule; here the capsule is drawn and the icon is lifted out of the texture.
        int iconGrey = 0;
        if ("minecraft".equals(texture.getNamespace()) && w == 20 && h == 20) {
            if ("textures/gui/widgets.png".equals(path) && u == 0 && v == 106) iconGrey = 0xE0;
            else if ("textures/gui/accessibility.png".equals(path) && u == 0 && v == 0) iconGrey = 0xC0;
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
            ctx.draw();   // land what was queued; the capsule is immediate GL (absolute coords)
            Identifier icon = iconGrey == 0 ? null
                    : dev.s1mp1e.glass.render.ContainerExtras.iconArt(texture, u, v, w, h, iconGrey);
            if (iconGrey != 0 && icon == null) {          // art not recognisable (resource pack): stay vanilla
                btn.drawTexture(ctx, texture, x, y, u, v, hoveredVOffset, w, h, texW, texH);
                return;
            }
            GlassRenderer.button(x, y, x + w, y + h, 1.0f, lift, opacity, btn.active);
            if (icon != null) {
                com.mojang.blaze3d.systems.RenderSystem.enableBlend();
                ctx.setShaderColor(1f, 1f, 1f, opacity);
                ctx.drawTexture(icon, x, y, 0f, 0f, w, h, w, h);
                ctx.setShaderColor(1f, 1f, 1f, 1f);
                return;
            }
        }
        btn.drawTexture(ctx, texture, x, y, u, v, hoveredVOffset, w, h, texW, texH);
    }
}
