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
 * The recipe-book open/close button becomes a liquid-glass capsule like every other glass button — the 1.21.1 port of
 * 26.2's {@code RecipeBookButtonGlassMixin}. In 1.21.1 that button is a {@link TexturedButtonWidget} built with
 * {@code RecipeBookWidget.BUTTON_TEXTURES} (verified: {@code InventoryScreen}/{@code AbstractCraftingScreen} pass those
 * textures to {@code TexturedButtonWidget}); {@code ButtonGlassMixin} deliberately skips {@code TexturedButtonWidget}
 * (its icon buttons paint their own sprite), so the recipe-book button is otherwise left vanilla — this mixin glasses it.
 *
 * <h3>Seam</h3>
 * {@code TexturedButtonWidget.renderWidget} blits its state sprite with a single
 * {@code DrawContext.drawGuiTexture(Identifier, x, y, w, h)} (bytecode-verified: {@code ButtonTextures.get(ZZ)} →
 * {@code drawGuiTexture} at offset 36). The redirect gates on the identifier path so ONLY the recipe-book button (path
 * {@code recipe_book/button…}) is touched; every other {@code TexturedButtonWidget} (advancement tabs, page-turn arrows
 * elsewhere) keeps its vanilla sprite.
 *
 * <h3>Look</h3>
 * The {@code BTN} capsule program (no backdrop → no {@code SceneCapture.grab}) with the shared hover-lift ease and the
 * {@link ScreenOpenFade} screen-open ramp, exactly like {@code ButtonGlassMixin}; the vanilla book sprite is then drawn
 * back ON TOP so the book icon still reads (26.2 stripped the stone frame with a bundled PNG; on 1.21.1 the button
 * sprite is kept as-is over the capsule, whose drop-shadow / refractive rim extend past it to read as glass — no new
 * asset is shipped).
 */
@Mixin(TexturedButtonWidget.class)
public abstract class RecipeBookButtonGlassMixin {

    /** 26.2's hovered lift (G=0x30 -> 1-0x30/255 ~= 0.81), matching ButtonGlassMixin. */
    @Unique private static final float LIFT_ON = 0.81f;
    @Unique private static final float HOVER_MS = 100f;
    @Unique private static final WeakHashMap<TexturedButtonWidget, Fade> s1mp1e$hover = new WeakHashMap<>();

    @Redirect(method = "renderWidget",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/DrawContext;drawGuiTexture(Lnet/minecraft/util/Identifier;IIII)V"))
    private void s1mp1e$glassBookButton(DrawContext self, Identifier texture, int x, int y, int w, int h) {
        if (!texture.getPath().startsWith("recipe_book/button")
                || !GlassProgram.ensureReady() || !GlassProgram.btnUsable()) {
            self.drawGuiTexture(texture, x, y, w, h);
            return;
        }
        TexturedButtonWidget btn = (TexturedButtonWidget) (Object) this;
        boolean over = btn.isHovered() || btn.isFocused();
        Fade hover = s1mp1e$hover.get(btn);
        if (hover == null) {
            hover = new Fade(over ? 1f : 0f, HOVER_MS);
            s1mp1e$hover.put(btn, hover);
        }
        hover.to(over ? 1f : 0f);
        float lift = LIFT_ON * hover.value();
        float opacity = ScreenOpenFade.value(MinecraftClient.getInstance().currentScreen);
        // BTN capsule (immediate GL, absolute coords) then the vanilla book icon on top.
        GlassRenderer.button(x, y, x + w, y + h, 1.0f, lift, opacity, btn.active);
        self.drawGuiTexture(texture, x, y, w, h);
    }
}
