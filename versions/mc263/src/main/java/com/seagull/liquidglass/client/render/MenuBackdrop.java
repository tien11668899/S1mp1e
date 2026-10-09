package com.seagull.liquidglass.client.render;

/**
 * Makes glass panels refract the real menu background (the title-screen / options panorama) when there is NO world
 * behind the GUI.
 *
 * <p>The base backdrop is normally grabbed at {@code GuiRenderer.render} HEAD — before anything is drawn, so in a world
 * it is the world. With no world the framebuffer is black there. But {@code GuiRenderer.render} then renders the menu
 * panorama ({@code CubeMap.render}, an immediate 3D pass straight to the main target) and only AFTER that draws the GUI
 * (the screen's blur/scrim and every widget) via {@code draw()}. So the panorama is grabbed here, at the moment between
 * those two — the main target holds the clean, bright panorama and no GUI yet, so the panels refract the panorama itself
 * (not the darkened/blurred menu backdrop the screen paints over it). Fires only on frames that render a panorama
 * (title / menus, and the world-loading screen even though the client level exists by then); screens over the live
 * world render no panorama, so there the HEAD grab (the world) stands.</p>
 */
public final class MenuBackdrop {
   private MenuBackdrop() {
   }

   private static final boolean DEBUG = Boolean.getBoolean("s1mp1e.menuBackdropDebug");
   private static int dbgFrame;

   /** Dev/probe readout. */
   public static volatile boolean lastGrabbed;

   /**
    * GuiRenderer.render, right after the panorama pass (only called when a panorama was rendered this frame) and before
    * the GUI draws: grab the panorama as the base backdrop. Also with a level present — the world-loading screen shows
    * the panorama while the client level already exists; the HEAD grab there holds the bare sky the world pass drew
    * underneath, which made the loading card a flat light-blue slab. Whenever the panorama is what is on screen, the
    * glass refracts the panorama.
    */
   public static void grabBeforeGui() {
      // 26.2 path (copy the freshly drawn panorama out of the main target). Unused on 26.3: that copy faults the
      // NVIDIA driver here, intermittently, whatever copy path is used — see renderPanoramaIntoBackdrop.
      lastGrabbed = GlassPipeline.grabBackdropNow();
      if (DEBUG && dbgFrame++ % 60 == 0) System.out.println("[MenuBackdrop] grabBeforeGui grabbed=" + lastGrabbed);
   }

   private static com.mojang.renderpearl.api.textures.GpuTextureView retarget;

   /** Non-null only while the panorama is being re-rendered into the glass backdrop (read by CubeMapRetargetMixin). */
   public static com.mojang.renderpearl.api.textures.GpuTextureView retargetView() {
      return retarget;
   }

   /**
    * 26.3: render the panorama a SECOND time, straight into the glass backdrop texture (after vanilla drew it to the
    * main target, before the GUI draws). No copy out of the main target at all.
    */
   public static void renderPanoramaIntoBackdrop(Runnable renderPanorama) {
      com.mojang.renderpearl.api.textures.GpuTextureView v = GlassPipeline.backdropViewIfReady();
      if (v == null) {
         lastGrabbed = false;
         return;
      }
      retarget = v;
      try {
         renderPanorama.run();
         lastGrabbed = true;
      } catch (Throwable t) {
         lastGrabbed = false;
         dev.s1mp1e.client.ErrorOnce.report("panorama into glass backdrop", t);
      } finally {
         retarget = null;
      }
   }
}
