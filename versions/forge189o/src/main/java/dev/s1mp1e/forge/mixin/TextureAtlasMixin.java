package dev.s1mp1e.forge.mixin;

import net.minecraft.client.render.texture.TextureAtlas;
import net.minecraft.client.resource.manager.ResourceManager;
import net.minecraftforge.client.ForgeHooksClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Forge 的 TextureStitchEvent.Pre/Post：模組在 Pre 裡註冊自己的方塊／物品貼圖。 */
@Mixin(value = TextureAtlas.class, priority = 1050)
public abstract class TextureAtlasMixin {
    @Inject(method = "loadAndStitch", at = @At("HEAD"))
    private void s1f$pre(ResourceManager rm, CallbackInfo ci) {
        ForgeHooksClient.onTextureStitchedPre((TextureAtlas) (Object) this);
    }

    @Inject(method = "loadAndStitch", at = @At("TAIL"))
    private void s1f$post(ResourceManager rm, CallbackInfo ci) {
        ForgeHooksClient.onTextureStitchedPost((TextureAtlas) (Object) this);
    }
}
