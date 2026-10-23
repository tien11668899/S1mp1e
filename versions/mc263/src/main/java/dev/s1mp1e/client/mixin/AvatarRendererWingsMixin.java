package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.wings.DragonWingsLayer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.model.player.PlayerModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds {@link DragonWingsLayer} to every player renderer (both skin models) after vanilla has added its own layers.
 * The layer draws only for the local player and only while the DragonWings module is on.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererWingsMixin {
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(method = "<init>", at = @At("TAIL"))
    private void s1mp1e$addWings(EntityRendererProvider.Context context, boolean slim, CallbackInfo ci) {
        RenderLayerParent<AvatarRenderState, PlayerModel> parent = (RenderLayerParent<AvatarRenderState, PlayerModel>) (Object) this;
        ((LivingEntityRendererLayersInvoker) (Object) this).s1mp1e$addLayer(new DragonWingsLayer(parent));
    }
}
