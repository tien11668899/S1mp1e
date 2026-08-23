package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.anim.Spring;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.PanelGhost;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.screen.recipebook.RecipeBookWidget;
import net.minecraft.client.gui.screen.recipebook.RecipeGroupButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * System 2 (recipe book glass) — the 1.15.2 Fabric port of LiquidGlass26's
 * {@code RecipeBookGlassMixin} (ported from the verified 1.16.5 line). Reproduces
 * the full 26.2 look, which is THREE things, not just the pop-out background:
 *
 * <ol>
 *   <li><b>Book panel</b> — the 147x166 background PNG becomes a frosted glass
 *       panel ({@link GlassRenderer#panel}).</li>
 *   <li><b>Category tabs → ONE vertical glass bar + a liquid-sliding pill.</b>
 *       26.2 does NOT glass each tab individually; it merges the whole visible-tab
 *       column into a single frosted bar and slides a selected-pill along it on the
 *       hotbar-measured two-spring rig (lead {@link Spring#OMEGA_SNAP 55} / trail
 *       {@link Spring#OMEGA_MED 30}, critically damped). The individual tab sprites
 *       are suppressed in {@link RecipeTabGlassMixin}; only the tab icons draw over
 *       the bar.</li>
 * </ol>
 *
 * <h3>1.15.2 tier deltas vs the 1.16.5 source (verified from bytecode)</h3>
 * This render tier has NO {@code MatrixStack}: {@code RecipeBookWidget.render} is
 * {@code (IIF)V} and the book-background blit is the yarn method {@code blit}
 * (not {@code drawTexture}), descriptor {@code (IIIIII)V}. The invoke's OWNER is
 * {@code RecipeBookWidget} itself (constant pool {@code #540 =
 * RecipeBookWidget.blit:(IIIIII)V}; javac emits the receiver's static type), so the
 * {@code @Redirect} owner is {@code RecipeBookWidget}. {@code blit} is a PUBLIC
 * method inherited from {@code DrawableHelper}; it is NOT {@code @Shadow}'d (that
 * throws "not located in target class" for inherited members) — the no-glass
 * fallback calls it through the redirect's {@code self} param instead. The selected
 * tab field is {@code currentTab} (own field of {@code RecipeBookWidget}). The tab
 * widget has no {@code getHeight()} accessor on this version's
 * {@code AbstractButtonWidget}, so the fixed 27px tab height (from the
 * {@code RecipeGroupButtonWidget} ctor {@code super(0,0,35,27,false)}) is used.
 *
 * <p>The {@code tabButtons} {@code List} and the {@code currentTab} field are both
 * declared ON {@code RecipeBookWidget}, so {@code @Shadow} of them resolves cleanly
 * (unlike the inherited {@code blit}).
 */
@Mixin(RecipeBookWidget.class)
public abstract class RecipeBookGlassMixin {

    /** Fixed tab height (RecipeGroupButtonWidget is always 35x27; no getHeight() here). */
    private static final int TAB_H = 27;

    /** Visible category tabs down the side of the open book (own field of
     *  RecipeBookWidget → @Shadow safe). Element type {@link RecipeGroupButtonWidget}. */
    @Shadow private List<RecipeGroupButtonWidget> tabButtons;
    /** The currently-selected category tab (own field → @Shadow safe). */
    @Shadow private RecipeGroupButtonWidget currentTab;

    /** Per-widget open ramp so the book pops in with the mod's 150 ms fade. */
    private Fade s1mp1e$openFade;

    // Vertical two-spring rig for the liquid-sliding selected-tab pill (26.2).
    private Spring s1mp1e$py1, s1mp1e$py2;
    private long   s1mp1e$lastNanos;
    private int    s1mp1e$lastTabY = Integer.MIN_VALUE;

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/recipebook/RecipeBookWidget;"
                            + "blit(IIIIII)V"))
    private void s1mp1e$glassBook(RecipeBookWidget self,
                                  int x, int y, int u, int v, int width, int height) {
        // No glass path on this GPU -> draw the vanilla book PNG unchanged.
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            self.blit(x, y, u, v, width, height);
            return;
        }

        // Backdrop for the refraction. grab() folds duplicates within 3 ms, so if
        // the container panel already grabbed this frame this is a no-op and reuses
        // that texture. If no backdrop is available (0-size window etc.) the glass
        // would draw nothing and we'd have swallowed the vanilla blit -> keep the
        // PNG in that case so the book never vanishes.
        SceneCapture.grab();
        if (!SceneCapture.hasBackdrop()) {
            self.blit(x, y, u, v, width, height);
            return;
        }

        if (s1mp1e$openFade == null) {
            s1mp1e$openFade = new Fade(0f, PanelGhost.FADE_MS);
            s1mp1e$openFade.to(1f);
        }
        float fade = s1mp1e$openFade.value();

        // (1) book background panel — 26.2 knobs: frost 0.5, corner ~0.19, no lift.
        PanelGhost.remember(x, y, width, height);
        GlassRenderer.panel(x, y, x + width, y + height, fade);

        // (2) category tabs merged into ONE vertical glass bar + sliding pill.
        s1mp1e$drawTabBar(fade);
    }

    /**
     * 26.2 tab-bar: bbox of VISIBLE tabs → one frosted vertical bar with a
     * two-spring liquid-sliding selected pill. Mirrors LiquidGlass26
     * {@code RecipeBookGlassMixin#lg$glassBook}.
     */
    private void s1mp1e$drawTabBar(float fade) {
        List<RecipeGroupButtonWidget> tabs = tabButtons;
        if (tabs == null || tabs.isEmpty()) return;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int visible = 0;
        for (RecipeGroupButtonWidget t : tabs) {
            if (!t.visible) continue;         // hidden tabs park at (0,0)
            visible++;
            minX = Math.min(minX, t.x);
            minY = Math.min(minY, t.y);
            maxY = Math.max(maxY, t.y + TAB_H);
        }
        if (visible == 0) return;

        // Bar width 27 ([minX+1, minX+28]); length = first tab top .. last tab
        // bottom, so the end pills also clear by 2px.
        int barX0 = minX + 1, barX1 = minX + 28;
        int barY0 = minY,     barY1 = maxY;
        PanelGhost.remember(barX0, barY0, barX1 - barX0, barY1 - barY0);
        // bar 0x80FFFF00 -> frost 0.5, full-pill corner (R=FF), no lift (G=FF),
        // opacity follows the open fade.
        GlassRenderer.glass(barX0, barY0, barX1, barY1,
                            GlassRenderer.PAD_PILL, 1.0f, 0f, fade, GlassRenderer.FROST_PANEL);

        // Liquid-sliding selected pill (vertical two-spring, hotbar-measured).
        if (currentTab != null) {
            float ty = currentTab.y;
            long now = System.nanoTime();
            float dt = (s1mp1e$lastNanos == 0L) ? (1f / 60f)
                       : Math.min(0.1f, (now - s1mp1e$lastNanos) * 1e-9f);
            s1mp1e$lastNanos = now;
            if (s1mp1e$py1 == null) {
                s1mp1e$py1 = new Spring(ty, Spring.OMEGA_SNAP, Spring.DAMPING);
                s1mp1e$py2 = new Spring(ty, Spring.OMEGA_MED,  Spring.DAMPING);
                s1mp1e$lastTabY = (int) ty;
            } else if ((int) ty != s1mp1e$lastTabY) {
                s1mp1e$py1.setTarget(ty);
                s1mp1e$py2.setTarget(ty);
                s1mp1e$lastTabY = (int) ty;
            }
            s1mp1e$py1.advance(dt);
            s1mp1e$py2.advance(dt);
            if (!Float.isFinite(s1mp1e$py1.value()) || !Float.isFinite(s1mp1e$py2.value())) {
                s1mp1e$py1.snap(ty); s1mp1e$py2.snap(ty);
            }
            int py0  = Math.round(Math.min(s1mp1e$py1.value(), s1mp1e$py2.value())) + 2;
            int py1v = Math.round(Math.max(s1mp1e$py1.value(), s1mp1e$py2.value())) + TAB_H - 2;
            // pill 0xFFFFD800 -> no frost, full corner, lift 0.153, opacity=fade.
            GlassRenderer.glass(barX0 + 2, py0, barX1 - 2, py1v,
                                6f, 1.0f, 0.153f, fade, GlassRenderer.FROST_NONE);
        }
    }
}
