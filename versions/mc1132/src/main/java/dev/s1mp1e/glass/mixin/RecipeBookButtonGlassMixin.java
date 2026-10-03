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
import net.minecraft.class_3256;
import net.minecraft.class_4218;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The recipe-book open/close button becomes a liquid-glass capsule like every other glass button — the 1.13.2 port of
 * the 1.20.1 / 1.21.1 lines' (26.2's) {@code RecipeBookButtonGlassMixin}. A {@link class_3256} overrides
 * {@code renderButton} outright (its texture is the whole button), so {@code ButtonGlassMixin} never sees it and this
 * button was left a grey vanilla square next to the glass panel.
 *
 * <h3>Seam (javap-read on 1.13.2)</h3>
 * {@code InventoryScreen} / {@code CraftingScreen} / {@code AbstractFurnaceScreen} build the button as a
 * {@code class_3256} with {@code textures/gui/recipe_button.png} (a 20x18 cell, the hovered one 19 px
 * below — there are no GUI sprites yet). {@code class_3256.renderButton} binds its texture and makes one
 * call of the static {@code drawTexture(MatrixStack, x, y, u, v, w, h, texW, texH)}; that call is redirected and
 * gated on the widget's texture, so ONLY the buttons named below are touched — every other
 * {@code class_3256} keeps its vanilla draw.
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
 *
 * <p><b>1.13.2.</b> The textured button is the intermediary-only {@code class_3256} (GuiButtonImage): fields
 * {@code field_15891} texture, {@code field_15892 / 15893} u / v, {@code field_15894} the hovered v offset; its
 * {@code method_891} makes one instance {@code drawTexture(IIIIII)} on a 256 px sheet. Only the recipe-book button
 * ({@code textures/gui/recipe_button.png}) is such a button in this version (the title screen's language button is
 * the separate {@code LanguageButton} class, there is no accessibility button).
 */

@Mixin(class_3256.class)
public abstract class RecipeBookButtonGlassMixin {

    @Unique private static final float LIFT_ON = 0.81f;
    @Unique private static final float HOVER_MS = 100f;
    @Unique private static final WeakHashMap<class_3256, Fade> s1mp1e$hover = new WeakHashMap<>();

    @Shadow @Final private Identifier field_15891;

    @Redirect(method = "method_891",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/class_3256;drawTexture(IIIIII)V"))
    private void s1mp1e$glassBookButton(class_3256 self, int x, int y, int u, int v, int w, int h) {
        class_3256 btn = (class_3256) (Object) this;
        Identifier texture = this.field_15891;
        String path = texture.getPath();
        if ("minecraft".equals(texture.getNamespace()) && "textures/gui/recipe_button.png".equals(path)
                && GlassProgram.ensureReady() && GlassProgram.btnUsable()) {
            boolean over = btn.isHovered();
            Fade hover = s1mp1e$hover.get(btn);
            if (hover == null) {
                hover = new Fade(over ? 1f : 0f, HOVER_MS);
                s1mp1e$hover.put(btn, hover);
            }
            hover.to(over ? 1f : 0f);
            float lift = LIFT_ON * hover.value();
            float opacity = ScreenOpenFade.value(MinecraftClient.getInstance().currentScreen);
            GlassRenderer.button(x, y, x + w, y + h, 1.0f, lift, opacity, btn.active);
            // the capsule is a raw-GL draw: put back what vanilla set up for its blit; the vanilla button art is then
            // drawn back on top so the book icon still reads (as on 1.21.1 - no new asset is shipped)
            GlStateManager.enableBlend();
            GlStateManager.blendFuncSeparate(770, 771, 1, 0);
            GlStateManager.color(1f, 1f, 1f, 1f);
            MinecraftClient.getInstance().getTextureManager().bindTexture(texture);
        }
        self.drawTexture(x, y, u, v, w, h);
    }
}
