package com.seagull.liquidglass.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LiquidGlassClient implements ClientModInitializer {
   public static final String MODID = "liquidglass";
   public static final Logger LOG = LoggerFactory.getLogger("LiquidGlass");

   public void onInitializeClient() {
      LOG.info("[LiquidGlass] client initialized (v0.1.0, MC 26.2 / mojmap)");
      // Build the S1mp1e client modules + load saved config (guarded internally; never throws).
      try { dev.s1mp1e.client.ModuleManager.init(); } catch (Throwable t) { LOG.warn("[S1mp1e] module init failed", t); }
      // The menu key (RightShift) is polled by dev.s1mp1e.client.mixin.MenuKeyMixin at Minecraft.tick() RETURN.

      // DEV ONLY (set by `runClient` via -Ds1mp1e.preloadMixinTargets=true, never in production): force every mixin
      // target class to load now. Mixin transforms a class when it is defined, so this applies every injection at
      // startup and surfaces a bad target immediately — including classes that otherwise only load in-world or in a
      // specific menu (tabs, container screens, AbstractClientPlayer...). initialize=false: no static init runs.
      if (Boolean.getBoolean("s1mp1e.preloadMixinTargets")) {
         preloadMixinTargets();
      }
   }

   /** Every @Mixin target in liquidglass.mixins.json + s1mp1e.mixins.json (regenerate when adding a mixin). */
   private static final String[] MIXIN_TARGETS = {
      "net.minecraft.client.Camera",
      "net.minecraft.client.KeyMapping",
      "net.minecraft.client.Minecraft",
      "net.minecraft.client.MouseHandler",
      "net.minecraft.client.gui.GuiGraphicsExtractor",
      "net.minecraft.client.gui.Hud",
      "net.minecraft.client.gui.components.AbstractButton",
      "net.minecraft.client.gui.components.AbstractSliderButton",
      "net.minecraft.client.gui.components.tabs.MenuTabBar$MenuTabButton",
      "net.minecraft.client.gui.font.FontTexture",
      "net.minecraft.client.gui.render.GuiRenderer",
      "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen",
      "net.minecraft.client.gui.screens.inventory.AbstractFurnaceScreen",
      "net.minecraft.client.gui.screens.inventory.BeaconScreen",
      "net.minecraft.client.gui.screens.inventory.BrewingStandScreen",
      "net.minecraft.client.gui.screens.inventory.CartographyTableScreen",
      "net.minecraft.client.gui.screens.inventory.ContainerScreen",
      "net.minecraft.client.gui.screens.inventory.CraftingScreen",
      "net.minecraft.client.gui.screens.inventory.DispenserScreen",
      "net.minecraft.client.gui.screens.inventory.EnchantmentScreen",
      "net.minecraft.client.gui.screens.inventory.GrindstoneScreen",
      "net.minecraft.client.gui.screens.inventory.HopperScreen",
      "net.minecraft.client.gui.screens.inventory.InventoryScreen",
      "net.minecraft.client.gui.screens.inventory.ItemCombinerScreen",
      "net.minecraft.client.gui.screens.inventory.LoomScreen",
      "net.minecraft.client.gui.screens.inventory.MerchantScreen",
      "net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen",
      "net.minecraft.client.gui.screens.inventory.StonecutterScreen",
      "net.minecraft.client.gui.screens.inventory.tooltip.ClientTextTooltip",
      "net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil",
      "net.minecraft.client.gui.screens.recipebook.RecipeBookComponent",
      "net.minecraft.client.gui.screens.recipebook.RecipeBookTabButton",
      "net.minecraft.client.gui.screens.recipebook.RecipeButton",
      "net.minecraft.client.player.AbstractClientPlayer",
      "net.minecraft.client.renderer.GameRenderer",
      "net.minecraft.client.renderer.ItemInHandRenderer",
      "net.minecraft.client.renderer.LightmapRenderStateExtractor",
      "net.minecraft.client.renderer.entity.LivingEntityRenderer",
      "net.minecraft.client.renderer.state.gui.GlyphRenderState",
   };

   private static void preloadMixinTargets() {
      ClassLoader loader = LiquidGlassClient.class.getClassLoader();
      int ok = 0;
      for (String target : MIXIN_TARGETS) {
         try {
            Class.forName(target, false, loader);
            ok++;
         } catch (Throwable t) {
            LOG.error("[S1mp1e] mixin target preload FAILED: " + target, t);
         }
      }
      LOG.info("[S1mp1e] mixin target preload: {}/{} OK", ok, MIXIN_TARGETS.length);
   }
}
