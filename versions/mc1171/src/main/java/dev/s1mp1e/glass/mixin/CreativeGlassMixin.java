package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.gui.GlassGlideHost;
import dev.s1mp1e.client.gui.GlassScrollbar;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.client.gui.GlideProbe;
import dev.s1mp1e.client.gui.GuiScissor;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.ContainerGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassTabs;
import dev.s1mp1e.glass.render.PanelGhost;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * System 2 (container glass), creative-inventory variant — the 1.17.1 port of 26.2's fused-band creative design.
 *
 * <ul>
 *   <li><b>Body + tabs = ONE glass sheet (B).</b> The item-panel body blit (ordinal-0 {@code drawTexture} in
 *       {@code drawBackground}) becomes a glass sheet extended {@link GlassTabs#BAND} px above and below, so both tab
 *       rows are the top/bottom band of the same surface (no seam); the body's ABSOLUTE corner radius is preserved (knob
 *       rescaled for the taller sheet, R2). Vanilla's tab sprite+icon draws ({@code renderTabIcon}) are intercepted —
 *       recorded as visible and cancelled — and {@link GlassTabs} lays the equal touching cells, the sliding /
 *       cross-fading selection pill, the hover pill and the centred icons. Tab hit boxes ({@code isClickInTab}, the
 *       relative-coordinate test {@code mouseClicked}/{@code mouseReleased} use) and the tab tooltip hit box
 *       ({@code renderTabTooltipIfHovered}) move to the cells. The container slot layer (lattice + drag + the gliding
 *       hover pill — vanilla's white slot highlight is suppressed for every container) is drawn on the sheet too, as
 *       26.2 does.</li>
 *   <li><b>Scrollbar (C).</b> The scrollbar knob blit (ordinal-1 {@code drawTexture}, gated by the group's
 *       {@code hasScrollbar}) becomes the shared vertical glass slider.</li>
 *   <li><b>Silky content (D).</b> The 45-slot grid GLIDES sub-pixel with the eased scrollbar value; vanilla keeps a
 *       row-aligned logical scroll (clicks / hit-test / tooltips correct) while the shared
 *       {@link ContainerGlideGlassMixin} suppresses the vanilla grid slots + their hover test and this host draws the
 *       eased overlay translated by the fractional offset, scissored to the window with ONE extra row; the scroll-edge
 *       whisper is drawn after the effect strip (so the strip still samples the clean panel backdrop, R4); a click
 *       mid-glide snaps to the target row first.</li>
 * </ul>
 *
 * <h3>Seams — verified from the 1.17.1 bytecode (yarn 1.17.1+build.65)</h3>
 * {@code drawBackground(MatrixStack,FII)}: {@code renderTabIcon} for every unselected group (offset 73), the body
 * {@code drawTexture(MatrixStack,IIIIII)} ordinal 0 (offset 131, owner {@code CreativeInventoryScreen}), the search
 * field, the scrollbar knob {@code drawTexture} ordinal 1 (offset 246) at {@code (x+175, y+18+(int)(95*scrollPosition),
 * 232|244, 0, 12, 15)}, then {@code renderTabIcon} for the selected group (offset 253). {@code render} calls
 * {@code AbstractInventoryScreen.render} (offset 11: panel, slots, foreground, then the effect strip) and then
 * {@code renderTabTooltipIfHovered} per group. Scroll: {@code scrollPosition} 0..1, the logical top row is
 * {@code (int)(scrollPosition*rows+0.5)} ({@code CreativeScreenHandler.scrollItems}), rows = ceil(size/9) - 5.
 *
 * <p>Priority 1100: applied after Fabric API's item-group paging mixin (default 1000), so at the shared HEADs of
 * {@code renderTabIcon}/{@code isClickInTab}/{@code renderTabTooltipIfHovered} Fabric's "not on this page" cancel runs
 * first and hidden groups never reach the fused layout.
 */
@Mixin(value = CreativeInventoryScreen.class, priority = 1100)
public abstract class CreativeGlassMixin implements GlassGlideHost, GlideProbe, dev.s1mp1e.client.gui.TabsProbe {

    @Unique private static final int LG_GRID_X = 9, LG_GRID_Y = 18, LG_COLS = 9, LG_VIS = 5, LG_PITCH = 18;
    /** Creative thumb 12x15 at x+175, track top y+18. Travel 97 = vanilla's DRAG divisor ((y+130)-(y+18)-15, in
     *  mouseDragged), so a held glass thumb maps the pointer exactly like vanilla's scrollPosition (1:1, no correction
     *  glide on release); vanilla's own sprite used 95 (2 px short of the 112 px track end). */
    @Unique private static final float LG_TRAVEL = 97f, LG_THUMB = 15f;

    // drawTexture is inherited from DrawableHelper (public); NOT @Shadow'd — @Shadow of an inherited method throws
    // "not located in target class" at apply time. The fallbacks call it through the redirect's `self` param.

    @Shadow private static int selectedTab;

    /**
     * Switching the creative category swaps the whole item grid in one frame: snapshot the outgoing frame and
     * cross-dissolve it over the new category (the fused tab sheet overlaps, so only the grid visibly fades). HEAD,
     * before the static {@code selectedTab} flips, so the snapshot holds the old tab. Skips the re-select vanilla does
     * in {@code init}. (1.17.1: {@code selectedTab} is the group's index.)
     */
    @Inject(method = "setSelectedTab", at = @At("HEAD"))
    private void s1mp1e$dissolveTab(ItemGroup group, CallbackInfo ci) {
        if (group != null && selectedTab != group.getIndex()) dev.s1mp1e.glass.render.ScreenDissolve.onTabSwitch();
    }
    @Shadow private float scrollPosition;
    @Shadow private boolean scrolling;
    @Shadow private boolean hasScrollbar() { throw new AssertionError(); }

    @Unique private Fade s1mp1e$openFade;
    @Unique private boolean s1mp1e$opened;
    @Unique private final GlassTabs s1mp1e$tabs = new GlassTabs();
    @Unique private final ContainerGlass.State s1mp1e$slotLayer = new ContainerGlass.State();
    @Unique private final GlassScrollbar s1mp1e$scrollbar = new GlassScrollbar();
    @Unique private int s1mp1e$mouseX, s1mp1e$mouseY;
    @Unique private int s1mp1e$barTab = -1;

    // Feature D — computed each frame in the scrollbar redirect, consumed by ContainerGlideGlassMixin + the edges.
    @Unique private boolean s1mp1e$gridGliding, s1mp1e$wasGliding;
    @Unique private int s1mp1e$gridBaseRow;
    @Unique private float s1mp1e$gridFracPx;

    @Unique private HandledScreenAccessor s1mp1e$acc() { return (HandledScreenAccessor) (Object) this; }
    @Unique private static boolean s1mp1e$glass() { return GlassProgram.ensureReady() && GlassProgram.usable(); }
    @Unique private float s1mp1e$fade() { return s1mp1e$openFade == null ? 1f : s1mp1e$openFade.value(); }
    @Unique private static ItemGroup s1mp1e$group(int index) {
        return index >= 0 && index < ItemGroup.GROUPS.length ? ItemGroup.GROUPS[index] : null;
    }

    /** Frame start: capture the pointer (renderTabIcon / the scroller redirect don't receive it), forget last frame's
     *  visible tabs, and reset the glide flag (the scroller redirect re-arms it; it only runs on scrolling tabs). */
    @Inject(method = "drawBackground", at = @At("HEAD"))
    private void s1mp1e$frameStart(MatrixStack matrices, float delta, int mouseX, int mouseY, CallbackInfo ci) {
        s1mp1e$mouseX = mouseX;
        s1mp1e$mouseY = mouseY;
        s1mp1e$wasGliding = s1mp1e$gridGliding;
        s1mp1e$gridGliding = false;
        s1mp1e$tabs.begin();
    }

    // ---- B: the body PNG -> the FUSED sheet (+ pills + centred icons + the slot layer) --------------------------

    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 0,
                     target = "Lnet/minecraft/client/gui/screen/ingame/CreativeInventoryScreen;"
                            + "drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$glassItemPanel(CreativeInventoryScreen self, MatrixStack matrices,
                                       int x, int y, int u, int v, int w, int h) {
        if (!s1mp1e$glass()) {
            self.drawTexture(matrices, x, y, u, v, w, h);
            return;
        }
        if (s1mp1e$openFade == null) s1mp1e$openFade = new Fade(0f, PanelGhost.FADE_MS);

        // Backdrop = world + dim, grabbed the instant before the glass draw. grabNow (not the deduped grab) so a
        // high-frame-rate paused screen can't skip it and leave the sheet sampling a stale backdrop (R4). The vanilla
        // unselected-tab draws before this point are cancelled, so nothing of ours is in the copy.
        SceneCapture.grabNow();

        if (!s1mp1e$opened) {
            s1mp1e$opened = true;
            s1mp1e$openFade.snap(0f);
            s1mp1e$openFade.to(1f);
            PanelGhost.cancel();
        }
        float fade = s1mp1e$openFade.value();

        // The fused sheet is the only glass rect this screen registers; the close ghost fades the whole extended rect.
        PanelGhost.beginFrame();
        PanelGhost.remember(x, y - GlassTabs.BAND, w, h + 2 * GlassTabs.BAND);

        // Keep the body's ABSOLUTE corner radius on the taller sheet (R2 — existing body radius unchanged): the panel
        // knob is 0.19, radius = min(w,h)*0.25*0.19; rescale the knob for the extended height.
        float baseR = Math.min(w, h) * 0.25f * 0.19f;
        int sheetH = h + 2 * GlassTabs.BAND;
        float sheetKnob = Math.min(1f, baseR / (Math.min(w, sheetH) * 0.25f));

        MinecraftClient mc = MinecraftClient.getInstance();
        s1mp1e$tabs.render(x, y, w, h, fade, sheetKnob, s1mp1e$group(selectedTab), s1mp1e$mouseX, s1mp1e$mouseY,
                mc.getItemRenderer(), mc.textRenderer);

        // The container slot layer on the sheet (26.2 draws it for creative too): lattice + quick-craft drag + the
        // gliding hover pill. No hover pill while the grid glides (vanilla nulls the hovered grid slot then).
        HandledScreenAccessor a = s1mp1e$acc();
        boolean glide = s1mp1e$wasGliding;
        ContainerGlass.drawSlotLayer(s1mp1e$slotLayer, x, y, a.s1mp1e$handler().slots, a.s1mp1e$cursorDragSlots(),
                a.s1mp1e$cursorDragging(), glide ? -10000 : s1mp1e$mouseX, glide ? -10000 : s1mp1e$mouseY, fade);
    }

    /** B — take over every tab sprite + icon: record the (visible) group for the fused layout and cancel vanilla's draw.
     *  Only while glass is usable, so a shader-unavailable fallback keeps the vanilla tabs. */
    @Inject(method = "renderTabIcon", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$tabIcon(MatrixStack matrices, ItemGroup group, CallbackInfo ci) {
        if (!s1mp1e$glass()) return;
        s1mp1e$tabs.record(group);
        ci.cancel();
    }

    /** B — the tab hit box follows the drawn fused cell. {@code mouseClicked}/{@code mouseReleased} pass coordinates
     *  RELATIVE to {@code x/y} (javap-verified), so the cell test is done in panel-relative space. */
    @Inject(method = "isClickInTab", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$cellHit(ItemGroup group, double mouseX, double mouseY, CallbackInfoReturnable<Boolean> cir) {
        if (!s1mp1e$glass()) return;
        HandledScreenAccessor a = s1mp1e$acc();
        cir.setReturnValue(s1mp1e$tabs.isVisible(group)
                && GlassTabs.hitRel(group, mouseX, mouseY, a.s1mp1e$backgroundWidth(), a.s1mp1e$backgroundHeight()));
    }

    /** B — the tab name tooltip follows the fused cell too (vanilla tests its own sprite box; absolute coords here). */
    @Inject(method = "renderTabTooltipIfHovered", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$cellTooltip(MatrixStack matrices, ItemGroup group, int mouseX, int mouseY,
                                    CallbackInfoReturnable<Boolean> cir) {
        if (!s1mp1e$glass()) return;
        HandledScreenAccessor a = s1mp1e$acc();
        boolean hit = s1mp1e$tabs.isVisible(group) && GlassTabs.hitRel(group, mouseX - a.s1mp1e$x(),
                mouseY - a.s1mp1e$y(), a.s1mp1e$backgroundWidth(), a.s1mp1e$backgroundHeight());
        if (hit) ((Screen) (Object) this).renderTooltip(matrices, group.getTranslationKey(), mouseX, mouseY);
        cir.setReturnValue(hit);
    }

    // ---- C: the scrollbar knob -> the vertical glass slider; D: the per-frame glide state ---------------------------

    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 1,
                     target = "Lnet/minecraft/client/gui/screen/ingame/CreativeInventoryScreen;"
                            + "drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$glassScrollbar(CreativeInventoryScreen self, MatrixStack matrices,
                                       int tx, int ty, int u, int v, int w, int h) {
        if (!s1mp1e$glass()) {
            self.drawTexture(matrices, tx, ty, u, v, w, h);
            return;
        }
        HandledScreenAccessor a = s1mp1e$acc();
        boolean active = hasScrollbar();
        int rc = s1mp1e$rowCount();
        // Point the thumb at the ROW-ALIGNED logical ratio (vanilla's own scrollItems row), not the continuous
        // scrollPosition: after a drag scrollPosition rests between rows while the grid shows the rounded row.
        int row = rc <= 0 ? 0 : MathHelper.clamp((int) (scrollPosition * rc + 0.5f), 0, rc);
        float ratio = rc <= 0 ? 0f : (float) row / rc;
        float cx = a.s1mp1e$x() + 175 + 6f;       // 12-wide knob at x+175 -> centre +6
        float trackTop = a.s1mp1e$y() + 18f;
        GlassScrollbar.step(s1mp1e$scrollbar, cx, trackTop, LG_TRAVEL, LG_THUMB, ratio, scrolling && active,
                s1mp1e$mouseY);
        if (selectedTab != s1mp1e$barTab) {        // a tab switch resets vanilla's scroll: no glide across tabs
            s1mp1e$barTab = selectedTab;
            s1mp1e$scrollbar.snapToTarget();
        }
        s1mp1e$scrollbar.paint(matrices, active, s1mp1e$fade());

        if (active && rc > 0) {
            float easedRows = s1mp1e$scrollbar.pos() * rc;
            if (Math.abs(easedRows - row) > 0.02f) {
                s1mp1e$gridGliding = true;
                s1mp1e$gridBaseRow = MathHelper.clamp((int) Math.floor(easedRows), 0, rc);
                s1mp1e$gridFracPx = (easedRows - s1mp1e$gridBaseRow) * LG_PITCH;
            }
        }
    }

    // ---- D: GlassGlideHost (ContainerGlideGlassMixin calls these) ------------------------------------------------

    @Override
    public boolean s1mp1e$gliding() { return s1mp1e$gridGliding; }

    @Override
    public boolean s1mp1e$isGlideSlot(Slot slot) {
        // The 45 item-grid slots sit at relative (9+col*18, 18+row*18), col 0..8, row 0..4.
        int rx = slot.x - LG_GRID_X, ry = slot.y - LG_GRID_Y;
        return rx >= 0 && ry >= 0 && rx <= 8 * LG_PITCH && ry <= 4 * LG_PITCH && rx % LG_PITCH == 0 && ry % LG_PITCH == 0;
    }

    @Override
    public void s1mp1e$drawGlideOverlay(MatrixStack matrices) {
        List<ItemStack> items = s1mp1e$items();
        if (items == null) return;
        int size = items.size();
        HandledScreenAccessor a = s1mp1e$acc();
        int px = a.s1mp1e$x(), py = a.s1mp1e$y();
        MinecraftClient mc = MinecraftClient.getInstance();
        ItemRenderer ir = mc.getItemRenderer();

        // This runs inside HandledScreen.render's (x,y) RenderSystem model-view translate. RenderSystem.enableScissor
        // IGNORES the model-view, so the scissor is ABSOLUTE while the item draws stay slot-relative — the MatrixStack-
        // family mirror of 26.2's double-translate trap (an absolute scissor there clipped the grid EMPTY).
        GuiScissor.enable(px + LG_GRID_X, py + LG_GRID_Y, px + LG_GRID_X + LG_COLS * LG_PITCH,
                py + LG_GRID_Y + LG_VIS * LG_PITCH);
        MatrixStack mv = RenderSystem.getModelViewStack();
        mv.push();
        mv.translate(0.0, -s1mp1e$gridFracPx, 0.0);
        RenderSystem.applyModelViewMatrix();
        float savedZ = ir.zOffset;
        ir.zOffset = 100f;                            // vanilla drawSlot item depth
        try {
            for (int vr = 0; vr <= LG_VIS; vr++) {     // 5 visible + ONE extra row so no edge gap shows
                int row = s1mp1e$gridBaseRow + vr;
                for (int col = 0; col < LG_COLS; col++) {
                    int idx = col + row * LG_COLS;
                    if (idx < 0 || idx >= size) continue;
                    ItemStack stack = items.get(idx);
                    if (stack.isEmpty()) continue;
                    int rx = LG_GRID_X + col * LG_PITCH, ry = LG_GRID_Y + vr * LG_PITCH;
                    ir.renderInGuiWithOverrides(stack, rx, ry);
                    ir.renderGuiItemOverlay(mc.textRenderer, stack, rx, ry);
                }
            }
        } finally {
            ir.zOffset = savedZ;
            mv.pop();
            RenderSystem.applyModelViewMatrix();
            GuiScissor.disable();
        }
    }

    /** D — the iOS-26 scroll-edge whisper over the gliding window, AFTER AbstractInventoryScreen.render (so after the
     *  effect strip, which must keep sampling the clean pre-GUI panel backdrop) and before every tooltip. It takes a
     *  fresh composite grab first so the edge band blurs the items it sits on — never itself, never a stale frame. */
    @Inject(method = "render",
            at = @At(value = "INVOKE", shift = At.Shift.AFTER,
                     target = "Lnet/minecraft/client/gui/screen/ingame/AbstractInventoryScreen;"
                            + "render(Lnet/minecraft/client/util/math/MatrixStack;IIF)V"))
    private void s1mp1e$glideEdges(MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!s1mp1e$gridGliding || !GlassProgram.edgeUsable()) return;
        HandledScreenAccessor a = s1mp1e$acc();
        int x0 = a.s1mp1e$x() + LG_GRID_X, y0 = a.s1mp1e$y() + LG_GRID_Y;
        int x1 = x0 + LG_COLS * LG_PITCH, y1 = y0 + LG_VIS * LG_PITCH;
        SceneCapture.grabNow();
        int rc = s1mp1e$rowCount();
        float eased = s1mp1e$gridBaseRow + s1mp1e$gridFracPx / LG_PITCH;
        float topK = eased > 0.03f ? 1f : 0f;
        float botK = eased < rc - 0.03f ? 1f : 0f;
        GlassWidgets.scrollEdges(x0, y0, x1, y1, 6f, topK, botK, s1mp1e$fade());
    }

    /** A click while the grid is mid-glide snaps the thumb to the logical row first (acts on the drawn item). */
    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void s1mp1e$snapGridOnClick(double mx, double my, int button, CallbackInfoReturnable<Boolean> cir) {
        if (s1mp1e$gridGliding) {
            s1mp1e$scrollbar.snapToTarget();
            s1mp1e$gridGliding = false;
        }
    }

    @Unique
    private List<ItemStack> s1mp1e$items() {
        try {
            Object h = ((CreativeInventoryScreen) (Object) this).getScreenHandler();
            if (h instanceof CreativeInventoryScreen.CreativeScreenHandler) {
                return ((CreativeInventoryScreen.CreativeScreenHandler) h).itemList;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Off-screen rows of the current tab's item list = ceil(size/9) − 5 (vanilla's own divisor). */
    @Unique
    private int s1mp1e$rowCount() {
        List<ItemStack> items = s1mp1e$items();
        return items == null ? 0 : Math.max(0, (items.size() + 8) / 9 - 5);
    }

    // ---- dev probes (DevShotVerify only) -------------------------------------------------------------------------

    @Override
    public boolean s1mp1e$probeGliding() { return s1mp1e$gridGliding; }

    @Override
    public float s1mp1e$probeOffsetPx() { return s1mp1e$scrollbar.pos() * s1mp1e$rowCount() * LG_PITCH; }

    @Override
    public float s1mp1e$probeLift() { return s1mp1e$scrollbar.liftValue(); }

    @Override
    public GlassTabs s1mp1e$probeTabs() { return s1mp1e$tabs; }
}
