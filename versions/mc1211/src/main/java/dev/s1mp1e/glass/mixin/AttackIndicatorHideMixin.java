package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.module.AttackRingModule;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * While {@link AttackRingModule} is on (and the ring can actually draw), drop ONLY vanilla's crosshair attack-indicator
 * sprites (full / background / progress) in {@code InGameHud.renderCrosshair}; the crosshair itself and every other blit
 * run unchanged. (When the S1mp1e Crosshair module is on, {@code CrosshairMixin} cancels the whole method anyway.)
 */
@Mixin(InGameHud.class)
public abstract class AttackIndicatorHideMixin {

    @Shadow @Final private static Identifier CROSSHAIR_ATTACK_INDICATOR_FULL_TEXTURE;
    @Shadow @Final private static Identifier CROSSHAIR_ATTACK_INDICATOR_BACKGROUND_TEXTURE;
    @Shadow @Final private static Identifier CROSSHAIR_ATTACK_INDICATOR_PROGRESS_TEXTURE;

    @Unique
    private static boolean s1mp1e$hide(Identifier id) {
        return AttackRingModule.active() && GlassProgram.arcUsable()
            && (id == CROSSHAIR_ATTACK_INDICATOR_FULL_TEXTURE
                || id == CROSSHAIR_ATTACK_INDICATOR_BACKGROUND_TEXTURE
                || id == CROSSHAIR_ATTACK_INDICATOR_PROGRESS_TEXTURE);
    }

    @WrapOperation(method = "renderCrosshair",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/DrawContext;drawGuiTexture(Lnet/minecraft/util/Identifier;IIII)V"))
    private void s1mp1e$hideIndicator(DrawContext ctx, Identifier id, int x, int y, int w, int h, Operation<Void> original) {
        if (s1mp1e$hide(id)) return;
        original.call(ctx, id, x, y, w, h);
    }

    @WrapOperation(method = "renderCrosshair",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/DrawContext;drawGuiTexture(Lnet/minecraft/util/Identifier;IIIIIIII)V"))
    private void s1mp1e$hideIndicatorPartial(DrawContext ctx, Identifier id, int a, int b, int c, int d, int e, int f,
                                             int w, int h, Operation<Void> original) {
        if (s1mp1e$hide(id)) return;
        original.call(ctx, id, a, b, c, d, e, f, w, h);
    }
}
