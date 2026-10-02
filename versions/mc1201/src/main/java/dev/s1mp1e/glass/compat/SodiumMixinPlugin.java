package dev.s1mp1e.glass.compat;

import java.util.List;
import java.util.Set;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Gates {@code s1mp1e.sodium.mixins.json} (the liquid-glass restyle of Sodium's own Video Settings screen) on Sodium
 * actually being installed, so players without Sodium load the mod completely untouched. Mirrors 26.2's
 * {@code RsoMixinPlugin}. The whole config is {@code required:false}, so even a failed injection only logs a warning
 * instead of crashing.
 */
public final class SodiumMixinPlugin implements IMixinConfigPlugin {

    private static final boolean SODIUM = FabricLoader.getInstance().isModLoaded("sodium");

    @Override public void onLoad(String mixinPackage) {}

    @Override public String getRefMapperConfig() { return null; }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return SODIUM; }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override public List<String> getMixins() { return null; }

    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
