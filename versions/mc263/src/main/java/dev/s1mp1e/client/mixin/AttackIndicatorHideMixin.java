package dev.s1mp1e.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.s1mp1e.client.module.AttackRingModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * While {@link AttackRingModule} is on, drop ONLY vanilla's crosshair attack-indicator sprites (full / background /
 * progress) in {@code Hud.extractCrosshair}; the crosshair itself and every other blit run unchanged. (When the S1mp1e
 * Crosshair module is on, {@code CrosshairHideMixin} already cancels the whole method, so nothing here matters then.)
 */
@Mixin(Hud.class)
public abstract class AttackIndicatorHideMixin {

    @Shadow @Final private static Identifier CROSSHAIR_ATTACK_INDICATOR_FULL_SPRITE;
    @Shadow @Final private static Identifier CROSSHAIR_ATTACK_INDICATOR_BACKGROUND_SPRITE;
    @Shadow @Final private static Identifier CROSSHAIR_ATTACK_INDICATOR_PROGRESS_SPRITE;

    @Unique
    private static boolean s1mp1e$isIndicator(Identifier id) {
        return id == CROSSHAIR_ATTACK_INDICATOR_FULL_SPRITE
            || id == CROSSHAIR_ATTACK_INDICATOR_BACKGROUND_SPRITE
            || id == CROSSHAIR_ATTACK_INDICATOR_PROGRESS_SPRITE;
    }

    @WrapOperation(
        method = "extractCrosshair",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V")
    )
    private void s1mp1e$hideIndicator(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier id, int x, int y, int w, int h,
                                      Operation<Void> original) {
        if (AttackRingModule.active() && s1mp1e$isIndicator(id)) return;
        original.call(g, pipeline, id, x, y, w, h);
    }

    @WrapOperation(
        method = "extractCrosshair",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIIIIII)V")
    )
    private void s1mp1e$hideIndicatorPartial(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier id, int a, int b, int c, int d,
                                             int e, int f, int w, int h, Operation<Void> original) {
        if (AttackRingModule.active() && s1mp1e$isIndicator(id)) return;
        original.call(g, pipeline, id, a, b, c, d, e, f, w, h);
    }
}
