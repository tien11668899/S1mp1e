package dev.s1mp1e.forge.mixin;

import net.minecraft.client.sound.instance.SoundInstance;
import net.minecraft.client.sound.system.SoundEngine;
import net.minecraftforge.client.ForgeHooksClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Forge 的 PlaySoundEvent：監聽者可以換掉或取消要播的音效。 */
@Mixin(value = SoundEngine.class, priority = 1050)
public abstract class SoundEngineMixin {
    @Shadow public abstract void play(SoundInstance sound);

    @Unique private boolean s1f$replaying;

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void s1f$play(SoundInstance sound, CallbackInfo ci) {
        if (s1f$replaying) return;
        SoundInstance r = ForgeHooksClient.playSound((SoundEngine) (Object) this, sound);
        if (r == sound) return;
        ci.cancel();
        if (r == null) return;
        s1f$replaying = true;
        try {
            play(r);
        } finally {
            s1f$replaying = false;
        }
    }
}
