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
 * System 2 (container glass), creative-inventory variant — 1.13.2 (Legacy Fabric) port
 * of 26.2's / the 1.16.5 {@code CreativeGlassMixin}.
 *
 * <h3>Why a dedicated mixin</h3>
 * {@link CreativeInventoryScreen} OVERRIDES both {@code render} and {@code drawBackground}.
 * The base {@link HandledScreenGlassMixin} draws its container panel + lattice + hover from
 * inside {@code HandledScreen.render}, which the creative screen reaches via
 * {@code super.render}. Letting the base draw for the creative screen would put a slot
 * lattice over the creative item panel and double the glass — so the base mixin is guarded
 * to early-return for {@code CreativeInventoryScreen}, and this mixin does a SURGICAL
 * replacement instead: only the big item-area panel blit becomes glass; the category tab
 * row, the search box and the scrollbar (separate draws in {@code drawBackground}) run
 * untouched.
 *
 * <h3>The seam — verified from 1.13.2 bytecode (obf merged jar)</h3>
 * {@code CreativeInventoryScreen} obf {@code clh} / intermediary {@code class_415}; its
 * {@code drawBackground} (obf {@code a(FII)V}, yarn name {@code drawBackground} inherited
 * from {@code HandledScreen} obf {@code cky} / {@code class_409}) issues exactly TWO
 * {@code drawTexture(IIIIII)V} blits, both with INVOKE owner {@code CreativeInventoryScreen}
 * itself (javac emits the {@code this} receiver's static type; {@code drawTexture} =
 * {@code DrawableHelper.b(IIIIII)V} = yarn {@code drawTexture} / {@code method_992}).
 * <ul>
 *   <li>ordinal 0 (offset 142): {@code drawTexture(this.x, this.y, 0, 0, backgroundWidth,
 *       backgroundHeight)} — the big 195x136 item panel. THIS is redirected to glass.</li>
 *   <li>ordinal 1 (offset 252): the scrollbar knob blit ({@code u=232/244, w=12, h=15}) —
 *       left ALONE so the scrollbar keeps working.</li>
 * </ul>
 * Pre-MatrixStack: the blit descriptor is {@code (IIIIII)V} (no {@code MatrixStack} prefix)
 * and {@code drawBackground} is {@code (FII)V}.
 *
 * <p>Style/knobs mirror {@link HandledScreenGlassMixin}: grab the backdrop the instant
 * before the glass draw, drive an open fade, and register the rect with {@link PanelGhost}.
 */
@Mixin(CreativeInventoryScreen.class)
public abstract class CreativeGlassMixin {

    // drawTexture is inherited from DrawableHelper (PUBLIC) — NOT @Shadow'd (that throws
    // "not located in target class"). The shader-unavailable fallback calls it through the
    // redirect's `self` param, whose static type CreativeInventoryScreen publicly inherits
    // drawTexture.

    // Per-screen-instance open fade (fresh with each opened creative screen).
    private Fade s1mp1e$openFade;
    private boolean s1mp1e$opened;

    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 0,
                     target = "Lnet/minecraft/client/gui/screen/ingame/CreativeInventoryScreen;"
                            + "drawTexture(IIIIII)V"))
    private void s1mp1e$glassItemPanel(CreativeInventoryScreen self,
                                       int x, int y, int u, int v, int w, int h) {
        // Glass REPLACES the item-panel PNG (the 26.2 design): the redirect swallows the
        // blit and paints frosted glass over the same rect. Tabs, search box and scrollbar
        // are separate INVOKEs and draw as normal.
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            self.drawTexture(x, y, u, v, w, h);
            return;
        }

        if (s1mp1e$openFade == null) {
            s1mp1e$openFade = new Fade(0f, PanelGhost.FADE_MS);
        }

        // Backdrop = world + dim (+ any tab sprites already painted this frame), grabbed
        // the instant before the glass draw.
        SceneCapture.grab();

        if (!s1mp1e$opened) {
            // Fresh screen instance: snap the open fade and cancel any in-flight close ghost.
            s1mp1e$opened = true;
            s1mp1e$openFade.snap(0f);
            s1mp1e$openFade.to(1f);
            PanelGhost.cancel();
        }
        float fade = s1mp1e$openFade.value();

        // The item panel is the only glass rect this screen registers, so it owns
        // beginFrame(); the close ghost then fades exactly this rect.
        PanelGhost.beginFrame();
        PanelGhost.remember(x, y, w, h);
        GlassRenderer.panel(x, y, x + w, y + h, fade);
    }
}
