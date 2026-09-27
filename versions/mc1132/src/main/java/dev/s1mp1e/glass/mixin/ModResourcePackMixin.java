package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.compat.S1mp1eResourcePackCreator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.RunArgs;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Registers the mod's own always-enabled resource pack ({@link S1mp1eResourcePackCreator}) so
 * that on 1.13.2 — which has no fabric-resource-loader — the whole-game PingFang TTF and its
 * {@code assets/minecraft/font/default.json} override reach the ResourceManager.
 *
 * <p>Hooked at the RETURN of {@code MinecraftClient(RunArgs)} (the single constructor, one
 * return). By that point the constructor has already created the pack manager
 * ({@code field_19941}) and registered the vanilla + folder creators, but
 * {@code initializeGame()} has NOT yet run {@code options.method_18259(field_19941)} (the pack
 * scan + enable) or {@code reloadResources()}, so our always-enabled creator is picked up on
 * the very first scan.
 *
 * <p>The PUBLIC getter {@code MinecraftClient.method_18199()} is used (returns the private
 * final {@code field_19941}) rather than a {@code @Shadow} of the unmapped field;
 * {@code method_21351} = addCreator, which adds to a mutable {@code HashSet}. Wrapped in
 * try/catch (logged once): a failure leaves fonts vanilla (CJK still renders via
 * legacy_unicode) instead of crashing the game.
 */
@Mixin(MinecraftClient.class)
public class ModResourcePackMixin {

    private static final Logger S1MP1E_LOG = LogManager.getLogger("S1mp1e/ResourcePack");
    private static boolean s1mp1e$loggedFail = false;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void s1mp1e$registerResourcePack(RunArgs args, CallbackInfo ci) {
        try {
            ((MinecraftClient) (Object) this).method_18199()
                    .method_21351(new S1mp1eResourcePackCreator());
        } catch (Throwable t) {
            if (!s1mp1e$loggedFail) {
                s1mp1e$loggedFail = true;
                S1MP1E_LOG.warn("[S1mp1e] could not register the mod resource pack; "
                        + "fonts fall back to vanilla", t);
            }
        }
    }
}
