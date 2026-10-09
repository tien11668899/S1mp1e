package com.seagull.liquidglass.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.joml.Matrix3x2f;

/**
 * Menu-to-menu cross-dissolve: no screen switch outside gameplay cuts hard any more.
 *
 * <p>Vanilla swaps screens in one frame: the old screen is gone on the very next frame, and the new one's vanilla text
 * and backgrounds appear at once while its glass buttons / sliders only start their 150 ms {@link ScreenOpenFade} — so
 * for a frame or two the new screen is a bare "skeleton" of floating labels (seen frame by frame with DevShot
 * {@code trans}). Here, on {@code Gui.setScreen}, the last finished frame (the outgoing screen) is copied into the glass
 * snapshot texture ({@link GlassPipeline#grabSnapshot}), and for {@link #DURATION_S} it is drawn over everything with the
 * {@code glass_fade} pipeline at a smoothstep-falling alpha. Meanwhile {@link ScreenOpenFade} is held at 1 so the
 * incoming screen is complete underneath from its first frame: the result is a true cross-dissolve with no gap and no
 * skeleton frame. A switch mid-dissolve re-grabs the current (blended) frame, so chains stay continuous.
 *
 * <p>Not applied to in-game screens that already have their own open/close motion: container screens (glass panel fade +
 * close ghost) and the chat input (its own fade). The S1mp1e settings screen IS dissolved: its own open fade yields while
 * {@link ScreenOpenFade#held} and its close switches immediately when {@link #canDissolve} (else it fades itself out).
 */
public final class ScreenTransition {
   private ScreenTransition() {}

   /** Dissolve length. */
   public static final float DURATION_S = 0.22F;

   private static long startNs;
   private static boolean active;
   private static int snapW, snapH;

   /** {@code Gui.setScreen} HEAD: {@code from} is still the current screen, {@code to} the incoming one. */
   public static void onSetScreen(Screen from, Screen to) {
      if (from == to) return;
      if (excluded(from) || excluded(to)) return;
      // Never during a resource (re)load: vanilla sets the title screen under the startup LoadingOverlay, and drawing
      // glass_fade then compiles it against half-loaded shader sources — the shared liquidglass:core/glass vertex shader
      // gets cached as invalid and every glass pipeline (buttons, round rects) breaks for the session.
      if (Minecraft.getInstance().gui.overlay() != null) return;
      // Only when the glass pipelines are ALREADY up — never call ensureReady() here. Vanilla calls setScreen very early
      // at startup, before the first resource load; initialising the pipelines then precompiles them before that load,
      // which clears them again, and their lazy recompile can't find the liquidglass shaders ("Couldn't find source for
      // VERTEX shader liquidglass:core/glass") -> every glass pipeline fails for the session. The first glass widget
      // drawn after the load initialises them as before. (The snapshot texture is created by the grab itself.)
      if (!GlassPipeline.usable() || GlassPipeline.fade() == null) return;
      GlassPipeline.grabSnapshot();
      if (!GlassPipeline.fadeUsable()) return;
      RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
      snapW = main.width;
      snapH = main.height;
      startNs = net.minecraft.util.Util.getNanos();
      active = true;
      ScreenOpenFade.holdUntil(startNs + (long) (DURATION_S * 1.0e9F));
   }

   /** Whether a switch made right now would be cross-dissolved (glass up, fade program built, no reload running). */
   public static boolean canDissolve() {
      return GlassPipeline.usable() && GlassPipeline.fade() != null && Minecraft.getInstance().gui.overlay() == null;
   }

   /**
    * A tab switch WITHIN one screen (e.g. CreateWorldScreen's tabs, the friends/stats overlays): only the tab content
    * changes while the tab bar, title and footer stay put, so vanilla swaps the content in one frame with no motion.
    * Here the whole outgoing frame is snapshot and dissolved over the incoming one exactly like a screen switch — the
    * unchanged chrome overlaps pixel-for-pixel so only the content visibly cross-fades. NOT held on {@link ScreenOpenFade}:
    * the screen instance is unchanged, so its glass widgets never restarted their open fade and are already fully drawn
    * underneath (holding would be a no-op here anyway). Called from {@code TabManager.setCurrentTab} HEAD.
    */
   public static void onTabSwitch() {
      if (!canDissolve() || GlassPipeline.fade() == null) return;
      GlassPipeline.grabSnapshot();
      if (!GlassPipeline.fadeUsable()) return;
      RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
      snapW = main.width;
      snapH = main.height;
      startNs = net.minecraft.util.Util.getNanos();
      active = true;
   }

   private static boolean excluded(Screen s) {
      return s instanceof AbstractContainerScreen<?> || s instanceof ChatScreen;
   }

   /**
    * {@code GuiRenderer.render} HEAD, after the tooltip strata were promoted: append the fading snapshot as a new last
    * stratum so it lies over everything drawn this frame (tooltips included).
    */
   public static void appendOverlay(GuiRenderState renderState) {
      if (!active) return;
      float t = (net.minecraft.util.Util.getNanos() - startNs) / 1.0e9F / DURATION_S;
      RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
      if (t >= 1F || main.width != snapW || main.height != snapH || !GlassPipeline.fadeUsable()
            || Minecraft.getInstance().gui.overlay() != null) {   // a reload started: never draw glass_fade under it
         end();
         return;
      }
      float s = t <= 0F ? 0F : t * t * (3F - 2F * t);   // smoothstep
      int alpha = Math.round((1F - s) * 255F) & 0xFF;
      if (alpha <= 1) return;
      var window = Minecraft.getInstance().getWindow();
      int w = window.getGuiScaledWidth(), h = window.getGuiScaledHeight();
      TextureSetup ts = TextureSetup.singleTexture(GlassPipeline.snapshotView(), GlassPipeline.sampler());
      renderState.nextStratum();
      // glass_fade reads the dissolve opacity from the vertex colour's BLUE channel
      renderState.addGuiElement(new GlassRectRenderState(
            GlassPipeline.fade(), ts, new Matrix3x2f(), 0, 0, w, h, 0, 0xFF000000 | alpha, null));
   }

   private static void end() {
      active = false;
      ScreenOpenFade.holdUntil(0L);
   }
}
