package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassScreens;
import dev.s1mp1e.glass.render.MenuBackdrop;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.client.util.math.MatrixStack;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla list-screen backgrounds → liquid glass (mc189 V-4 list half): world select, server list,
 * options sub-lists, resource-pack lists, … replace their tiled dirt with the blurred title panorama
 * (no world) or the blurred world (in-game), dim the row body, and re-blit the same blurred strips over
 * the header/footer so overflowing rows still clip.
 *
 * <p>On 1.16.5 {@code EntryListWidget.render(MatrixStack,int,int,float)} gates the two dirt draws with two
 * private booleans (both javap-confirmed as a single {@code GETFIELD} each in {@code render}):
 * <ul>
 *   <li>{@code field_26846} — the interior dirt, read once BEFORE {@code renderList}. We draw the blurred
 *       backdrop + a {@code 0x80000000} body scrim and return {@code false} so the dirt is skipped.</li>
 *   <li>{@code field_26847} — the top/bottom dirt strips + edge shadow, read once AFTER {@code renderList}
 *       (so they still mask overflowing rows). We re-blit the same blurred texture over {@code [0,top)} and
 *       {@code [bottom,height)} plus a 1&nbsp;px {@code 0x33FFFFFF} hairline on the edge facing the rows, and
 *       return {@code false}.</li>
 * </ul>
 * When the blur is unusable, or the list's render was overridden by another mod (ModMenu, malilib) so the
 * fields are not read, both hooks return the original value and vanilla draws its dirt. Everything is in
 * try/catch so a failure falls back to vanilla dirt rather than crashing.
 */
@Mixin(EntryListWidget.class)
public abstract class EntryListGlassMixin {

    @Shadow protected int top;
    @Shadow protected int bottom;
    @Shadow protected int left;
    @Shadow protected int right;
    @Shadow protected int width;
    @Shadow protected int height;

    /** The backdrop texture drawn for the current list frame, remembered so the strip hook re-blits it. */
    @Unique private int s1mp1e$listTex;
    @Unique private boolean s1mp1e$listActive;

    @ModifyExpressionValue(method = "render",
            at = @At(value = "FIELD",
                     target = "Lnet/minecraft/client/gui/widget/EntryListWidget;field_26846:Z",
                     opcode = Opcodes.GETFIELD))
    private boolean s1mp1e$listInterior(boolean original, @Local(argsOnly = true) MatrixStack matrices) {
        this.s1mp1e$listActive = false;
        // Statistics (feature A): the full-screen glass plate + scrim was laid by the list's renderBackground
        // (StatsListGlassMixin) — no dirt and no extra body slab on top of it.
        if (original && GlassScreens.isStatsList(this)) return false;
        // Respect the list's own choice: only replace a dirt background the list ACTUALLY draws
        // (original == true, e.g. world-select / server-list / options sub-lists). A list that disabled
        // its background via method_31322(false) — such as the in-panel SocialInteractionsPlayerListWidget,
        // which sits on the Social glass panel — must be left alone, or we paint a stray blurred+0x80000000
        // slab across its bounds (the black slab beside the social panel). Never draw glass it didn't want.
        if (!original) return original;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            int tex;
            if (mc.world == null) {
                // draw() re-renders the live panorama (V-5) before blurring where enabled, so it must be
                // attempted before any readiness test.
                if (!MenuBackdrop.draw()) return original;
                tex = MenuBackdrop.panoramaTex();
            } else {
                if (!GlassProgram.ensureReady() || !GlassProgram.blurUsable()) return original;
                if (!MenuBackdrop.drawLive(MenuBackdrop.RADIUS, MenuBackdrop.DIM)) return original;
                tex = SceneCapture.texture();
            }
            if (tex == 0) return original;
            this.s1mp1e$listTex = tex;
            this.s1mp1e$listActive = true;
            // darken the list body so the rows read over the blur
            DrawableHelper.fill(matrices, this.left, this.top, this.right, this.bottom, 0x80000000);
            return false;
        } catch (Throwable t) {
            return original;
        }
    }

    @ModifyExpressionValue(method = "render",
            at = @At(value = "FIELD",
                     target = "Lnet/minecraft/client/gui/widget/EntryListWidget;field_26847:Z",
                     opcode = Opcodes.GETFIELD))
    private boolean s1mp1e$listStrips(boolean original, @Local(argsOnly = true) MatrixStack matrices) {
        try {
            // Statistics: re-lay the same plate over the header/footer bands (masks rows scrolled past the edges).
            if (original && GlassScreens.isStatsList(this)) {
                GlassScreens.statsStrips(matrices, this.left, this.right, this.top, this.bottom);
                return false;
            }
            if (!this.s1mp1e$listActive || this.s1mp1e$listTex == 0) return original;
            // re-blit the same backdrop only under the header/footer strips
            MenuBackdrop.drawTexture(this.s1mp1e$listTex, MenuBackdrop.RADIUS, MenuBackdrop.DIM, 0f, this.top);
            MenuBackdrop.drawTexture(this.s1mp1e$listTex, MenuBackdrop.RADIUS, MenuBackdrop.DIM, this.bottom, this.height);
            // 1 px separators on the edges facing the rows (top strip's bottom edge, bottom strip's top edge)
            DrawableHelper.fill(matrices, this.left, this.top - 1, this.right, this.top, 0x33FFFFFF);
            DrawableHelper.fill(matrices, this.left, this.bottom, this.right, this.bottom + 1, 0x33FFFFFF);
            return false;
        } catch (Throwable t) {
            return original;
        }
    }
}
