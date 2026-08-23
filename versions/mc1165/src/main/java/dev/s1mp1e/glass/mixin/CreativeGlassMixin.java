package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.PanelGhost;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * System 2 (container glass), creative-inventory variant.
 *
 * <h3>Why a dedicated mixin</h3>
 * {@link CreativeInventoryScreen} OVERRIDES both {@code render} and
 * {@code drawBackground}. The base {@link HandledScreenGlassMixin} redirects the
 * {@code drawBackground} INVOKE inside {@code HandledScreen.render} and, when it
 * fires, swallows the WHOLE background blit — which for the creative screen would
 * blank the category tab row, the search box and the scrollbar along with the
 * item panel (they are all painted from inside the same {@code drawBackground}).
 * So the creative screen needs the base mixin held off (the human wires that: let
 * the base redirect delegate to vanilla {@code drawBackground} when the screen is
 * a {@code CreativeInventoryScreen}) and this mixin does a SURGICAL replacement
 * instead: only the big item-area panel blit becomes glass; every other draw in
 * {@code drawBackground} runs untouched.
 *
 * <h3>The seam — verified from 1.16.5 bytecode (merged jar)</h3>
 * {@code CreativeInventoryScreen} obf {@code dqc}; its {@code drawBackground}
 * (yarn {@code method_2389}, {@code a(dfm,float,int,int)}) issues exactly TWO
 * {@code drawTexture(MatrixStack,int,int,int,int,int,int)} blits, both resolved
 * through the constant pool as {@code Methodref dqc.b:(Ldfm;IIIIII)V} — i.e. the
 * INVOKE owner is {@code CreativeInventoryScreen} itself (javac emits the static
 * type of the {@code this} receiver, NOT the declaring
 * {@code DrawableHelper}; same gotcha as the base mixin's {@code fillGradient}).
 * The yarn name of {@code b:(Ldfm;IIIIII)V} is
 * {@code DrawableHelper.drawTexture} ({@code method_25302}).
 * <ul>
 *   <li>ordinal 0 (offset 141): {@code drawTexture(matrices, this.x, this.y, 0,
 *       0, backgroundWidth, backgroundHeight)} — the big 195x136 item panel.
 *       THIS is redirected to glass.</li>
 *   <li>ordinal 1 (offset 254): the scrollbar knob blit
 *       ({@code u=232/244, w=12, h=15}) — left ALONE, so the scrollbar keeps
 *       working.</li>
 * </ul>
 * Everything else in {@code drawBackground} is a different INVOKE and is never
 * touched: the unselected/selected tab sprites+icons ({@code renderTabIcon},
 * {@code dqc.a(Ldfm;Lbks;)V}) and the search field
 * ({@code TextFieldWidget.render}, {@code dlq.a(Ldfm;IIF)V}). Vanilla text,
 * labels and item stacks are painted later in {@code render} and also survive.
 *
 * <p>Style, knobs and springs mirror {@link HandledScreenGlassMixin}: grab the
 * backdrop the instant before the glass draw, drive an open fade, and register
 * the rect with {@link PanelGhost} so the panel fades out on close.
 * {@code compatibilityLevel JAVA_8}; {@code s1mp1e$} member prefix.
 */
@Mixin(CreativeInventoryScreen.class)
public abstract class CreativeGlassMixin {

    // Inherited from DrawableHelper (obf dkw.b:(Ldfm;IIIIII)V, yarn
    // method_25302, public). NOT @Shadow'd: @Shadow of an inherited method throws
    // "not located in target class" at apply time. The shader-unavailable fallback
    // instead calls it through the redirect's `self` param, whose static type
    // CreativeInventoryScreen publicly inherits drawTexture.

    // Per-screen-instance open fade (fresh with each opened creative screen).
    private Fade s1mp1e$openFade;
    private boolean s1mp1e$opened;

    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 0,
                     target = "Lnet/minecraft/client/gui/screen/ingame/CreativeInventoryScreen;"
                            + "drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$glassItemPanel(CreativeInventoryScreen self, MatrixStack matrices,
                                       int x, int y, int u, int v, int w, int h) {
        // Glass REPLACES the item-panel PNG (the 26.2 design): the redirect
        // swallows the blit and paints frosted glass over the same rect. Tabs,
        // search box and scrollbar are separate INVOKEs and draw as normal.
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            self.drawTexture(matrices, x, y, u, v, w, h);
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
