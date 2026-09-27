package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassScreenPanels;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.StatsScreen;
import net.minecraft.client.gui.widget.EntryListWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PORT_SPEC feature (A) — the statistics screen becomes a refracting glass plate + grey readability
 * scrim. 1.15.2 Fabric counterpart of 26.2's {@code StatsScreenGlassMixin} (identical seam to the
 * 1.14.4 sibling {@code StatsListGlassMixin}).
 *
 * <p><b>Why the shared list widget, not {@code StatsScreen}.</b> 26.2 cancels the stats screen's own
 * {@code extractMenuBackground}. 1.15.2 has no equivalent: {@code StatsScreen.render}'s main branch
 * never calls {@code renderBackground()} — the visible dark background is the tiled dirt quad that
 * the shared {@code EntryListWidget} draws behind its rows (javap-verified: StatsScreen's lists are
 * {@code AlwaysSelectedEntryListWidget} subclasses, and only the selected one renders per frame). So
 * the only place to replace that dirt is inside {@code EntryListWidget.render}, and the injection is
 * GATED on {@code minecraft.currentScreen instanceof StatsScreen} so it never touches any OTHER list
 * screen (controls, language, video, world select...).
 *
 * <p>The seam (javap-confirmed on 1.15.2): {@code render(int,int,float)} draws the dirt quad
 * ({@code Tessellator.draw()} ordinal 0), then calls {@code renderList}, then two
 * {@code renderHoleBackground} header/footer strips. {@link EntryListWidgetBackdropMixin} replaces the
 * dirt (list body + header/footer strips) with the blurred world/panorama backdrop. This mixin then
 * {@code @Inject}s right BEFORE the {@code renderList} INVOKE — after that backdrop is drawn, before
 * any row — and lays one refracting glass plate over the list body + the grey scrim (0x0E0E14 @ 0xB4).
 * Injecting at {@code renderList} rather than the earlier {@code Tessellator.draw} makes the plate
 * draw OVER the backdrop and UNDER the rows deterministically, without relying on mixin apply order.
 * The stat rows, scrollbar and the screen's title / tabs / buttons then draw on top, unchanged. A
 * fresh {@code grabNow()} inside the helper keeps the backdrop deterministic every frame (rule R4).
 * When glass is unusable the vanilla dirt / blurred backdrop stays.
 */
@Mixin(EntryListWidget.class)
public abstract class StatsListGlassMixin {

    @Shadow protected int top;
    @Shadow protected int bottom;
    @Shadow protected int left;
    @Shadow protected int right;

    @Inject(
        method = "render(IIF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/widget/EntryListWidget;renderList(IIIIF)V",
            shift = At.Shift.BEFORE
        )
    )
    private void s1mp1e$statsGlass(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || !(mc.currentScreen instanceof StatsScreen)) return;   // stats screen only
        try {
            GlassScreenPanels.stats(mc.currentScreen, this.left, this.top, this.right, this.bottom);
        } catch (Throwable ignored) {
            // a failed plate leaves the vanilla dirt (never dropped when glass is down)
        }
    }
}
