package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassScreenPanels;
import net.minecraft.client.gui.screen.advancement.AdvancementsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PORT_SPEC feature (A) — the advancements window is framed in glass and the wooden frame is gone.
 * 1.14.4 counterpart of 26.2's {@code AdvancementsGlassMixin}.
 *
 * <p>1.14.4 (javap) {@code AdvancementsScreen.render} order: {@code renderBackground()} (dim) ->
 * {@code drawAdvancementTree(mouseX, mouseY, originX, originY)} (the tree, scissored to the window
 * interior, with its own dark interior fill) -> {@code drawWidgets(originX, originY)} (binds
 * {@code WINDOW_TEXTURE} and {@code blit(originX, originY, 0, 0, 252, 140)} = the wooden frame,
 * then the tabs) -> {@code drawWidgetTooltip}.
 *
 * <ul>
 *   <li><b>@Inject drawAdvancementTree HEAD</b> lays one refracting glass panel over the whole
 *       {@code 252x140} window at {@code (originX, originY)} BEFORE the tree draws (params 3/4 are
 *       the window origin). The tree's dark interior fill then paints over the panel centre (keeps
 *       icons / lines readable); the panel shows through the 9/18-px border = "tree framed in
 *       glass". It is drawn outside the tree's scissor (before {@code pushMatrix}/{@code scissor})
 *       so it is not clipped.</li>
 *   <li><b>@Redirect the WINDOW_TEXTURE blit in drawWidgets</b> to a no-op so the wooden frame is
 *       dropped; when glass is unusable it draws the vanilla frame so nothing is lost.</li>
 * </ul>
 *
 * <p>Tabs ({@code AdvancementTab.drawBackground}), the title icon and the tree contents are left
 * vanilla. (1.8.9 has {@code GuiAchievements} instead and is handled separately; 1.14.4 has the
 * real advancements screen.)
 */
@Mixin(AdvancementsScreen.class)
public abstract class AdvancementsScreenGlassMixin {

    @Inject(method = "drawAdvancementTree", at = @At("HEAD"))
    private void s1mp1e$glassWindow(int mouseX, int mouseY, int originX, int originY, CallbackInfo ci) {
        GlassScreenPanels.window(this, originX, originY, originX + 252, originY + 140);
    }

    @Redirect(
        method = "drawWidgets",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screen/advancement/AdvancementsScreen;blit(IIIIII)V"
        )
    )
    private void s1mp1e$dropFrame(AdvancementsScreen self, int x, int y, int u, int v, int w, int h) {
        // Glass frame (drawn before the tree) replaces the wood; keep vanilla only if glass is off.
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) {
            self.blit(x, y, u, v, w, h);
        }
    }
}
