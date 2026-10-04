package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.GlassScrollbar;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.anim.Spring;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.GlassTabs;
import dev.s1mp1e.glass.render.PanelGhost;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemGroup;
import net.minecraft.screen.slot.Slot;

import java.util.HashSet;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * System 2 (container glass), creative-inventory variant. 1.19.2 Fabric port of
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
public abstract class CreativeGlassMixin implements dev.s1mp1e.client.gui.GlassGlideHost, dev.s1mp1e.client.gui.GlideProbe {

    // drawTexture is inherited from DrawableHelper (public); NOT @Shadow'd — @Shadow
    // of an inherited method throws "not located in target class" at apply time. The
    // shader-unavailable fallback instead calls it through the redirect's `self`
    // param, whose static type CreativeInventoryScreen publicly inherits drawTexture.

    // Per-screen-instance open fade (fresh with each opened creative screen).
    private Fade s1mp1e$openFade;
    private boolean s1mp1e$opened;

    // Feature C — vertical glass scrollbar for the creative item grid. Shadows of fields/method declared on
    // CreativeInventoryScreen itself (no inherited-field @Shadow warning).
    @Shadow private float scrollPosition;
    @Shadow private boolean scrolling;
    @Shadow private boolean hasScrollbar() { throw new AssertionError(); }
    private GlassScrollbar s1mp1e$scrollbar;

    // Feature B — fused tab band. Static field (the vanilla selected-tab index) + a per-screen tab animator.
    @Shadow private static int selectedTab;

    /**
     * Switching the creative category swaps the whole item grid in one frame: snapshot the outgoing frame and
     * cross-dissolve it over the new category (the fused tab sheet overlaps, so only the grid visibly fades). HEAD,
     * before the static {@code selectedTab} flips, so the snapshot holds the old tab. Skips the re-select vanilla does
     * in {@code init}. (1.19.2: {@code selectedTab} is the group's index.)
     */
    @org.spongepowered.asm.mixin.injection.Inject(method = "setSelectedTab", at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
    private void s1mp1e$dissolveTab(net.minecraft.item.ItemGroup group, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (group != null && selectedTab != group.getIndex()) dev.s1mp1e.glass.render.ScreenDissolve.onTabSwitch();
    }
    @Unique private GlassTabs s1mp1e$tabs;

    // Feature D — sub-pixel item-grid glide. Computed each frame in the scrollbar redirect, consumed by the shared
    // ContainerGlideGlassMixin (suppress vanilla grid slots + draw the eased overlay).
    @Unique private boolean s1mp1e$gridGliding;
    @Unique private int s1mp1e$gridBaseRow;
    @Unique private float s1mp1e$gridFracPx;

    // #4 — creative slot lattice + gliding hover pill (survival has these via HandledScreenGlassMixin, which returns
    // early for creative). Same look/rig as that mixin: static neighbour-mask lattice + a two-axis hover spring.
    @Unique private Spring s1mp1e$hx1, s1mp1e$hx2, s1mp1e$hy1, s1mp1e$hy2;
    @Unique private final Fade s1mp1e$hoverFade = new Fade(0f, 100f);
    @Unique private boolean s1mp1e$hoverActive;
    @Unique private long s1mp1e$hoverNanos;

    // ---- GlideProbe (dev capture harness only) ----
    @Override
    public boolean s1mp1e$probeGliding() { return s1mp1e$gridGliding; }

    @Override
    public float s1mp1e$probeOffsetPx() {
        return s1mp1e$scrollbar == null ? 0f : s1mp1e$scrollbar.pos() * Math.max(0, s1mp1e$offRows()) * 18f;
    }

    @Unique private int s1mp1e$px() { return ((HandledScreenAccessor) (Object) this).s1mp1e$x(); }
    @Unique private int s1mp1e$py() { return ((HandledScreenAccessor) (Object) this).s1mp1e$y(); }
    @Unique private int s1mp1e$bgH() { return ((HandledScreenAccessor) (Object) this).s1mp1e$backgroundHeight(); }
    @Unique private int s1mp1e$bgW() { return ((HandledScreenAccessor) (Object) this).s1mp1e$backgroundWidth(); }

    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 0,
                     target = "Lnet/minecraft/client/gui/screen/ingame/CreativeInventoryScreen;"
                            + "drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$glassItemPanel(CreativeInventoryScreen self, MatrixStack matrices,
                                       int x, int y, int u, int v, int w, int h,
                                       MatrixStack encMatrices, float delta, int mouseX, int mouseY) {
        // Feature B — the body PNG becomes the FUSED tab sheet: the body glass extended by GlassTabs.BAND above and
        // below into ONE glass rect that both tab rows sit inside (no seam), with the sliding/cross-fading selection
        // pill, the hover pill, and every tab icon centred in its cell. The vanilla tab sprites are suppressed
        // (s1mp1e$suppressTabIcon); the search box and scrollbar are separate INVOKEs and draw as normal.
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            self.drawTexture(matrices, x, y, u, v, w, h);
            return;
        }

        s1mp1e$gridGliding = false;   // reset the glide each frame; the scrollbar redirect re-arms it if scrolling
        if (s1mp1e$openFade == null) {
            s1mp1e$openFade = new Fade(0f, PanelGhost.FADE_MS);
        }
        if (s1mp1e$tabs == null) s1mp1e$tabs = new GlassTabs();

        // Backdrop = world + dim, grabbed the instant before the glass draw. grabNow (not the deduped grab) so a
        // high-frame-rate paused screen can't skip it and leave the sheet sampling a stale backdrop (R4).
        // 2026-10-04：沿用 HUD 這一幀在 InGameHud.render 開頭拍的「只有世界」背景（26.2 的做法）；在暗色漸層之後重拍會讓整個背包發暗
        if (!SceneCapture.hasBackdrop()) SceneCapture.grabNow();

        if (!s1mp1e$opened) {
            s1mp1e$opened = true;
            s1mp1e$openFade.snap(0f);
            s1mp1e$openFade.to(1f);
            PanelGhost.cancel();
        }
        float fade = s1mp1e$openFade.value();

        // The fused sheet is the only glass rect this screen registers, so it owns beginFrame(); the close ghost
        // fades the whole extended rect.
        PanelGhost.beginFrame();
        PanelGhost.remember(x, y - GlassTabs.BAND, w, h + 2 * GlassTabs.BAND);

        // Keep the body's ABSOLUTE corner radius on the taller sheet (R2 leaves existing surfaces unchanged): the
        // panel corner knob is 0.19, so radius = min(w,h)*0.25*0.19; rescale the knob for the extended height.
        float baseR = Math.min(w, h) * 0.25f * 0.19f;
        int sheetH = h + 2 * GlassTabs.BAND;
        float sheetKnob = Math.min(1f, baseR / (Math.min(w, sheetH) * 0.25f));

        MinecraftClient mc = MinecraftClient.getInstance();
        s1mp1e$tabs.render(matrices, x, y, w, h, fade, sheetKnob, selectedTab, mouseX, mouseY,
                mc.getItemRenderer(), mc.textRenderer);

        // #4 — the creative slot lattice + gliding hover pill (survival gets these from HandledScreenGlassMixin,
        // which returns early for creative). Drawn on the fused sheet, over the current tab's slots. No hover pill
        // while the item grid glides (vanilla nulls the hovered grid slot then), matching the survival look.
        List<Slot> slots = ((HandledScreenAccessor) (Object) this).s1mp1e$handler().slots;
        s1mp1e$drawLattice(slots, x, y, fade);
        boolean glide = s1mp1e$gridGliding;
        s1mp1e$drawHover(slots, x, y, glide ? -10000 : mouseX, glide ? -10000 : mouseY, System.nanoTime());
    }

    /** Slot-separator lattice: one glass cell per slot with a 4-bit neighbour mask (E/W/S/N), like the survival grid. */
    @Unique
    private static void s1mp1e$drawLattice(List<Slot> slots, int gl, int gt, float fade) {
        HashSet<Long> pos = new HashSet<Long>();
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            pos.add((((long) s.x) << 32) | (s.y & 0xffffffffL));
        }
        if (!GlassRenderer.beginBatch(GlassProgram.LINE)) return;
        try {
            for (int i = 0; i < slots.size(); i++) {
                Slot s = slots.get(i);
                int sx = s.x, sy = s.y, mask = 0;
                if (pos.contains((((long) (sx + 18)) << 32) | (sy & 0xffffffffL))) mask |= 1; // E
                if (pos.contains((((long) (sx - 18)) << 32) | (sy & 0xffffffffL))) mask |= 2; // W
                if (pos.contains((((long) sx) << 32) | ((sy + 18) & 0xffffffffL)))  mask |= 4; // S
                if (pos.contains((((long) sx) << 32) | ((sy - 18) & 0xffffffffL)))  mask |= 8; // N
                GlassRenderer.batchQuad(gl + sx - 1, gt + sy - 1, gl + sx + 17, gt + sy + 17,
                                        0f, 1f, 1f, fade, (mask * 17) / 255f);
            }
        } finally {
            GlassRenderer.endBatch();
        }
    }

    /** Gliding hover pill on the two-axis spring rig (lead 55 / trail 30, critically damped), the survival look. */
    @Unique
    private void s1mp1e$drawHover(List<Slot> slots, int gl, int gt, int mouseX, int mouseY, long now) {
        Slot hov = null;
        int px = mouseX - gl, py = mouseY - gt;
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            if (px >= s.x - 1 && px < s.x + 17 && py >= s.y - 1 && py < s.y + 17 && s.isEnabled()) hov = s;
        }
        boolean hovering = hov != null;
        float dt = (s1mp1e$hoverNanos == 0L) ? (1f / 60f) : Math.min(0.1f, (now - s1mp1e$hoverNanos) * 1.0e-9f);
        s1mp1e$hoverNanos = now;
        if (hovering) {
            float cx = gl + hov.x + 8f, cy = gt + hov.y + 8f;
            if (s1mp1e$hx1 == null || (!s1mp1e$hoverActive && s1mp1e$hoverFade.value() <= 0.05f)) {
                s1mp1e$hx1 = new Spring(cx, Spring.OMEGA_SNAP, Spring.DAMPING);
                s1mp1e$hx2 = new Spring(cx, Spring.OMEGA_MED,  Spring.DAMPING);
                s1mp1e$hy1 = new Spring(cy, Spring.OMEGA_SNAP, Spring.DAMPING);
                s1mp1e$hy2 = new Spring(cy, Spring.OMEGA_MED,  Spring.DAMPING);
            } else {
                s1mp1e$hx1.setTarget(cx); s1mp1e$hx2.setTarget(cx);
                s1mp1e$hy1.setTarget(cy); s1mp1e$hy2.setTarget(cy);
            }
            s1mp1e$hoverActive = true;
            s1mp1e$hoverFade.to(1f);
        } else {
            s1mp1e$hoverActive = false;
            s1mp1e$hoverFade.to(0f);
            if (s1mp1e$hoverFade.value() <= 0.004f || s1mp1e$hx1 == null) return;
        }
        s1mp1e$hx1.advance(dt); s1mp1e$hx2.advance(dt);
        s1mp1e$hy1.advance(dt); s1mp1e$hy2.advance(dt);
        float lox = Math.min(s1mp1e$hx1.value(), s1mp1e$hx2.value()), hix = Math.max(s1mp1e$hx1.value(), s1mp1e$hx2.value());
        float loy = Math.min(s1mp1e$hy1.value(), s1mp1e$hy2.value()), hiy = Math.max(s1mp1e$hy1.value(), s1mp1e$hy2.value());
        GlassRenderer.glass(lox - 10f, loy - 10f, hix + 10f, hiy + 10f,
                            6f, 1.0f, 0.12f, s1mp1e$hoverFade.value(), GlassRenderer.FROST_NONE);
    }

    /**
     * Feature B — suppress vanilla's tab sprite + icon draw (both the unselected-tab loop and the selected-tab draw in
     * {@code drawBackground}); {@link GlassTabs} draws the fused pills + centred icons instead. Cancelled only when the
     * glass pipeline is usable, so a shader-unavailable fallback keeps the vanilla tabs.
     */
    @Inject(method = "renderTabIcon", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$suppressTabIcon(MatrixStack matrices, ItemGroup group, CallbackInfo ci) {
        if (GlassProgram.ensureReady() && GlassProgram.usable()) ci.cancel();
    }

    /**
     * Feature B — the tab hit box follows the drawn fused cell, so what is clicked is what is drawn. Cancelled with the
     * cell test only when glass is usable (else vanilla geometry stands).
     */
    @Inject(method = "isClickInTab", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$cellHit(ItemGroup group, double mouseX, double mouseY, CallbackInfoReturnable<Boolean> cir) {
        if (s1mp1e$tabs != null && GlassProgram.ensureReady() && GlassProgram.usable()) {
            cir.setReturnValue(s1mp1e$tabs.hit(group, mouseX, mouseY, s1mp1e$px(), s1mp1e$py(), s1mp1e$bgW(), s1mp1e$bgH()));
        }
    }

    /**
     * Feature C — replace the vanilla creative scrollbar thumb (ordinal-1 {@code drawTexture} in {@code drawBackground},
     * gated by {@code selectedTab.hasScrollbar()}) with the shared vertical glass slider. Verified against yarn
     * 1.19.2+build.28: the thumb is blitted at {@code (x+175, (y+18)+(int)(95*scrollPosition), 232+..., 0, 12, 15)}, so
     * from the passed thumb-x / thumb-y the track centre is {@code x+181}, the track top {@code y+18}, thumb-top travel
     * 95, thumb length 15. {@code scrollPosition} is the logical ratio; {@code scrolling} is vanilla's drag flag;
     * {@code hasScrollbar()} distinguishes an enabled from a greyed thumb. Falls back to the vanilla thumb when the
     * glass pipeline is unavailable.
     */
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 1,
                     target = "Lnet/minecraft/client/gui/screen/ingame/CreativeInventoryScreen;"
                            + "drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$glassScrollbar(CreativeInventoryScreen self, MatrixStack matrices,
                                       int tx, int ty, int u, int v, int w, int h) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            self.drawTexture(matrices, tx, ty, u, v, w, h);
            return;
        }
        if (s1mp1e$scrollbar == null) s1mp1e$scrollbar = new GlassScrollbar();
        final float travel = 95f, thumbLen = 15f;
        float cx = tx + w / 2f;
        float trackTop = ty - (int) (travel * scrollPosition);   // thumb-y minus (int)(travel*ratio) = track top
        MinecraftClient mc = MinecraftClient.getInstance();
        double my = mc.mouse.getY() * (double) mc.getWindow().getScaledHeight() / (double) mc.getWindow().getHeight();
        GlassScrollbar.run(s1mp1e$scrollbar, matrices, cx, trackTop, travel, thumbLen,
                scrollPosition, hasScrollbar(), scrolling, my, 1.0f);

        // Feature D — arm the sub-pixel grid glide from the eased thumb position. offRows = ceil(size/9) - 5; the
        // eased top row is scrollbar.pos()*offRows, the logical (row-snapped) top is round(scrollPosition*offRows).
        // While they differ the shared ContainerGlideGlassMixin suppresses vanilla's grid slots and draws the eased
        // overlay (s1mp1e$drawGlideOverlay); at rest vanilla draws normally.
        int offRows = s1mp1e$offRows();
        if (hasScrollbar() && offRows > 0) {
            float easedTop = s1mp1e$scrollbar.pos() * offRows;
            int logicalTop = Math.round(scrollPosition * offRows);
            if (Math.abs(easedTop - logicalTop) > 0.02f) {
                s1mp1e$gridGliding = true;
                s1mp1e$gridBaseRow = net.minecraft.util.math.MathHelper.clamp((int) Math.floor(easedTop), 0, offRows);
                s1mp1e$gridFracPx = (easedTop - s1mp1e$gridBaseRow) * 18f;
            }
        }
    }

    // ---- Feature D: GlassGlideHost (the shared ContainerGlideGlassMixin calls these) -----------------------------

    @Unique
    private net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen.CreativeScreenHandler s1mp1e$creativeHandler() {
        return (net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen.CreativeScreenHandler)
                ((net.minecraft.client.gui.screen.ingame.HandledScreen<?>) (Object) this).getScreenHandler();
    }

    /** Off-screen rows in the current tab's item list = ceil(size/9) − 5 (the 5 visible rows). */
    @Unique
    private int s1mp1e$offRows() {
        try {
            int size = s1mp1e$creativeHandler().itemList.size();
            return Math.max(0, (size + 8) / 9 - 5);
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public boolean s1mp1e$gliding() { return s1mp1e$gridGliding; }

    @Override
    public boolean s1mp1e$isGlideSlot(net.minecraft.screen.slot.Slot slot) {
        // The 45 item-grid slots sit at relative (9+col*18, 18+row*18), col 0..8, row 0..4.
        int rx = slot.x - 9, ry = slot.y - 18;
        return rx >= 0 && ry >= 0 && rx <= 8 * 18 && ry <= 4 * 18 && rx % 18 == 0 && ry % 18 == 0;
    }

    @Override
    public void s1mp1e$drawGlideOverlay(MatrixStack matrices) {
        net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen.CreativeScreenHandler h;
        try { h = s1mp1e$creativeHandler(); } catch (Throwable t) { return; }
        java.util.List<net.minecraft.item.ItemStack> items = h.itemList;
        int size = items.size();
        int px = s1mp1e$px(), py = s1mp1e$py();
        MinecraftClient mc = MinecraftClient.getInstance();
        net.minecraft.client.render.item.ItemRenderer ir = mc.getItemRenderer();

        // Scissor the 5-row window in ABSOLUTE window coords (glScissor ignores the model-view translate). The items
        // are drawn at RELATIVE (9+col*18, 18+row*18) UNDER the (px,py) translate, offset up by the fractional row.
        net.minecraft.client.gui.DrawableHelper.enableScissor(px + 9, py + 18, px + 9 + 9 * 18, py + 18 + 5 * 18);
        MatrixStack mv = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
        mv.push();
        mv.translate(0f, -s1mp1e$gridFracPx, 0f);
        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
        for (int vr = 0; vr <= 5; vr++) {          // 5 visible + 1 extra row so no edge gap shows
            int row = s1mp1e$gridBaseRow + vr;
            for (int col = 0; col < 9; col++) {
                int idx = col + row * 9;
                if (idx < 0 || idx >= size) continue;
                net.minecraft.item.ItemStack stack = items.get(idx);
                if (stack.isEmpty()) continue;
                int rx = 9 + col * 18, ry = 18 + vr * 18;
                ir.renderInGuiWithOverrides(stack, rx, ry);
                ir.renderGuiItemOverlay(mc.textRenderer, stack, rx, ry);
            }
        }
        mv.pop();
        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
        net.minecraft.client.gui.DrawableHelper.disableScissor();
    }

    /** A click while the grid is mid-glide snaps the thumb to the logical row first (acts on the drawn item). */
    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void s1mp1e$snapGridOnClick(double mx, double my, int button, CallbackInfoReturnable<Boolean> cir) {
        if (s1mp1e$gridGliding && s1mp1e$scrollbar != null) {
            s1mp1e$scrollbar.snapToTarget();
            s1mp1e$gridGliding = false;
        }
    }
}
