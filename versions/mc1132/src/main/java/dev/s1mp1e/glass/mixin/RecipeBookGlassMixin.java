package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.anim.Spring;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.PanelGhost;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.screen.RecipeBookScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * System 2 (recipe book glass) — the 1.13.2 (Legacy Fabric) port of LiquidGlass26's
 * {@code RecipeBookGlassMixin}. Reproduces the full 26.2 look, which is THREE things:
 *
 * <ol>
 *   <li><b>Book panel</b> — the 147x166 background PNG becomes a frosted glass panel
 *       ({@link GlassRenderer#panel}).</li>
 *   <li><b>Category tabs → ONE vertical glass bar + a liquid-sliding pill.</b> 26.2
 *       does NOT glass each tab individually; it merges the whole visible-tab column
 *       into a single frosted bar and slides a selected-pill along it on the
 *       hotbar-measured two-spring rig (lead {@link Spring#OMEGA_SNAP 55} / trail
 *       {@link Spring#OMEGA_MED 30}, critically damped). The individual tab sprites
 *       are suppressed in {@link RecipeTabGlassMixin}; only the tab icons draw over
 *       the bar.</li>
 * </ol>
 *
 * <h3>1.13.2 recon (obf merged jar + legacyfabric yarn build.604)</h3>
 * The recipe-book component is {@code RecipeBookScreen} (obf {@code cmg}, intermediary
 * {@code class_3282}) — NOT a widget embedded in the screen as in 1.16.5, but the same
 * role. It {@code extends DrawableHelper} (obf {@code cgm} / {@code class_372}) directly.
 * <ul>
 *   <li>render = obf {@code a(IIF)V} = intermediary {@code method_14575} (NO friendly
 *       yarn name in build.604 — targeted by intermediary). Pre-MatrixStack: it is
 *       {@code (IIF)V}, no {@code MatrixStack} param.</li>
 *   <li>the book background is the SOLE {@code drawTexture(IIIIII)V} INVOKE in render
 *       (offset 87): {@code this.drawTexture(x, y, 1, 1, 147, 166)}. The invoke OWNER is
 *       {@code RecipeBookScreen} itself (javac emits the {@code this} receiver's static
 *       type), so the {@code @Redirect} owner is {@code RecipeBookScreen}.
 *       {@code drawTexture} = {@code DrawableHelper.b(IIIIII)V} = yarn
 *       {@code drawTexture} ({@code method_992}), PUBLIC inherited — NOT {@code @Shadow}'d
 *       (that throws "not located in target class"); the no-glass fallback calls it
 *       through the redirect's {@code self} param.</li>
 *   <li>tab-button list = own field obf {@code p} = intermediary {@code field_16047}
 *       ({@code List}, element {@code class_3284}); selected tab = own field obf {@code q}
 *       = intermediary {@code field_16048} ({@code class_3284}). Both declared ON
 *       {@code RecipeBookScreen} → {@code @Shadow} resolves cleanly. Element/selected
 *       type {@code class_3284} is unmapped but IS-A {@code ButtonWidget} (obf {@code cgu}
 *       / {@code class_356}), whose {@code x} ({@code h}), {@code y} ({@code i}) and
 *       {@code visible} ({@code m}) are PUBLIC — read through {@code ButtonWidget}.</li>
 * </ul>
 * The tab button is fixed 35x27 ({@code chn.<init>(0,0,35,27,0,false)} in {@code class_3284}'s
 * ctor), so the tab height is the constant 27 (26.2's default; there is no public getter
 * for the protected {@code height} field).
 */
@Mixin(RecipeBookScreen.class)
public abstract class RecipeBookGlassMixin {

    private static final int TAB_H = 27;   // recipe tab is fixed 35x27

    /** Visible category tabs (own field of RecipeBookScreen → @Shadow safe). Actual
     *  element type is the unmapped {@code class_3284}; typed here as its public
     *  {@code ButtonWidget} supertype (x/y/visible are public). */
    @Shadow private List<ButtonWidget> field_16047;
    /** The currently-selected category tab (own field → @Shadow safe). */
    @Shadow private net.minecraft.class_3284 field_16048;

    /** Per-widget open ramp so the book pops in with the mod's 150 ms fade. */
    private Fade s1mp1e$openFade;

    // Vertical two-spring rig for the liquid-sliding selected-tab pill (26.2).
    private Spring s1mp1e$py1, s1mp1e$py2;
    private long   s1mp1e$lastNanos;
    private int    s1mp1e$lastTabY = Integer.MIN_VALUE;

    /**
     * Arm the inventory glide ({@link RecipeBookInvGlideMixin} / {@link dev.s1mp1e.glass.render.RecipeBookSlide}) the
     * instant the book toggles, so the cascade timing knows an opening is in progress BEFORE
     * {@code refreshResultButtons} schedules it, and re-trigger the book's own open fade so it fades in on every open
     * (not only the first). At HEAD {@code isOpen()} still reports the pre-toggle state, so
     * {@code opening = !isOpen()}.
     */
    @org.spongepowered.asm.mixin.injection.Inject(method = "method_14587", at = @At("HEAD"))
    private void s1mp1e$armSlide(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        boolean opening = !((RecipeBookScreen) (Object) this).method_14590();
        dev.s1mp1e.glass.render.RecipeBookSlide.arm(this, opening);
        if (opening && s1mp1e$openFade != null) {
            s1mp1e$openFade.snap(0f);
            s1mp1e$openFade.to(1f);
        }
    }

    // ---- slide out from behind the inventory (RecipeBookSlide) -----------------------------------------------------

    @Shadow private int field_16044;
    @Shadow private int field_16043;
    @org.spongepowered.asm.mixin.Unique private boolean s1mp1e$slideOpen;

    /** While sliding shut, keep drawing although vanilla already marked the book hidden. */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "method_14575",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/RecipeBookScreen;method_14590()Z"))
    private boolean s1mp1e$drawWhileClosing(RecipeBookScreen self,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<Boolean> op) {
        return op.call(self) || dev.s1mp1e.glass.render.RecipeBookSlide.closing(this);
    }

    /**
     * Bracket the whole book render: shift it by {@code RecipeBookSlide.bookShift} through the GlStateManager model-view
     * (every draw of the book honours it — immediate glass, textures, text, item models) and clip it at the
     * inventory's animated left edge, so it reads as sliding out from underneath. 1.13.2 GUI draws are immediate, so
     * nothing queued before leaks into the clip and nothing of the book is drawn after it is lifted.
     */
    @org.spongepowered.asm.mixin.injection.Inject(method = "method_14575", at = @At("HEAD"))
    private void s1mp1e$slideBegin(int mouseX, int mouseY, float delta,
                                   org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        s1mp1e$slideOpen = false;
        // 1.13.2 draws the book BEFORE the inventory, so the toggle has to be noticed here already (see observe()).
        net.minecraft.client.gui.screen.Screen s1mp1e$screen = net.minecraft.client.MinecraftClient.getInstance().currentScreen;
        if (s1mp1e$screen instanceof net.minecraft.class_3288
                && s1mp1e$screen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen) {
            dev.s1mp1e.glass.render.RecipeBookSlide.observe(s1mp1e$screen, ((HandledScreenAccessor) s1mp1e$screen).s1mp1e$x());
        }
        if (!dev.s1mp1e.glass.render.RecipeBookSlide.slides(this)) return;
        int bookOpenX = (this.field_16044 - 147) / 2 - this.field_16043;
        float shift = dev.s1mp1e.glass.render.RecipeBookSlide.bookShift(bookOpenX);
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        dev.s1mp1e.glass.render.GuiFlush.flush();
        dev.s1mp1e.client.gui.GuiScissor.enable(0, 0,
                Math.max(0, dev.s1mp1e.glass.render.RecipeBookSlide.animatedX() + 1), mc.field_19944.method_18322());
        com.mojang.blaze3d.platform.GlStateManager.pushMatrix();          // 1.13.2: the fixed-function model-view
        com.mojang.blaze3d.platform.GlStateManager.translate(shift, 0f, 0f);
        dev.s1mp1e.client.gui.GuiAlpha.push(dev.s1mp1e.glass.render.RecipeBookSlide.bookFade());
        s1mp1e$slideOpen = true;
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "method_14575", at = @At("RETURN"))
    private void s1mp1e$slideEnd(int mouseX, int mouseY, float delta,
                                 org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (!s1mp1e$slideOpen) return;
        s1mp1e$slideOpen = false;
        dev.s1mp1e.client.gui.GuiAlpha.pop();
        com.mojang.blaze3d.platform.GlStateManager.popMatrix();
        dev.s1mp1e.client.gui.GuiScissor.disable();
    }

    /** The search field: its opaque black vanilla frame becomes a frosted glass scrim (see EditBoxTypingMixin, part 5). */
    @Redirect(method = "method_14575",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/widget/TextFieldWidget;method_18385(IIF)V"))
    private void s1mp1e$glassSearch(net.minecraft.client.gui.widget.TextFieldWidget box, int mouseX, int mouseY, float delta) {
        dev.s1mp1e.glass.render.EditBoxGlass.frame = true;
        try {
            box.method_18385(mouseX, mouseY, delta);
        } finally {
            dev.s1mp1e.glass.render.EditBoxGlass.frame = false;
        }
    }

    @Redirect(method = "method_14575",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/RecipeBookScreen;drawTexture(IIIIII)V"))
    private void s1mp1e$glassBook(RecipeBookScreen self, int x, int y, int u, int v,
                                  int width, int height) {
        // No glass path on this GPU -> draw the vanilla book PNG unchanged.
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            self.drawTexture(x, y, u, v, width, height);
            return;
        }

        // Backdrop for the refraction. If none is available keep the PNG so the
        // book never vanishes.
        // 2026-10-04：沿用 HUD 這一幀在 InGameHud.render 開頭拍的「只有世界」背景（26.2 的做法）；在暗色漸層之後重拍會讓整個背包發暗
        if (!SceneCapture.hasBackdrop()) SceneCapture.grabNow();
        if (!SceneCapture.hasBackdrop()) {
            self.drawTexture(x, y, u, v, width, height);
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
     * 26.2 tab-bar: bbox of VISIBLE tabs → one frosted vertical bar with a two-spring
     * liquid-sliding selected pill. Mirrors LiquidGlass26 {@code RecipeBookGlassMixin}.
     */
    private void s1mp1e$drawTabBar(float fade) {
        List<ButtonWidget> tabs = field_16047;
        if (tabs == null || tabs.isEmpty()) return;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int visible = 0;
        for (ButtonWidget t : tabs) {
            if (!t.visible) continue;         // hidden tabs park at (0,0)
            visible++;
            minX = Math.min(minX, t.x);
            minY = Math.min(minY, t.y);
            maxY = Math.max(maxY, t.y + TAB_H);
        }
        if (visible == 0) return;

        // Bar width 27 ([minX+1, minX+28]); length = first tab top .. last tab bottom,
        // so the end pills also clear by 2px.
        int barX0 = minX + 1, barX1 = minX + 28;
        int barY0 = minY,     barY1 = maxY;
        PanelGhost.remember(barX0, barY0, barX1 - barX0, barY1 - barY0);
        // bar 0x80FFFF00 -> frost 0.5, full-pill corner (R=FF), no lift (G=FF),
        // opacity follows the open fade.
        GlassRenderer.glass(barX0, barY0, barX1, barY1,
                            GlassRenderer.PAD_PILL, 1.0f, 0f, fade, GlassRenderer.FROST_PANEL);

        // Liquid-sliding selected pill (vertical two-spring, hotbar-measured).
        if (field_16048 != null) {
            ButtonWidget sel = field_16048;   // class_3284 IS-A ButtonWidget (public y)
            float ty = sel.y;
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
