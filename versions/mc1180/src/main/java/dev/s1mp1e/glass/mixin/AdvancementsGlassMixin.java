package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.advancement.AdvancementsScreen;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feature A — the advancements window is framed in glass (the wooden frame gone).
 *
 * <p>Verified against yarn 1.18.2+build.4: {@code AdvancementsScreen.render} computes {@code i=(width-252)/2},
 * {@code j=(height-140)/2}, then {@code renderBackground} → {@code drawAdvancementTree(matrices,mx,my,i,j)} (the tree,
 * scissored to the window interior, with its own dark interior fill) → {@code drawWindow(matrices,i,j)} (the
 * {@code WINDOW_TEXTURE} wooden frame + the tabs) → {@code drawWidgetTooltip}.
 *
 * <p>This mixin injects one refracting glass plate over the whole {@code 252x140} window <b>before</b> the tree is
 * drawn, so the tree's own dark interior fill then paints over the plate (keeping icons and connector lines readable)
 * = tree framed in glass; and it {@link Redirect}s the {@code WINDOW_TEXTURE} blit inside {@code drawWindow} to drop the
 * wooden frame. Both gate on the glass pipeline, so when it is unavailable the frame is kept and no plate is drawn.
 * The tab sprites, tab icons and the title text (other INVOKEs in {@code drawWindow}) are left untouched.
 */
@Mixin(AdvancementsScreen.class)
public abstract class AdvancementsGlassMixin {

    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final
    private java.util.Map<net.minecraft.advancement.Advancement,
            net.minecraft.client.gui.screen.advancement.AdvancementTab> tabs;
    @org.spongepowered.asm.mixin.Shadow
    private net.minecraft.client.gui.screen.advancement.AdvancementTab selectedTab;

    /**
     * Switching advancement tab swaps the whole tree in one frame. Snapshot the outgoing frame and cross-dissolve it
     * over the new tree (the glass window frame and tab row overlap, so only the tree region visibly cross-fades). HEAD,
     * before {@code selectedTab} flips. Skips a no-op re-select of the current tab. (Tabs are keyed by
     * {@code Advancement} here, not 1.20.2+'s {@code AdvancementEntry}.)
     */
    @Inject(method = "selectTab", at = @At("HEAD"))
    private void s1mp1e$dissolveAdvTab(net.minecraft.advancement.Advancement advancement, CallbackInfo ci) {
        net.minecraft.client.gui.screen.advancement.AdvancementTab next =
                advancement == null ? null : this.tabs.get(advancement);
        if (next != null && this.selectedTab != null && next != this.selectedTab) {
            dev.s1mp1e.glass.render.ScreenDissolve.onTabSwitch();
        }
    }

    @Inject(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/advancement/AdvancementsScreen;"
                            + "drawAdvancementTree(Lnet/minecraft/client/util/math/MatrixStack;IIII)V"))
    private void s1mp1e$glassWindow(MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;
        // Screen width/height == the window's scaled size; read from the window to avoid an inherited-field @Shadow.
        Window win = MinecraftClient.getInstance().getWindow();
        int i = (win.getScaledWidth() - 252) / 2;
        int j = (win.getScaledHeight() - 140) / 2;
        // #23: creative-inventory style — the tab row is a band of the SAME glass sheet (no separate tab tiles, see
        // AdvancementSpritesGlassMixin). Extend the panel by one tab's depth (28 = tab 32 minus the 4 px overlap) on
        // each side that carries tabs; only when >1 tab (vanilla draws the tab row only then). Orientation from the
        // package-private AdvancementTabType ordinal (0 ABOVE / 1 BELOW / 2 LEFT / 3 RIGHT).
        int x0 = i, y0 = j, x1 = i + 252, y1 = j + 140;
        if (this.tabs != null && this.tabs.size() > 1) {
            for (net.minecraft.client.gui.screen.advancement.AdvancementTab t : this.tabs.values()) {
                switch (((Enum<?>) (Object) t.getType()).ordinal()) {
                    case 0: y0 = j - 28; break;         // ABOVE
                    case 1: y1 = j + 140 + 28; break;   // BELOW
                    case 2: x0 = i - 28; break;         // LEFT
                    case 3: x1 = i + 252 + 28; break;   // RIGHT
                    default: break;
                }
            }
        }
        // world + dim already in the framebuffer (renderBackground ran first). Panel refracts it; the tree's dark
        // interior then draws on top of the panel interior, so only the glass FRAME shows around the tree.
        SceneCapture.grabNow();
        GlassRenderer.panel(x0, y0, x1, y1, 1.0f);
    }

    /** Drop the wooden {@code WINDOW_TEXTURE} frame while the glass frame is up (else keep it). */
    @Redirect(method = "drawWindow",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/advancement/AdvancementsScreen;"
                            + "drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$dropFrame(AdvancementsScreen self, MatrixStack matrices,
                                  int x, int y, int u, int v, int w, int h) {
        if (GlassProgram.ensureReady() && GlassProgram.usable()) return;   // glass frame replaces it
        self.drawTexture(matrices, x, y, u, v, w, h);
    }
}
