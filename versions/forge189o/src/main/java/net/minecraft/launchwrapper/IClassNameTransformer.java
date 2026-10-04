package net.minecraft.launchwrapper;

/** LaunchWrapper 替身。 */
public interface IClassNameTransformer {
    String unmapClassName(String name);

    String remapClassName(String name);
}
