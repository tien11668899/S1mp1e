package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.gui.GlassGlideHost;
import dev.s1mp1e.client.gui.GlassScrollbar;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.ContainerGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.GlassTabs;
import dev.s1mp1e.glass.render.PanelGhost;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.container.Slot;
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
 * Creative inventory -> the FINAL fused liquid-glass design (26.2's fused-band tabs ported to 1.14.4, MatrixStack /
 * legacy GL), the 1.14.4 counterpart of the verified 1.17.1 sibling:
 * <ul>
 *   <li><b>Body + tabs = ONE glass sheet (B).</b> The item-panel body blit (ordinal-0 {@code drawTexture}) becomes a
 *       glass sheet extended {@link GlassTabs#BAND} px above and below, so both tab rows are the top/bottom band of the
 *       same surface (no seam); the body's ABSOLUTE corner radius is preserved (knob rescaled for the taller sheet, R2).
 *       Every tab's sprite+icon draw ({@code method_2468}) is intercepted and deferred; {@link GlassTabs} lays the
 *       equal touching cells, the sliding / cross-fading selection pill, the hover pill and the centred icons. Tab hit
 *       boxes ({@code isClickInTab}) and the tab tooltip box ({@code renderTabTooltipIfHovered}) move to the cells.</li>
 *   <li><b>Scrollbar (C).</b> The scrollbar knob blit (ordinal-1 {@code drawTexture}) becomes the shared vertical glass
 *       slider.</li>
 *   <li><b>Silky content (D).</b> The 45-slot grid GLIDES sub-pixel with the eased scrollbar value; vanilla keeps a
 *       row-aligned logical scroll (clicks / hit-test / tooltips correct) while the shared {@code HandledScreenGlassMixin}
 *       suppresses the vanilla grid slots + their hover test and this host draws the eased overlay translated by the
 *       fractional offset, scissored to the window with ONE extra row; a click mid-glide snaps to the target row.</li>
 * </ul>
 *
 * <h3>Seams (1.14.4 bytecode, yarn build.10)</h3>
 * {@code drawBackground(MatrixStack,FII)}: {@code method_2468} for every unselected group, then the body
 * {@code drawTexture(MatrixStack,IIIIII)} ordinal 0 at {@code (x,y,0,0,bgW,bgH)}, the search field, the scrollbar knob
 * {@code drawTexture} ordinal 1 at {@code (x+175, y+18+(int)(95*scrollPosition), ...)}, then {@code method_2468} for
 * the selected group (LAST). Scroll: {@code scrollPosition} 0..1, the logical top row is
 * {@code (int)(scrollPosition*rows+0.5)}, rows = ceil(size/9) - 5. The tab hit test {@code isClickInTab} gets coords
 * RELATIVE to {@code x/y}; the tooltip test {@code renderTabTooltipIfHovered} gets absolute coords.
 */
@Mixin(CreativeInventoryScreen.class)
public abstract class CreativeGlassMixin implements GlassGlideHost {

    @Unique private static final int LG_GRID_X = 9, LG_GRID_Y = 18, LG_COLS = 9, LG_VIS = 5, LG_PITCH = 18;
    /** Creative thumb 12x15 at x+175, track top y+18. Travel 97 = vanilla's DRAG divisor (mouseDragged: (y+130)-(y+18)-15),
     *  so a held glass thumb maps the pointer exactly like vanilla's scrollPosition (the sprite itself used 95). */
    @Unique private static final float LG_TRAVEL = 97f, LG_THUMB = 15f;

    @Shadow private static int selectedTab;

    /**
     * Switching the creative category swaps the whole item grid in one frame: snapshot the outgoing frame and
     * cross-dissolve it over the new category (the fused tab sheet overlaps, so only the grid visibly fades). HEAD,
     * before the static {@code selectedTab} flips, so the snapshot holds the old tab. Skips the re-select vanilla does
     * in {@code init}. (1.14.4: {@code selectedTab} is the group's index.)
     */
    @Inject(method = "setSelectedTab", at = @At("HEAD"))
    private void s1mp1e$dissolveTab(ItemGroup group, CallbackInfo ci) {
        if (group != null && selectedTab != group.getIndex()) dev.s1mp1e.glass.render.ScreenDissolve.onTabSwitch();
    }
    @Shadow private float scrollPosition;
    @Shadow private boolean field_2892;
    @Shadow private boolean hasScrollbar() { throw new AssertionError(); }
    @Shadow protected abstract void method_2468(ItemGroup group);

    @Unique private Fade s1mp1e$openFade;
    @Unique private boolean s1mp1e$opened;
    @Unique private final GlassScrollbar s1mp1e$scrollbar = new GlassScrollbar();
    /** Slot-separator lattice + drag + gliding hover pill on the fused sheet (26.2 draws it for creative too, S9P4). */
    @Unique private final ContainerGlass.State s1mp1e$slotLayer = new ContainerGlass.State();
    @Unique private int s1mp1e$mouseX, s1mp1e$mouseY;
    @Unique private int s1mp1e$barTab = Integer.MIN_VALUE;

    // Feature D — computed each frame in the scrollbar redirect, consumed by the shared glide mixin + the edges.
    @Unique private boolean s1mp1e$gliding, s1mp1e$wasGliding;
    @Unique private int s1mp1e$gridBaseRow;
    @Unique private float s1mp1e$gridFracPx;

    @Unique private HandledScreenAccessor s1mp1e$acc() { return (HandledScreenAccessor) (Object) this; }
    @Unique private static boolean s1mp1e$glass() { return GlassProgram.ensureReady() && GlassProgram.usable(); }
    @Unique private float s1mp1e$fade() { return s1mp1e$openFade == null ? 1f : s1mp1e$openFade.value(); }

    /** Frame start: capture the pointer (method_2468 / the scroller redirect don't receive it), forget last frame's
     *  deferred tabs, and reset the glide flag (the scroller redirect re-arms it; it only runs on field_2892 groups). */
    @Inject(method = "drawBackground", at = @At("HEAD"))
    private void s1mp1e$frameStart(float delta, int mouseX, int mouseY, CallbackInfo ci) {
        s1mp1e$mouseX = mouseX;
        s1mp1e$mouseY = mouseY;
        s1mp1e$wasGliding = s1mp1e$gliding;
        s1mp1e$gliding = false;
        GlassTabs.begin();
    }

    // ---- B: the body PNG -> the FUSED sheet -----------------------------------------------------

    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 0,
                     target = "Lnet/minecraft/client/gui/screen/ingame/CreativeInventoryScreen;blit(IIIIII)V"))
    private void s1mp1e$glassItemPanel(CreativeInventoryScreen self, int x, int y, int u, int v, int w, int h) {
        if (!s1mp1e$glass()) {
            self.blit(x, y, u, v, w, h);
            return;
        }
        if (s1mp1e$openFade == null) s1mp1e$openFade = new Fade(0f, PanelGhost.FADE_MS);
        // Frame-primary sheet: fresh backdrop the instant before the glass draw (grabNow, not the deduped grab) so the
        // sheet never samples a stale, wrong-stage snapshot at high fps (R4). Unselected-tab draws are deferred, so
        // nothing of ours is in the copy yet.
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
        // knob is 0.19 (radius = min(w,h)*0.25*0.19); rescale the knob for the extended height.
        int sheetTop = y - GlassTabs.BAND, sheetBot = y + h + GlassTabs.BAND;
        int sheetH = h + 2 * GlassTabs.BAND;
        float baseR = Math.min(w, h) * 0.25f * 0.19f;
        float sheetKnob = Math.min(1f, baseR / (Math.min(w, sheetH) * 0.25f));
        GlassRenderer.glass(x, sheetTop, x + w, sheetBot, GlassRenderer.PAD_PANEL, sheetKnob, 0f, fade,
                GlassRenderer.FROST_PANEL);

        // S9P4 — the container slot layer on the sheet (lattice + quick-craft drag + the gliding hover pill),
        // the SAME faint lattice + glass hover pill the survival inventory uses (ContainerGlass, 1.17.1 look).
        // No hover pill while the grid glides (vanilla nulls the hovered grid slot then).
        HandledScreenAccessor a = s1mp1e$acc();
        boolean glide = s1mp1e$wasGliding;
        ContainerGlass.drawSlotLayer(s1mp1e$slotLayer, x, y, a.s1mp1e$handler().slots, a.s1mp1e$cursorDragSlots(),
                a.s1mp1e$cursorDragging(), glide ? -10000 : s1mp1e$mouseX, glide ? -10000 : s1mp1e$mouseY, fade);
    }

    /** B — take over every tab sprite + icon: defer the (visible) tab for the fused layout and cancel vanilla's draw.
     *  The selected tab is issued LAST (after the body), so flushing on it paints the pills + icons on top. */
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/CreativeInventoryScreen;method_2468(Lnet/minecraft/item/ItemGroup;)V"))
    private void s1mp1e$tab(CreativeInventoryScreen self, ItemGroup group) {
        if (!s1mp1e$glass()) {
            this.method_2468(group);   // fallback: vanilla tabs
            return;
        }
        int col = group.getColumn();
        boolean top = group.isTopRow();
        boolean selected = group.getIndex() == selectedTab;
        HandledScreenAccessor a = s1mp1e$acc();
        boolean hovered = !selected && GlassTabs.hitRel(col, top, s1mp1e$mouseX - a.s1mp1e$x(),
                s1mp1e$mouseY - a.s1mp1e$y(), a.s1mp1e$backgroundWidth(), a.s1mp1e$backgroundHeight());
        GlassTabs.deferTile(col, top, selected, hovered);
        GlassTabs.deferIcon(group.getIcon(), col, top);
        if (selected) {
            MinecraftClient mc = MinecraftClient.getInstance();
            GlassTabs.flush(this, mc.getItemRenderer(), mc.textRenderer, a.s1mp1e$x(), a.s1mp1e$y(),
                    a.s1mp1e$backgroundWidth(), a.s1mp1e$backgroundHeight(),
                    Math.round(s1mp1e$fade() * 255f));
        }
    }

    /** B — the tab hit box follows the drawn fused cell. {@code isClickInTab} coords are RELATIVE to {@code x/y}. */
    @Inject(method = "isClickInTab", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$cellHit(ItemGroup group, double mouseX, double mouseY, CallbackInfoReturnable<Boolean> cir) {
        if (!s1mp1e$glass()) return;
        HandledScreenAccessor a = s1mp1e$acc();
        cir.setReturnValue(GlassTabs.hitRel(group.getColumn(), group.isTopRow(), mouseX, mouseY,
                a.s1mp1e$backgroundWidth(), a.s1mp1e$backgroundHeight()));
    }

    /** B — the tab name tooltip follows the fused cell too ({@code renderTabTooltipIfHovered} gets absolute coords). */
    @Inject(method = "method_2471", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$cellTooltip(ItemGroup group, int mouseX, int mouseY,
                                    CallbackInfoReturnable<Boolean> cir) {
        if (!s1mp1e$glass()) return;
        HandledScreenAccessor a = s1mp1e$acc();
        boolean hit = GlassTabs.hitRel(group.getColumn(), group.isTopRow(), mouseX - a.s1mp1e$x(),
                mouseY - a.s1mp1e$y(), a.s1mp1e$backgroundWidth(), a.s1mp1e$backgroundHeight());
        if (hit) {
            ((net.minecraft.client.gui.screen.Screen) (Object) this)
                    .renderTooltip(net.minecraft.client.resource.language.I18n.translate(group.getTranslationKey()), mouseX, mouseY);
        }
        cir.setReturnValue(hit);
    }

    // ---- C: the scrollbar knob -> the vertical glass slider; D: the per-frame glide state -------

    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 1,
                     target = "Lnet/minecraft/client/gui/screen/ingame/CreativeInventoryScreen;blit(IIIIII)V"))
    private void s1mp1e$glassScrollbar(CreativeInventoryScreen self, int tx, int ty, int u, int v, int w, int h) {
        if (!s1mp1e$glass()) {
            self.blit(tx, ty, u, v, w, h);
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
        GlassScrollbar.run(s1mp1e$scrollbar, cx, trackTop, LG_TRAVEL, LG_THUMB, ratio, active,
                field_2892 && active, s1mp1e$mouseY, s1mp1e$fade());
        if (selectedTab != s1mp1e$barTab) {        // a tab switch resets vanilla's scroll: no glide across tabs
            s1mp1e$barTab = selectedTab;
            s1mp1e$scrollbar.snapToTarget();
        }
        if (active && rc > 0) {
            float easedRows = s1mp1e$scrollbar.pos() * rc;
            if (Math.abs(easedRows - row) > 0.02f) {
                s1mp1e$gliding = true;
                s1mp1e$gridBaseRow = MathHelper.clamp((int) Math.floor(easedRows), 0, rc);
                s1mp1e$gridFracPx = (easedRows - s1mp1e$gridBaseRow) * LG_PITCH;
            }
        }
    }

    /** A click while the grid is mid-glide snaps the thumb to the logical row first (acts on the drawn item). */
    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void s1mp1e$snapGridOnClick(double mx, double my, int button, CallbackInfoReturnable<Boolean> cir) {
        if (s1mp1e$gliding) {
            s1mp1e$scrollbar.snapToTarget();
            s1mp1e$gliding = false;
        }
    }

    // ---- D: GlassGlideHost (HandledScreenGlassMixin's glide redirects call these) ---------------

    @Override
    public boolean s1mp1e$gliding() { return s1mp1e$gliding; }

    @Override
    public boolean s1mp1e$isGlideSlot(Slot slot) {
        // The 45 item-grid slots sit at relative (9+col*18, 18+row*18), col 0..8, row 0..4.
        int rx = slot.xPosition - LG_GRID_X, ry = slot.yPosition - LG_GRID_Y;
        return rx >= 0 && ry >= 0 && rx <= 8 * LG_PITCH && ry <= 4 * LG_PITCH && rx % LG_PITCH == 0 && ry % LG_PITCH == 0;
    }

    @Override
    public void s1mp1e$drawGlideOverlay() {
        List<ItemStack> items = s1mp1e$items();
        if (items == null) return;
        int size = items.size();
        HandledScreenAccessor a = s1mp1e$acc();
        int px = a.s1mp1e$x(), py = a.s1mp1e$y();
        MinecraftClient mc = MinecraftClient.getInstance();
        ItemRenderer ir = mc.getItemRenderer();

        // Runs inside HandledScreen.render's GlStateManager.translatef(x,y,0) legacy model-view translate. The scissor is
        // window-absolute (GlStateManager.enableScissor ignores the model-view) while the item draws stay slot-relative —
        // the MatrixStack-family mirror of 26.2's double-translate trap.
        GlassWidgets.beginScissor(px + LG_GRID_X, py + LG_GRID_Y, px + LG_GRID_X + LG_COLS * LG_PITCH,
                py + LG_GRID_Y + LG_VIS * LG_PITCH);
        GlStateManager.pushMatrix();
        GlStateManager.translatef(0f, -s1mp1e$gridFracPx, 0f);
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
                    ir.renderGuiItem(stack, rx, ry);
                    ir.renderGuiItemOverlay(mc.textRenderer, stack, rx, ry);
                }
            }
        } finally {
            ir.zOffset = savedZ;
            GlStateManager.popMatrix();
            GlassWidgets.endScissor();
        }

        // Config-menu scroll-edge whisper (26.2 CreativeGlassMixin): progressive blur on the window edge where content
        // runs off. Still inside render's translatef(x,y) model-view -> slot-relative coords (the edge shader samples by
        // gl_FragCoord, so the translate is harmless). Fresh composite grab first: it blurs the panel + items drawn so
        // far and never itself (R4). Depth off so the band is not rejected by the item depth just written.
        if (GlassProgram.edgeUsable()) {
            int rc = s1mp1e$rowCount();
            float topK = s1mp1e$gridBaseRow > 0 || s1mp1e$gridFracPx > 0.5f ? 1f : 0f;
            float botK = s1mp1e$gridBaseRow < rc ? 1f : 0f;
            boolean depth = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
            GlStateManager.disableDepthTest();
            SceneCapture.grabNow();
            GlassWidgets.scrollEdges(LG_GRID_X, LG_GRID_Y, LG_GRID_X + LG_COLS * LG_PITCH,
                    LG_GRID_Y + LG_VIS * LG_PITCH, 6f, topK, botK, s1mp1e$fade());
            if (depth) GlStateManager.enableDepthTest();
        }
    }

    @Unique
    private List<ItemStack> s1mp1e$items() {
        try {
            Object hnd = ((CreativeInventoryScreen) (Object) this).getContainer();
            if (hnd instanceof CreativeInventoryScreen.CreativeContainer) {
                return ((CreativeInventoryScreen.CreativeContainer) hnd).itemList;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Off-screen rows of the current tab's item list = ceil(size/9) - 5 (vanilla's own divisor). */
    @Unique
    private int s1mp1e$rowCount() {
        List<ItemStack> items = s1mp1e$items();
        return items == null ? 0 : Math.max(0, (items.size() + 8) / 9 - 5);
    }
}
