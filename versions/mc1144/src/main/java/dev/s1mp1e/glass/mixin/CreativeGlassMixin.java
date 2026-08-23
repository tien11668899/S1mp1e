package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.PanelGhost;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * System 2 (container glass), creative-inventory variant. 1.14.4 tier port of the
 * 1.15.2 mixin (both are the same render tier as the 26.2 creative item-panel glass:
 * RenderSystem/GlStateManager, NO MatrixStack, {@code blit} not {@code drawTexture}).
 *
 * <h3>Why a dedicated mixin</h3>
 * {@link CreativeInventoryScreen} OVERRIDES both {@code render} and
 * {@code drawBackground}. Its {@code render} calls {@code super.render}
 * (AbstractInventoryScreen → ContainerScreen), so the base
 * {@link HandledScreenGlassMixin} {@code @Inject} on {@code ContainerScreen.render}
 * fires for the creative screen too — but drawing the base panel/lattice there for
 * the creative dims would double up with this mixin (and drive {@link PanelGhost}
 * twice). So the base mixin holds off for {@code CreativeInventoryScreen} (see its
 * guard) and this mixin does a SURGICAL replacement: only the big item-area panel
 * blit becomes glass; every other draw in {@code drawBackground} (tab sprites/icons,
 * search box, scrollbar) runs untouched.
 *
 * <h3>The seam — verified from 1.14.4 bytecode (merged obf jar {@code dds})</h3>
 * NO {@code MatrixStack} on this tier. {@code CreativeInventoryScreen.drawBackground}
 * is yarn {@code drawBackground(FII)V} (obf {@code a}); it issues exactly TWO
 * {@code blit(IIIIII)V} invokes (yarn name {@code blit}, formerly
 * {@code drawTexture}), both resolved through the constant pool as
 * {@code #879 = Methodref #2.#878 = dds.blit:(IIIIII)V} — the invoke owner is
 * {@code CreativeInventoryScreen} itself ({@code dds}), NOT the declaring
 * {@code DrawableHelper} (javac emits the {@code this} receiver's static type).
 * <ul>
 *   <li>ordinal 0 (offset 142): {@code blit(this.x, this.y, 0, 0, containerWidth,
 *       containerHeight)} — obf {@code blit(this.f, this.g, 0, 0, this.b, this.c)},
 *       the big 195x136 item panel. THIS is redirected to glass.</li>
 *   <li>ordinal 1 (offset 252): the scrollbar knob blit (x=232, y driven by the
 *       {@code o:F} scroll field, enabled/disabled sprite via {@code f()Z}) — left
 *       ALONE, so the scrollbar keeps working.</li>
 * </ul>
 * The tab sprites are painted in a separate method ({@code renderTabIcon}) whose
 * blits are NOT counted in {@code drawBackground}'s ordinals, and the search field
 * ({@code TextFieldWidget.render}) is a different INVOKE — both survive. Vanilla
 * text, labels and item stacks are painted later in {@code render} and also survive.
 *
 * <p>Style, knobs and springs mirror {@link HandledScreenGlassMixin}: grab the
 * backdrop the instant before the glass draw, drive an open fade, and register the
 * rect with {@link PanelGhost} so the panel fades out on close.
 * {@code compatibilityLevel JAVA_8}; {@code s1mp1e$} member prefix.
 */
@Mixin(CreativeInventoryScreen.class)
public abstract class CreativeGlassMixin {

    // blit is inherited from DrawableHelper (public). NOT @Shadow'd: @Shadow of an
    // inherited method throws "not located in target class" at apply time. The
    // shader-unavailable fallback instead calls it through the redirect's `self`
    // param, whose static type CreativeInventoryScreen publicly inherits blit.

    // Per-screen-instance open fade (fresh with each opened creative screen).
    private Fade s1mp1e$openFade;
    private boolean s1mp1e$opened;

    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 0,
                     target = "Lnet/minecraft/client/gui/screen/ingame/CreativeInventoryScreen;"
                            + "blit(IIIIII)V"))
    private void s1mp1e$glassItemPanel(CreativeInventoryScreen self,
                                       int x, int y, int u, int v, int w, int h) {
        // Glass REPLACES the item-panel PNG (the 26.2 design): the redirect
        // swallows the blit and paints frosted glass over the same rect. Tabs,
        // search box and scrollbar are separate INVOKEs and draw as normal.
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            self.blit(x, y, u, v, w, h);
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
