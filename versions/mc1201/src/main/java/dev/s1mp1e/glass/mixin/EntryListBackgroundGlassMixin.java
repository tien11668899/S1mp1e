package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.AllGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.StatsScreen;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * List chrome, all-glass #5 + #26 (1.20.1). {@code EntryListWidget.render} paints three tiled-dirt blits — the dark
 * list band ({@code y == top}, height {@code bottom - top}, tinted 0.125) and the dirt strips above and below the list
 * (tinted 0.25) — plus two black {@code fillGradient} shadows under the header / over the footer. With the glass
 * pipeline up and NO world loaded (#26 — out-of-game menus sit on the blurred title panorama, {@code MenuBackdrop}):
 * <ul>
 *   <li>a FULL-WIDTH list draws none of them: the rows sit straight on the panorama, like 26.2 / 1.21.1;</li>
 *   <li>a NARROW list (under 70 % of the screen — the resource-pack screen's two columns) gets a glass pane with a
 *       0x30 grey scrim where the dark band was (#5); its strips and shadows are dropped too.</li>
 * </ul>
 * With a world loaded the list chrome stays as it was (spec #26: "遊戲內維持原本做法") — except the stats screen, whose
 * dirt is dropped so {@code StatsScreenGlassMixin}'s glass plate shows (it never gets a pane). Without the glass
 * pipeline everything is vanilla.
 */
@Mixin(EntryListWidget.class)
public abstract class EntryListBackgroundGlassMixin {

    @Shadow protected int left;
    @Shadow protected int right;
    @Shadow protected int top;
    @Shadow protected int bottom;

    @Unique
    private static boolean s1mp1e$glass() {
        return GlassProgram.ensureReady() && GlassProgram.usable();
    }

    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Lnet/minecraft/util/Identifier;IIFFIIII)V"))
    private void s1mp1e$listChrome(DrawContext ctx, Identifier tex, int x, int y, float u, float v, int w, int h,
                                   int tw, int th) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!s1mp1e$glass() || (mc.world != null && !(mc.currentScreen instanceof StatsScreen))) {
            ctx.drawTexture(tex, x, y, u, v, w, h, tw, th);           // in a world: unchanged (spec #26), bar stats
            return;
        }
        boolean band = y == this.top && h == this.bottom - this.top;
        if (!band) return;                                            // dirt strips above / below the list
        boolean narrow = (this.right - this.left) < mc.getWindow().getScaledWidth() * 0.7f;
        if (!narrow || mc.currentScreen instanceof StatsScreen) return; // full-width band: dropped
        ctx.setShaderColor(1f, 1f, 1f, 1f);                           // vanilla tinted the band 0.125; vanilla resets after
        AllGlass.pane(ctx, this.left, this.top, this.right, this.bottom, 1f, 0x30000000);
    }

    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;fillGradient(Lnet/minecraft/client/render/RenderLayer;IIIIIII)V"))
    private void s1mp1e$noShadows(DrawContext ctx, RenderLayer layer, int x0, int y0, int x1, int y1, int c0, int c1, int z) {
        if (!s1mp1e$glass() || MinecraftClient.getInstance().world != null) ctx.fillGradient(layer, x0, y0, x1, y1, c0, c1, z);
    }
}
