package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.PanelGhost;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * System 2 (container glass), creative-inventory variant. 1.20.1 Fabric port of
 * the 26.2 creative item-panel glass.
 *
 * <h3>Why a dedicated mixin</h3>
 * {@link CreativeInventoryScreen} OVERRIDES both {@code render} and
 * {@code drawBackground}, and paints its OWN chrome (category tab row, search box,
 * scrollbar) from inside {@code drawBackground}. The base
 * {@link HandledScreenGlassMixin} therefore holds off on the creative screen (its
 * {@code @Inject} guard returns early when {@code this instanceof
 * CreativeInventoryScreen}), and this mixin does a SURGICAL replacement instead:
 * only the big item-area panel blit becomes glass; every other draw in
 * {@code drawBackground} runs untouched.
 *
 * <h3>The seam — verified from 1.17.1 bytecode (yarn 1.17.1+build.65)</h3>
 * {@code CreativeInventoryScreen.drawBackground}
 * ({@code (Lnet/minecraft/client/util/math/MatrixStack;FII)V}) issues exactly TWO
 * {@code drawTexture(MatrixStack,IIIIII)} blits, both resolved through the constant
 * pool as {@code #1089 = CreativeInventoryScreen.drawTexture:(...)V} — i.e. the
 * INVOKE owner is {@code CreativeInventoryScreen} itself (javac emits the static
 * type of the {@code this} receiver, NOT the declaring {@code DrawableHelper}).
 * <ul>
 *   <li>ordinal 0 (offset 131): {@code drawTexture(matrices, this.x, this.y, 0,
 *       0, backgroundWidth, backgroundHeight)} — the big 195x136 item panel
 *       (args verified at offsets 114/118/121/122/124/128). THIS is redirected to
 *       glass.</li>
 *   <li>ordinal 1 (offset 246, guarded by {@code hasScrollbar}): the scrollbar knob
 *       blit — left ALONE, so the scrollbar keeps working.</li>
 * </ul>
 * Everything else in {@code drawBackground} is a different INVOKE and is never
 * touched: the tab sprites+icons ({@code renderTabIcon}) and the search field
 * ({@code TextFieldWidget.render}). Vanilla text, labels and item stacks are painted
 * later in {@code render} and also survive.
 *
 * <p>Style, knobs and springs mirror {@link HandledScreenGlassMixin}: grab the
 * backdrop the instant before the glass draw, drive an open fade, and register the
 * rect with {@link PanelGhost} so the panel fades out on close.
 */
@Mixin(CreativeInventoryScreen.class)
public abstract class CreativeGlassMixin {

    // drawTexture is inherited from DrawableHelper (public); NOT @Shadow'd — @Shadow
    // of an inherited method throws "not located in target class" at apply time. The
    // shader-unavailable fallback instead calls it through the redirect's `self`
    // param, whose static type CreativeInventoryScreen publicly inherits drawTexture.

    // Per-screen-instance open fade (fresh with each opened creative screen).
    private Fade s1mp1e$openFade;
    private boolean s1mp1e$opened;

    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 0,
                     target = "Lnet/minecraft/client/gui/DrawContext;"
                            + "drawTexture(Lnet/minecraft/util/Identifier;IIIIII)V"))
    private void s1mp1e$glassItemPanel(DrawContext self, Identifier texture,
                                       int x, int y, int u, int v, int w, int h) {
        // Glass REPLACES the item-panel PNG (the 26.2 design): the redirect
        // swallows the blit and paints frosted glass over the same rect. Tabs,
        // search box and scrollbar are separate INVOKEs and draw as normal.
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            self.drawTexture(texture, x, y, u, v, w, h);
            return;
        }

        if (s1mp1e$openFade == null) {
            s1mp1e$openFade = new Fade(0f, PanelGhost.FADE_MS);
        }

        // Backdrop = world + dim (+ any tab sprites already painted this frame),
        // grabbed the instant before the glass draw.
        SceneCapture.grab();

        if (!s1mp1e$opened) {
            // Fresh screen instance: snap the open fade and cancel any in-flight
            // close ghost.
            s1mp1e$opened = true;
            s1mp1e$openFade.snap(0f);
            s1mp1e$openFade.to(1f);
            PanelGhost.cancel();
        }
        float fade = s1mp1e$openFade.value();

        // The item panel is the only glass rect this screen registers, so it
        // owns beginFrame(); the close ghost then fades exactly this rect.
        PanelGhost.beginFrame();
        PanelGhost.remember(x, y, w, h);
        GlassRenderer.panel(x, y, x + w, y + h, fade);
    }
}
