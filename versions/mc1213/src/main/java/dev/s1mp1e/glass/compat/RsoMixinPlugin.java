package dev.s1mp1e.glass.compat;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Gates {@code s1mp1e.rso.mixins.json} (Reese's Sodium Options 1.8 drawn by {@link SodiumGlass}) on both Sodium and
 * Reese's Sodium Options being installed, so everyone else loads the mod untouched. The config is {@code required:false}:
 * a failed injection (another RSO version) only logs a warning, and RSO then keeps its own screen.
 */
public final class RsoMixinPlugin implements IMixinConfigPlugin {
    private static final boolean ACTIVE = FabricLoader.getInstance().isModLoaded("sodium")
            && FabricLoader.getInstance().isModLoaded("reeses-sodium-options");

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return ACTIVE; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
