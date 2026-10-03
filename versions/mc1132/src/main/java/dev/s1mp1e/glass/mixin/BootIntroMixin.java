package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.BootIntroScreen;
import dev.s1mp1e.client.gui.BrandIntro;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 1.13.2 stand-in for {@code SplashOverlayIntroMixin} / {@code LevelLoadingScreenGlassMixin} (there is no
 * {@code SplashScreen} overlay and no {@code LevelLoadingScreen} before 1.14; javap-read):
 * <ul>
 *   <li>{@code initializeGame} ends with {@code setScreen(new TitleScreen())} (or a {@code ConnectScreen} for a
 *       quick-connect launch, left alone): the title is wrapped in {@link BootIntroScreen}, which plays the boot intro
 *       on pure black and then reveals the title;</li>
 *   <li>{@code startIntegratedServer} opens a {@code ProgressScreen} and, until the server is up, loops
 *       "set text, draw one frame, {@code Thread.sleep(200)}" - 5 frames a second. That screen instance is remembered
 *       as the world-entry screen ({@code ProgressScreenGlassMixin} plays the brand loop on it) and, while the loop is
 *       what is on screen, the 200 ms nap is skipped (the frame limiter paces the frames) so the loop animates instead of
 *       stepping.</li>
 * </ul>
 */
@Mixin(MinecraftClient.class)
public abstract class BootIntroMixin {

    @ModifyArg(method = "initializeGame",
               at = @At(value = "INVOKE",
                        target = "Lnet/minecraft/client/MinecraftClient;setScreen(Lnet/minecraft/client/gui/screen/Screen;)V"))
    private Screen s1mp1e$bootIntro(Screen screen) {
        return screen instanceof TitleScreen ? new BootIntroScreen(screen) : screen;
    }

    @ModifyArg(method = "startIntegratedServer",
               at = @At(value = "INVOKE",
                        target = "Lnet/minecraft/client/MinecraftClient;setScreen(Lnet/minecraft/client/gui/screen/Screen;)V"))
    private Screen s1mp1e$worldEntry(Screen screen) {
        BrandIntro.worldEntryScreen = screen;
        return screen;
    }

    @Redirect(method = "startIntegratedServer",
              at = @At(value = "INVOKE", target = "Ljava/lang/Thread;sleep(J)V"))
    private void s1mp1e$loopFrames(long ms) throws InterruptedException {
        if (!BrandIntro.worldEntryLoopShown) {
            Thread.sleep(ms);       // vanilla pace while vanilla's own screen is what is shown
        }                           // else: the menu frame limiter (60 fps) paces the loop
    }
}
