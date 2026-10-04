package net.minecraft.launchwrapper;

/** S1mp1e Forge 相容層的 LaunchWrapper 替身：只提供 Forge／模組會引用到的 API。 */
public interface IClassTransformer {
    byte[] transform(String name, String transformedName, byte[] basicClass);
}
