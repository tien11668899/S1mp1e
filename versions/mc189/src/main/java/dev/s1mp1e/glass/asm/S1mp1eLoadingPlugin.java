package dev.s1mp1e.glass.asm;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

/**
 * Coremod entry point.
 *
 * <p>Forge 1.8.9 exposes no render event for {@code GuiButton.drawButton} and no
 * way to cancel a container screen's background blit, so an event-only mod can
 * only paint <em>underneath</em> the vanilla textures — which then cover it
 * completely. Replacing them outright needs bytecode, which is what
 * {@link S1mp1eTransformer} does.
 *
 * <p>{@code @MCVersion} is deliberately omitted so FML doesn't refuse to load
 * this on a differently-numbered 1.8.9 build; the transformer itself is
 * defensive and no-ops on anything it doesn't recognise.
 */
@IFMLLoadingPlugin.Name("S1mp1e")
@IFMLLoadingPlugin.TransformerExclusions({ "dev.s1mp1e.glass.asm.", "dev.s1mp1e.client.asm." })
@IFMLLoadingPlugin.SortingIndex(1001)   // after Forge's own deobf transformer
public final class S1mp1eLoadingPlugin implements IFMLLoadingPlugin {

    /** Set by FML: true in a dev workspace (deobf names), false in production (SRG). */
    public static boolean deobfuscated = false;

    @Override
    public String[] getASMTransformerClass() {
        return new String[] {
            "dev.s1mp1e.glass.asm.S1mp1eTransformer",
            "dev.s1mp1e.client.asm.CombatTransformer"
        };
    }

    @Override
    public String getModContainerClass() { return null; }

    @Override
    public String getSetupClass() { return null; }

    @Override
    public void injectData(Map<String, Object> data) {
        Object runtime = data.get("runtimeDeobfuscationEnabled");
        // runtimeDeobfuscationEnabled == true  -> production, classes are SRG
        // runtimeDeobfuscationEnabled == false -> dev workspace, classes are MCP
        if (runtime instanceof Boolean) {
            deobfuscated = !((Boolean) runtime).booleanValue();
        }
        System.out.println("[S1mp1e/ASM] loading plugin active, deobf=" + deobfuscated);
    }

    @Override
    public String getAccessTransformerClass() { return null; }
}
