package dev.s1mp1e.glass;

import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric client entrypoint for the 1.16.5 line. The glass render layer is
 * identical to the Forge lines; only the hook plumbing (mixins + Fabric
 * callbacks) is version-specific.
 */
public final class S1mp1eClient implements ClientModInitializer {
    public static final String MODID = "s1mp1e";

    @Override
    public void onInitializeClient() {
        System.out.println("[S1mp1e] client init — liquid glass 0.1.0 (1.17.1 Fabric)");
    }
}
