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
 * System 2 (recipe book glass) — the 1.14.4 tier port of LiquidGlass26's
 * {@code RecipeBookGlassMixin} (via the 1.16.5 Fabric port). Reproduces the full
 * 26.2 look, which is TWO things, not just the pop-out background:
 *
 * <ol>
 *   <li><b>Book panel</b> — the background PNG becomes a frosted glass panel
 *       ({@link GlassRenderer#panel}).</li>
 *   <li><b>Category tabs → ONE vertical glass bar + a liquid-sliding pill.</b>
 *       26.2 merges the whole visible-tab column into a single frosted bar and
 *       slides a selected-pill along it on the hotbar-measured two-spring rig
 *       (lead {@link Spring#OMEGA_SNAP 55} / trail {@link Spring#OMEGA_MED 30},
 *       critically damped). Individual tab sprites are suppressed in
 *       {@link RecipeTabGlassMixin}; only the icons draw over the bar.</li>
 * </ol>
 *
 * <h3>Injection point (verified from 1.14.4 bytecode, merged jar)</h3>
 * {@code RecipeBookWidget} (obf {@code dey} / {@code class_507}) declares
 * {@code render} (yarn {@code render}, descriptor {@code (IIF)V} — NO MatrixStack
 * at this tier). Its first draw blits the book PNG via {@code blit(IIIIII)V} (the
 * 1.14.4 name of {@code DrawableHelper.drawTexture}); the INVOKE's OWNER is
 * {@code RecipeBookWidget} itself (javac emits the receiver's static type), so the
 * {@code @Redirect} owner is {@code RecipeBookWidget}. {@code blit} is a PUBLIC
 * method inherited from {@code DrawableHelper}; it is NOT {@code @Shadow}'d (that
 * throws "not located in target class" for inherited members) — the no-glass
 * fallback calls it through the redirect's {@code self} param instead.
 *
 * <h3>Tier deltas (1.14.4 vs 1.16.5)</h3>
 * <ul>
 *   <li><b>No MatrixStack (1.15+ has it, 1.14.4 does not).</b> {@code render(IIF)V}
 *       and {@code blit(IIIIII)V} both drop the MatrixStack arg.</li>
 *   <li><b>No {@code getHeight()}.</b> {@code AbstractButtonWidget} (class_339)
 *       exposes {@code getWidth()} only; {@code height} is {@code protected} and
 *       inaccessible from {@code RecipeBookWidget} (different package, not a
 *       subclass). The recipe tabs are built {@code 35x27}
 *       ({@code RecipeGroupButtonWidget.<init> -> ToggleButtonWidget(0,0,35,27,false)}),
 *       so the tab height is the constant 27.</li>
 *   <li><b>Public fields.</b> {@code x}, {@code y}, {@code visible} are {@code public}
 *       on {@code AbstractButtonWidget} (verified), so the bbox reads them directly.</li>
 * </ul>
 *
 * <p>The {@code tabButtons} {@code List} and the {@code currentTab} field are both
 * declared ON {@code RecipeBookWidget}, so {@code @Shadow} of them resolves cleanly.
 */
@Mixin(RecipeBookWidget.class)
public abstract class RecipeBookGlassMixin {

    /** The recipe tabs are constructed 35x27, and 1.14.4 has no getHeight(). */
    private static final int TAB_H = 27;

    /** Visible category tabs down the side of the open book (own field of
     *  RecipeBookWidget → @Shadow safe). */
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

        // Backdrop for the refraction. If none is available keep the PNG so the
        // book never vanishes.
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
        // bar 0x80FFFF00 -> frost 0.5, full-pill corner, no lift, opacity=fade.
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
