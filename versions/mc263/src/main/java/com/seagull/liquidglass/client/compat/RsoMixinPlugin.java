package com.seagull.liquidglass.client.compat;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Gates {@code liquidglass.rso.mixins.json} (the liquid-glass restyle of Reese's Sodium Options' video settings) on
 * RSO actually being installed, so players without Sodium/RSO load the mod untouched.
 */
public final class RsoMixinPlugin implements IMixinConfigPlugin {
   private static final boolean RSO = FabricLoader.getInstance().isModLoaded("reeses-sodium-options");

   @Override public void onLoad(String mixinPackage) {}
   @Override public String getRefMapperConfig() { return null; }
   @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return RSO; }
   @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
   @Override public List<String> getMixins() { return null; }
   @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
   @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
