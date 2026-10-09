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
        // #23: creative-inventory style — the tab row is a band of the SAME glass sheet (no separate tab tiles, see
        // AdvancementSpritesGlassMixin). Extend the panel by one tab's depth (28 = tab 32 minus the 4 px overlap) on each
        // side that carries tabs; only when >1 tab (vanilla draws the tab row only then). Orientation from the
        // package-private AdvancementTabType ordinal (0 ABOVE / 1 BELOW / 2 LEFT / 3 RIGHT), read reflectively.
        int x0 = originX, y0 = originY, x1 = originX + 252, y1 = originY + 140;
        if (this.tabs != null && this.tabs.size() > 1) {
            for (net.minecraft.client.gui.screen.advancement.AdvancementTab t : this.tabs.values()) {
                switch (s1mp1e$tabOrdinal(t)) {
                    case 0: y0 = originY - 28; break;            // ABOVE
                    case 1: y1 = originY + 140 + 28; break;      // BELOW
                    case 2: x0 = originX - 28; break;            // LEFT
                    case 3: x1 = originX + 252 + 28; break;      // RIGHT
                    default: break;
                }
            }
        }
        GlassScreenPanels.window(this, x0, y0, x1, y1);
    }

    /**
     * The tab's {@code AdvancementTabType} ordinal (0 ABOVE / 1 BELOW / 2 LEFT / 3 RIGHT). {@code AdvancementTabType} is
     * package-private (can't be named here) and {@code AdvancementTab} has no public {@code getType()} on 1.14.4, so read
     * the single 4-constant enum field reflectively (found by its enum type, not its name — works under any mapping).
     * Cached. -1 if not found.
     */
    @org.spongepowered.asm.mixin.Unique private static java.lang.reflect.Field s1mp1e$typeField;
    @org.spongepowered.asm.mixin.Unique private static boolean s1mp1e$typeFieldResolved;

    @org.spongepowered.asm.mixin.Unique
    private static int s1mp1e$tabOrdinal(net.minecraft.client.gui.screen.advancement.AdvancementTab t) {
        try {
            if (!s1mp1e$typeFieldResolved) {
                s1mp1e$typeFieldResolved = true;
                for (java.lang.reflect.Field f : net.minecraft.client.gui.screen.advancement.AdvancementTab.class
                        .getDeclaredFields()) {
                    Object[] consts = f.getType().getEnumConstants();
                    if (consts != null && consts.length == 4) { f.setAccessible(true); s1mp1e$typeField = f; break; }
                }
            }
            if (s1mp1e$typeField == null) return -1;
            Object v = s1mp1e$typeField.get(t);
            return v instanceof Enum ? ((Enum<?>) v).ordinal() : -1;
        } catch (Throwable ignored) {
            return -1;
        }
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

    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final
    private java.util.Map<net.minecraft.advancement.Advancement, net.minecraft.client.gui.screen.advancement.AdvancementTab> tabs;
    @org.spongepowered.asm.mixin.Shadow
    private net.minecraft.client.gui.screen.advancement.AdvancementTab selectedTab;

    /**
     * A tab click cross-dissolves the whole window (26.2 parity; the 1.16.5 line has this in AdvancementsGlassMixin).
     * At HEAD, before {@code selectedTab} flips; a no-op re-select of the current tab is skipped.
     */
    @Inject(method = "selectTab", at = @At("HEAD"))
    private void s1mp1e$dissolveAdvTab(net.minecraft.advancement.Advancement advancement, CallbackInfo ci) {
        net.minecraft.client.gui.screen.advancement.AdvancementTab next =
                advancement == null ? null : this.tabs.get(advancement);
        if (next != null && this.selectedTab != null && next != this.selectedTab) {
            dev.s1mp1e.glass.render.ScreenDissolve.onTabSwitch();
        }
    }
}
