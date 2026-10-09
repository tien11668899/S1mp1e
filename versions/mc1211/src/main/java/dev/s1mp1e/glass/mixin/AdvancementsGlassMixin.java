package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.advancement.AdvancementsScreen;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feature A — the advancements window framed in glass (wooden frame gone). 1.21.1 (DrawContext) port of 26.2's
 * {@code AdvancementsGlassMixin}.
 *
 * <h3>Seam (verified from 1.21.1 bytecode, yarn 1.21.1+build.3)</h3>
 * {@code AdvancementsScreen.render} computes {@code x=(width-252)/2}, {@code y=(height-140)/2}, then calls
 * {@code drawAdvancementTree(ctx, mouseX, mouseY, x, y)} (scissored tree interior + widgets) and
 * {@code drawWindow(ctx, x, y)} (the wooden frame texture + tab title). We:
 * <ul>
 *   <li>{@code @Inject} {@code drawAdvancementTree} at HEAD (before its internal scissor) and lay ONE glass panel over
 *       the whole window rect {@code 252x140} at {@code (x,y)} — the container-panel material (its existing radius, not
 *       the hotbar corner, since this is a panel-family surface). The tree's own dark interior fill then paints over the
 *       centre, so the glass reads as the FRAME around the tree.</li>
 *   <li>{@code @Redirect} the {@code WINDOW_TEXTURE} {@code drawTexture} in {@code drawWindow} to drop the wooden
 *       frame (the tab title text draw is a separate INVOKE and survives).</li>
 * </ul>
 * Gates on the glass pipeline; a down pipeline draws neither the panel nor drops the frame (vanilla window intact).
 */
@Mixin(AdvancementsScreen.class)
public abstract class AdvancementsGlassMixin {

    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final
    private java.util.Map<net.minecraft.advancement.AdvancementEntry,
            net.minecraft.client.gui.screen.advancement.AdvancementTab> tabs;
    @org.spongepowered.asm.mixin.Shadow
    private net.minecraft.client.gui.screen.advancement.AdvancementTab selectedTab;

    /**
     * Switching advancement tab swaps the whole tree in one frame. Snapshot the outgoing frame and cross-dissolve it
     * over the new tree (the glass window frame and tab row overlap, so only the tree region visibly cross-fades). HEAD,
     * before {@code selectedTab} flips. Skips a no-op re-select of the current tab.
     */
    @Inject(method = "selectTab", at = @At("HEAD"))
    private void s1mp1e$dissolveAdvTab(net.minecraft.advancement.AdvancementEntry advancement, CallbackInfo ci) {
        net.minecraft.client.gui.screen.advancement.AdvancementTab next =
                advancement == null ? null : this.tabs.get(advancement);
        if (next != null && this.selectedTab != null && next != this.selectedTab) {
            dev.s1mp1e.glass.render.ScreenDissolve.onTabSwitch();
        }
    }

    @Inject(method = "drawAdvancementTree", at = @At("HEAD"))
    private void s1mp1e$glassPanel(DrawContext ctx, int mouseX, int mouseY, int x, int y, CallbackInfo ci) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;
        ctx.draw();               // flush the dim so the glass lands on top + the grab captures world+dim
        SceneCapture.grabNow();   // R4: fresh backdrop
        // fades in with the shared 150 ms screen-open ramp (synced with the glass buttons)
        float fade = dev.s1mp1e.client.gui.ScreenOpenFade.value(
                net.minecraft.client.MinecraftClient.getInstance().currentScreen);
        // Creative-inventory style: each tab row is a band of the SAME glass sheet (no separate tab tiles) — extend the
        // panel by one tab's depth (28) on each side that carries tabs. Vanilla draws the tab row only when >1 tab; match it.
        int x0 = x, y0 = y, x1 = x + 252, y1 = y + 140;
        if (this.tabs != null && this.tabs.size() > 1) {
            for (net.minecraft.client.gui.screen.advancement.AdvancementTab t : this.tabs.values()) {
                // AdvancementTabType is package-private (can't be named here), and .name() is unreliable under intermediary
                // runtime remapping — so switch on the declaration-order ordinal: 0 ABOVE, 1 BELOW, 2 LEFT, 3 RIGHT.
                switch (((Enum<?>) (Object) t.getType()).ordinal()) {
                    case 0 -> y0 = y - 28;
                    case 1 -> y1 = y + 140 + 28;
                    case 2 -> x0 = x - 28;
                    case 3 -> x1 = x + 252 + 28;
                    default -> { }
                }
            }
        }
        GlassRenderer.panel(x0, y0, x1, y1, fade);
    }

    @Redirect(method = "drawWindow",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/DrawContext;"
                            + "drawTexture(Lnet/minecraft/util/Identifier;IIIIII)V"))
    private void s1mp1e$dropFrame(DrawContext ctx, Identifier tex, int x, int y, int u, int v, int w, int h) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            ctx.drawTexture(tex, x, y, u, v, w, h);   // glass off -> keep the wooden frame
        }
        // glass on: drop the frame; the glass panel (drawAdvancementTree HEAD) is the frame now.
    }
}
