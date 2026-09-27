package dev.s1mp1e.glass.compat;

import net.minecraft.class_4463;
import net.minecraft.class_4465;

import java.util.Map;

/**
 * Registers {@link S1mp1eResourcePack} as an always-enabled client pack. This is the
 * 1.13.2 stand-in for fabric-resource-loader's {@code ModResourcePackCreator}: it is added
 * to the client's pack manager ({@code class_4462}) via
 * {@code MinecraftClient.method_18199().method_21351(this)} (see {@code ModResourcePackMixin}).
 *
 * <p>The pack is inserted with {@code class_4465$class_4466.BOTTOM}, right after the pinned
 * vanilla pack, so user resource packs still override us — yet {@code FontManager}'s
 * provider-list reversal still consults our TTF provider before vanilla's bitmap providers,
 * giving the whole-game PingFang look while keeping vanilla's ascii/accented/legacy_unicode
 * glyphs as the fallback.
 *
 * <p>{@code class_4465.method_21359(name, alwaysEnabled=true, factory=S1mp1eResourcePack::new,
 * containerFactory, BOTTOM)} returns null only when metadata parsing fails AND the pack is
 * not always-enabled; ours is always-enabled, so the null guard is belt-and-braces.
 */
public final class S1mp1eResourcePackCreator implements class_4463 {

    @Override
    public <T extends class_4465> void method_21356(Map<String, T> map, class_4465.class_4467<T> factory) {
        T c = class_4465.method_21359(
                "s1mp1e",
                true,                          // alwaysEnabled: fudges missing meta, pins us on
                S1mp1eResourcePack::new,
                factory,
                class_4465.class_4466.BOTTOM); // just after the pinned vanilla pack
        if (c != null) {
            map.put("s1mp1e", c);
        }
    }
}
