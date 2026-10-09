package com.seagull.liquidglass.client.compat.essential;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixins;

/**
 * Registers the Essential compat mixins (glass + zh_TW) LATE — on purpose, not in fabric.mod.json.
 *
 * <p>The usual Essential download is a "container" mod whose loader puts the real Essential (with its Elementa and
 * UniversalCraft) on the classpath only inside its own preLaunch. A config declared in fabric.mod.json is prepared before
 * that, finds none of its targets and never applies; registered after client init it is too late (Essential has already
 * loaded Elementa). So it is registered from our own preLaunch entrypoint, which must run AFTER essential-loader's.
 *
 * <p>Order: Fabric invokes preLaunch entrypoints in mod order, and in production that order is the mod ids sorted
 * alphabetically ({@code essential-loader} &lt; {@code s1mp1e}), so essential-loader always goes first. Dependencies
 * ({@code recommends}) do not change it. Only dev shuffles the order — build.gradle sets
 * {@code -Dfabric.debug.disableModShuffle} so dev runs match production. Client init calls {@link #retry()} again as a
 * last resort (normally too late to mix into Elementa).
 *
 * <p>Never mix into essential-loader itself: applying any mixin to a class loaded before its preLaunch initialises the
 * MixinExtras service early, Essential's own mixin config then fails ("The MixinExtras service has already been
 * selected") and every Essential screen breaks. Registering at the game's {@code main} is ignored by Mixin.
 * The probe is by resource, never by loading a class (that would make it too early to mix into).
 */
public final class EssentialPreLaunch implements PreLaunchEntrypoint {
   private static final Logger LOG = LoggerFactory.getLogger("LiquidGlass");
   private static boolean registered;

   @Override
   public void onPreLaunch() {
      retry();
   }

   /** Register once, as soon as Essential's Elementa is on the classpath. */
   public static synchronized void retry() {
      if (registered) return;
      try {
         if (EssentialPreLaunch.class.getClassLoader().getResource("gg/essential/elementa/components/UIBlock.class") != null) {
            Mixins.addConfiguration("liquidglass.essential.mixins.json");
            registered = true;
            LOG.info("[LiquidGlass] Essential found: glass + zh_TW compat registered");
         } else {
            LOG.info("[LiquidGlass] Essential not on the classpath (yet)");
         }
      } catch (Throwable t) {
         LOG.warn("[LiquidGlass] Essential compat not registered", t);
      }
   }
}
