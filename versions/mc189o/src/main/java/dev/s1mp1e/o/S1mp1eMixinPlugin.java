package dev.s1mp1e.o;

import dev.s1mp1e.o.client.asm.CameraTransformer;
import dev.s1mp1e.o.glass.asm.S1mp1eTransformer;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Mixin 設定外掛：每個 S1mp1e mixin 套進目標類別後記一筆稽核（對應 1.8.9 coremod 的 auditOk），
 * 並把鏡頭相關修補的生效旗標設起來。
 */
public final class S1mp1eMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) {}

    @Override public String getRefMapperConfig() { return null; }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override public List<String> getMixins() { return null; }

    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        String m = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        String t = targetClassName.substring(targetClassName.lastIndexOf('.') + 1);
        S1mp1eTransformer.auditOk(m + " -> " + t);
        if (m.equals("GameRendererCameraMixin")) {
            CameraTransformer.gammaPatched = true;
            CameraTransformer.lookScalePatched = true;
        } else if (m.equals("ItemInHandRendererCombatMixin")) {
            CameraTransformer.handPatched = true;
        } else if (m.equals("KeyBindingMixin") || m.equals("GameOptionsMixin")) {
            CameraTransformer.keyFilterPatched = true;
        }
    }
}
