package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassSurface;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.advancement.AdvancementsScreen;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Advancements window → liquid glass: the wooden {@code WINDOW_TEXTURE} frame is dropped and the whole window is framed in
 * one refracting glass plate, with the tree's own dark interior fill drawn on top so icons/lines stay readable — 26.2's
 * {@code AdvancementsGlassMixin} ported to 1.20.1 (DrawContext, immediate mode).
 *
 * <p>1.20.1 order: {@code render} = {@code renderBackground} (screen dim) → {@code drawAdvancementTree} (interior fill +
 * icons, scissored to the window) → {@code drawWindow} (frame + title) → tooltips. We draw the glass plate at the
 * {@code drawAdvancementTree} INVOKE (BEFORE) — after the dim, before the tree, and OUTSIDE the tree's interior scissor so
 * it is not clipped — then redirect the {@code WINDOW_TEXTURE} blit in {@code drawWindow} to nothing. The tree's dark
 * interior then paints over the plate; only the frame/border/title band reads as glass.
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
     * before {@code selectedTab} flips. Skips a no-op re-select of the current tab. (1.20.1: tabs are keyed by
     * {@code Advancement}, not 1.20.2+'s {@code AdvancementEntry}.)
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
                            + "drawAdvancementTree(Lnet/minecraft/client/gui/DrawContext;IIII)V"))
    private void s1mp1e$glassWindow(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;
        Screen self = (Screen) (Object) this;
        int x = (self.width - 252) / 2;
        int y = (self.height - 140) / 2;
        // Creative-inventory style: each tab row is a band of the SAME glass sheet (no separate tab tiles) — extend the
        // panel by one tab's depth (28) on each side that carries tabs. Vanilla draws the tab row only when >1 tab; match
        // it. AdvancementTabType is package-private and .name() is unreliable under intermediary runtime remapping — so
        // switch on the declaration-order ordinal: 0 ABOVE, 1 BELOW, 2 LEFT, 3 RIGHT.
        int x0 = x, y0 = y, x1 = x + 252, y1 = y + 140;
        if (this.tabs != null && this.tabs.size() > 1) {
            for (net.minecraft.client.gui.screen.advancement.AdvancementTab t : this.tabs.values()) {
                switch (((Enum<?>) (Object) t.getType()).ordinal()) {
                    case 0: y0 = y - 28; break;
                    case 1: y1 = y + 140 + 28; break;
                    case 2: x0 = x - 28; break;
                    case 3: x1 = x + 252 + 28; break;
                    default: break;
                }
            }
        }
        // Frame-primary surface: fresh backdrop this frame (the dim was drawn by renderBackground just above). R4.
        SceneCapture.grabNow();
        GlassSurface.plateOrPaint(context, x0, y0, x1, y1, 1.0f, 0x99101014);
    }

    /** Drop the wooden window frame — the glass plate replaces it. Tabs (a different method) stay vanilla. */
    @Redirect(method = "drawWindow",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/DrawContext;"
                            + "drawTexture(Lnet/minecraft/util/Identifier;IIIIII)V"))
    private void s1mp1e$dropFrame(DrawContext context, Identifier texture, int x, int y, int u, int v, int w, int h) {
        if (GlassProgram.ensureReady() && GlassProgram.usable()) return;   // glass replaced it
        context.drawTexture(texture, x, y, u, v, w, h);
    }
}
