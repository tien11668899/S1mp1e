package com.seagull.liquidglass.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.seagull.liquidglass.client.animation.Fade;
import com.seagull.liquidglass.client.mixin.GuiGraphicsExtractorAccessor;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Apple-style icons for the game's interactive glyph buttons (user: "只要替換遊戲內的交互按鈕 — 打勾、叉叉、翻頁、篩選按鈕與翻頁箭頭").
 *
 * <p>Every vanilla GUI sprite draw funnels through {@code GuiGraphicsExtractor.blitSprite(pipeline, id, x, y, w, h, color)}
 * ({@code SfIconMixin} hooks its HEAD). For the sprites listed in {@link #MAP} the pixel-art glyph is replaced by an SF
 * Symbol (from andrewtavis/sf-symbols-online, the same set the launcher uses; white PNGs trimmed to their ink, shipped in
 * {@code assets/liquidglass/textures/gui/sf/}), aspect-fitted into the region the vanilla glyph occupied and tinted by
 * meaning (confirm green, cancel red, active/hover system blue, else white). Sprites that are a whole button (recipe
 * filter, checkbox, cross button) get the BTN glass body first — with the hotbar corner ({@link GlassCorners}), which
 * turns the small square ones into iOS-style circles — and a 100 ms hover lift like every other glass button.
 *
 * <p>Without Fabric API the mod's assets are not a resource pack, so the PNGs are read from the classpath and registered
 * as {@link LinearTexture}s (bilinear sampling: the ~30 px glyphs are drawn at 8–17 GUI px). Anything that fails to load
 * falls back to the vanilla sprite. Render thread only.
 */
public final class SfIcons {
   private SfIcons() {}

   /** Dev only (DevShot icon gallery): draw the vanilla sprite instead, for a side-by-side comparison. */
   public static boolean devBypass;

   private static final int WHITE = 0xFFFFFFFF;
   private static final int BLUE = 0xFF0A84FF;    // system blue (same as the caret)
   private static final int GREEN = 0xFF30D158;   // system green
   private static final int RED = 0xFFFF453A;     // system red

   private enum Frame { NONE, PILL, CHECK_OFF, CHECK_ON, CROSS }

   /** Region of the drawn rect the glyph may use (fractions), how much of it to fill, tints and body. */
   private record Icon(String glyph, float fx0, float fy0, float fx1, float fy1, float fill, int tint, int hoverTint,
                       Frame frame) {}

   private static final Map<String, Icon> MAP = new HashMap<>();

   private static void put(String sprite, Icon icon) {
      MAP.put(sprite, icon);
   }

   /** Glyph-only sprite; the "_highlighted" variant (if any) uses the hover tint. */
   private static void glyph(String sprite, String glyph, float fx0, float fy0, float fx1, float fy1, float fill, int tint,
                             boolean hasHighlight) {
      Icon icon = new Icon(glyph, fx0, fy0, fx1, fy1, fill, tint, hasHighlight ? BLUE : tint, Frame.NONE);
      put(sprite, icon);
      if (hasHighlight) put(sprite + "_highlighted", icon);   // hover blends tint -> blue over 100 ms
   }

   static {
      // recipe book: page arrows + the "craftable only" filter (crafting + furnace books)
      glyph("recipe_book/page_forward", "chevron.right", 0.0F, 0.0F, 0.917F, 1.0F, 0.72F, WHITE, true);
      glyph("recipe_book/page_backward", "chevron.left", 0.083F, 0.0F, 1.0F, 1.0F, 0.72F, WHITE, true);
      for (String f : new String[]{"recipe_book/filter", "recipe_book/furnace_filter"}) {
         Icon on = new Icon("line.horizontal.3.decrease", 0.0F, 0.0F, 1.0F, 1.0F, 0.62F, BLUE, BLUE, Frame.PILL);
         Icon off = new Icon("line.horizontal.3.decrease", 0.0F, 0.0F, 1.0F, 1.0F, 0.62F, WHITE, WHITE, Frame.PILL);
         put(f + "_enabled", on);
         put(f + "_enabled_highlighted", on);
         put(f + "_disabled", off);
         put(f + "_disabled_highlighted", off);
      }
      // written / lectern books: page turn
      glyph("widget/page_forward", "chevron.right", 0.0F, 0.0F, 1.0F, 1.0F, 0.85F, WHITE, true);
      glyph("widget/page_backward", "chevron.left", 0.0F, 0.0F, 1.0F, 1.0F, 0.85F, WHITE, true);
      // beacon confirm / cancel, the small green tick
      glyph("container/beacon/confirm", "checkmark", 0.0F, 0.0F, 1.0F, 1.0F, 0.72F, GREEN, false);
      glyph("container/beacon/cancel", "xmark", 0.0F, 0.0F, 1.0F, 1.0F, 0.64F, RED, false);
      glyph("icon/checkmark", "checkmark", 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, GREEN, false);
      // server / world lists: join, reorder
      glyph("server_list/join", "play.fill", 0.5F, 0.156F, 0.938F, 0.844F, 0.62F, WHITE, true);
      glyph("world_list/join", "play.fill", 0.312F, 0.156F, 0.75F, 0.844F, 0.62F, WHITE, true);
      // other-version / marked worlds show marked_join (a play triangle with a corner mark) instead of join, and
      // warning / error for incompatible or broken worlds — replace all so they match the adapted join.
      glyph("world_list/marked_join", "play.fill", 0.5F, 0.156F, 0.938F, 0.844F, 0.62F, WHITE, true);
      glyph("world_list/warning", "exclamationmark.triangle.fill", 0.09F, 0.156F, 0.47F, 0.844F, 1.0F, 0xFFFFD60A, true);
      glyph("world_list/error", "exclamationmark.octagon.fill", 0.09F, 0.156F, 0.47F, 0.844F, 1.0F, RED, true);
      glyph("server_list/move_up", "chevron.up", 0.094F, 0.156F, 0.438F, 0.375F, 1.0F, WHITE, true);
      glyph("server_list/move_down", "chevron.down", 0.094F, 0.625F, 0.438F, 0.844F, 1.0F, WHITE, true);
      glyph("transferable_list/move_up", "chevron.up", 0.562F, 0.156F, 0.906F, 0.375F, 1.0F, WHITE, true);
      glyph("transferable_list/move_down", "chevron.down", 0.562F, 0.625F, 0.906F, 0.844F, 1.0F, WHITE, true);
      // statistics column sort, spectator menu
      glyph("statistics/sort_up", "chevron.up", 0.222F, 0.278F, 0.833F, 0.667F, 1.0F, WHITE, false);
      glyph("statistics/sort_down", "chevron.down", 0.222F, 0.333F, 0.833F, 0.722F, 1.0F, WHITE, false);
      glyph("spectator/scroll_left", "chevron.left", 0.062F, 0.125F, 0.5F, 0.812F, 0.9F, WHITE, false);
      glyph("spectator/scroll_right", "chevron.right", 0.5F, 0.125F, 0.938F, 0.812F, 0.9F, WHITE, false);
      glyph("spectator/close", "xmark.circle.fill", 0.062F, 0.062F, 0.938F, 0.938F, 0.9F, RED, false);
      glyph("friends/cancel", "xmark.circle.fill", 0.062F, 0.062F, 0.938F, 0.938F, 0.9F, RED, false);
      // whole-button sprites: checkbox (iOS circle), cross button
      Icon offBox = new Icon(null, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F, WHITE, WHITE, Frame.CHECK_OFF);
      Icon onBox = new Icon("checkmark", 0.0F, 0.0F, 1.0F, 1.0F, 0.5F, WHITE, WHITE, Frame.CHECK_ON);
      put("widget/checkbox", offBox);
      put("widget/checkbox_highlighted", offBox);
      put("widget/checkbox_selected", onBox);
      put("widget/checkbox_selected_highlighted", onBox);
      Icon cross = new Icon("xmark", 0.0F, 0.0F, 1.0F, 1.0F, 0.46F, WHITE, WHITE, Frame.CROSS);
      put("widget/cross_button", cross);
      put("widget/cross_button_highlighted", cross);
   }

   // ---- textures ------------------------------------------------------------------------------------------------

   private record Glyph(Identifier id, int w, int h) {}

   private static final Map<String, Glyph> GLYPHS = new HashMap<>();
   private static final Map<String, Boolean> FAILED = new HashMap<>();

   private static com.mojang.blaze3d.textures.GpuSampler linear;

   /** A DynamicTexture sampled bilinearly (vanilla GUI textures are nearest). */
   private static final class LinearTexture extends DynamicTexture {
      LinearTexture(Supplier<String> label, NativeImage image) {
         super(label, image);
         if (linear == null) {
            linear = RenderSystem.getDevice().createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                  FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty());
         }
         this.sampler = linear;
      }
   }

   private static Glyph glyphTex(String name) {
      Glyph gl = GLYPHS.get(name);
      if (gl != null || FAILED.containsKey(name)) return gl;
      String path = "/assets/liquidglass/textures/gui/sf/" + name + ".png";
      try (InputStream in = SfIcons.class.getResourceAsStream(path)) {
         if (in != null) {
            NativeImage img = NativeImage.read(in);
            Identifier id = Identifier.fromNamespaceAndPath("liquidglass", "textures/gui/sf/" + name + ".png");
            int w = img.getWidth(), h = img.getHeight();
            Minecraft.getInstance().getTextureManager().register(id, new LinearTexture(() -> "liquidglass:sf/" + name, img));
            gl = new Glyph(id, w, h);
            GLYPHS.put(name, gl);
            return gl;
         }
      } catch (Throwable ignored) {
      }
      FAILED.put(name, Boolean.TRUE);
      return null;
   }

   // ---- hover fade (per on-screen position; sprites swap to "_highlighted" instantly in vanilla) ----------------------

   private static final Map<Long, Fade> HOVER = new HashMap<>();

   private static float hover(String base, int x, int y, boolean over) {
      long key = ((long) x << 40) ^ ((long) y << 20) ^ base.hashCode();
      Fade f = HOVER.get(key);
      if (f == null) {
         if (HOVER.size() > 512) HOVER.clear();
         f = new Fade(over ? 1.0F : 0.0F, 100.0F);
         HOVER.put(key, f);
      }
      float target = over ? 1.0F : 0.0F;
      if (f.target() != target) {
         f.snap(f.value());
         f.to(target);
      }
      return f.value();
   }

   private static int mix(int a, int b, float t) {
      if (t <= 0.0F) return a;
      if (t >= 1.0F) return b;
      int ar = a >>> 16 & 0xFF, ag = a >>> 8 & 0xFF, ab = a & 0xFF, aa = a >>> 24;
      int br = b >>> 16 & 0xFF, bg = b >>> 8 & 0xFF, bb = b & 0xFF, ba = b >>> 24;
      return Math.round(aa + (ba - aa) * t) << 24 | Math.round(ar + (br - ar) * t) << 16
            | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
   }

   private static int withAlpha(int argb, int alpha255) {
      int a = Math.round((argb >>> 24) * (alpha255 / 255.0F)) & 0xFF;
      return a << 24 | (argb & 0xFFFFFF);
   }

   /** Draw SF Symbol {@code glyph} aspect-fitted into the box (x0,y0)-(x1,y1), tinted {@code argb}; false if missing. */
   public static boolean drawGlyph(GuiGraphicsExtractor g, String glyph, float x0, float y0, float x1, float y1, int argb) {
      Glyph gl = glyphTex(glyph);
      if (gl == null || x1 <= x0 || y1 <= y0) return false;
      float s = Math.min((x1 - x0) / gl.w, (y1 - y0) / gl.h);
      float dw = gl.w * s, dh = gl.h * s;
      g.pose().pushMatrix();
      g.pose().translate((x0 + x1) / 2.0F - dw / 2.0F, (y0 + y1) / 2.0F - dh / 2.0F);
      g.pose().scale(s, s);
      g.blit(RenderPipelines.GUI_TEXTURED, gl.id, 0, 0, 0.0F, 0.0F, gl.w, gl.h, gl.w, gl.h, argb);
      g.pose().popMatrix();
      return true;
   }

   // ---- draw ----------------------------------------------------------------------------------------------------

   /** Draw the replacement for {@code spritePath} (minecraft namespace); false = not ours, draw vanilla. */
   public static boolean draw(GuiGraphicsExtractor g, String spritePath, int x, int y, int w, int h, int color) {
      if (devBypass) return false;
      Icon icon = MAP.get(spritePath);
      if (icon == null || w <= 0 || h <= 0) return false;
      Glyph gl = icon.glyph == null ? null : glyphTex(icon.glyph);
      if (icon.glyph != null && gl == null) return false;
      boolean framed = icon.frame != Frame.NONE;
      if (framed && !(GlassPipeline.ensureReady() && GlassPipeline.btnUsable())) return false;

      boolean highlighted = spritePath.endsWith("_highlighted");
      String base = highlighted ? spritePath.substring(0, spritePath.length() - "_highlighted".length()) : spritePath;
      float hov = hover(base, x, y, highlighted);
      int alpha = color >>> 24;

      if (framed) {
         float open = ScreenOpenFade.value(Minecraft.getInstance().gui.screen());
         int alphaByte = Math.round(alpha * open) & 0xFF;
         int liftG = Math.round(255.0F * (1.0F - 0.81F * hov)) & 0xFF;
         int col = 255 << 24 | GlassCorners.knobByte(w, h) << 16 | liftG << 8 | alphaByte;
         ((GuiGraphicsExtractorAccessor) g).liquidglass$guiRenderState().addGuiElement(new GlassRectRenderState(
               GlassPipeline.btn(), TextureSetup.noTexture(), g.pose(), x, y, x + w, y + h, 10, col, null));
         if (icon.frame == Frame.CHECK_ON && GlassPipeline.roundUsable()) {
            float r = Math.min(w, h) / 2.0F;
            ((GuiGraphicsExtractorAccessor) g).liquidglass$guiRenderState().addGuiElement(new RoundRectRenderState(
                  GlassPipeline.round(), g.pose(), x + 1.0F, y + 1.0F, x + w - 1.0F, y + h - 1.0F, r - 1.0F,
                  withAlpha(BLUE, Math.round(alphaByte * 0.92F)), null));
         }
         alpha = alphaByte;
      }
      if (gl == null) return true;

      int tint = withAlpha(mix(icon.tint, icon.hoverTint, hov), alpha);
      float bx0 = x + icon.fx0 * w, by0 = y + icon.fy0 * h, bx1 = x + icon.fx1 * w, by1 = y + icon.fy1 * h;
      float bw = (bx1 - bx0) * icon.fill, bh = (by1 - by0) * icon.fill;
      float s = Math.min(bw / gl.w, bh / gl.h);
      float dw = gl.w * s, dh = gl.h * s;
      float cx = (bx0 + bx1) / 2.0F, cy = (by0 + by1) / 2.0F;
      g.pose().pushMatrix();
      g.pose().translate(cx - dw / 2.0F, cy - dh / 2.0F);
      g.pose().scale(s, s);
      g.blit(RenderPipelines.GUI_TEXTURED, gl.id, 0, 0, 0.0F, 0.0F, gl.w, gl.h, gl.w, gl.h, tint);
      g.pose().popMatrix();
      return true;
   }
}
