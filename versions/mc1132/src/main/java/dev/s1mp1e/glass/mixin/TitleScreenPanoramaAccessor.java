package dev.s1mp1e.glass.mixin;

import net.minecraft.class_4227;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads {@code TitleScreen.backgroundRenderer} — the private final {@link class_4227}
 * that pans the title panorama — so {@code MenuBackdrop} can keep that panorama MOVING behind the
 * world-less screens layered on top of the title screen (Options, Multiplayer, Language…), where
 * vanilla never renders it again and our capture would otherwise sit frozen (mc189 V-5).
 *
 * <p>An interface mixin, so ordinary code may cast a {@code TitleScreen} to it, exactly like
 * {@link MinecraftClientFpsAccessor}. The field is javap-verified on yarn 1.13.2+build.10:
 * {@code private final net.minecraft.class_4227 backgroundRenderer}.
 */
@Mixin(TitleScreen.class)
public interface TitleScreenPanoramaAccessor {

    @Accessor("field_20320")
    class_4227 s1mp1e$backgroundRenderer();
}
