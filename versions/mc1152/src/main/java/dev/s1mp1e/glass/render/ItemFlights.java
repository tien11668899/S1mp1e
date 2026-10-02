package dev.s1mp1e.glass.render;

import java.util.ArrayList;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.container.Slot;

/**
 * Items flying between inventory slots (one instance per container screen, owned by {@code ItemFlightMixin}). A flight
 * carries a copy of the moved stack from where it left (a slot) to the slot it
 * landed in, on an ease-out path with a slight upward arc and a small mid-flight lift in scale, drawn sub-pixel in the
 * container's local space. The landing slot can be kept empty until the item arrives, so the item never appears in two
 * places. Render thread only.
 *
 * <p>Ported from LiquidGlass26's {@code ItemFlights}; the timing/arc/lift/duration and the hide-by-time trap are
 * byte-for-byte. Only the draw calls changed for 1.15.2: a GUI item model ignores the caller's {@code MatrixStack}
 * and reads the RenderSystem model-view (which {@code HandledScreen.render} has already translated to the container
 * origin when the flights are drawn), so each flight's translate + scale goes onto that stack through
 * {@link GuiItems#beginItemTransform} and the stack is drawn with {@link GuiItems#drawStack} (model, bars and a count
 * that follows the same transform). {@code ItemRenderer.zOffset} is raised to 150 for the flight: above the slot
 * items (100), below the stack carried on the cursor (232).
 */
public final class ItemFlights {

   private static final float DURATION_S = 0.18F;
   private static final float ARC = 6.0F;
   private static final float LIFT = 0.12F;
   private static final float FLIGHT_Z = 150.0F;

   private static final class Flight {
      ItemStack stack;
      float x0, y0, x1, y1;
      long startNs;
      Slot target;
      boolean hideTarget;
   }

   private final ArrayList<Flight> flights = new ArrayList<>();

   /** Dev only (DevShot): slow motion factor and a log line per spawned flight. */
   public static float timeScale = 1.0F;
   public static boolean debugLog;

   public void spawn(ItemStack stack, float x0, float y0, float x1, float y1, Slot target, boolean hideTarget) {
      Flight f = new Flight();
      f.stack = stack;
      f.x0 = x0; f.y0 = y0; f.x1 = x1; f.y1 = y1;
      f.startNs = System.nanoTime();
      f.target = target;
      f.hideTarget = hideTarget;
      flights.add(f);
      if (debugLog) System.out.printf("[ItemFlights] spawn %s (%.0f,%.0f)->(%.0f,%.0f) hide=%s ns=%d%n", stack, x0, y0, x1, y1, hideTarget, f.startNs);
      if (flights.size() > 64) flights.remove(0);
   }

   /** Whether {@code slot}'s own item should be withheld this frame (a flight is still on its way there). */
   public boolean hides(Slot slot) {
      long now = System.nanoTime();
      for (Flight f : flights) {
         // by time, not by list membership: on the landing frame the slot must already show its item (the flight is
         // retired later in the same frame by draw(), which no longer draws it) — else that one frame shows nothing
         if (f.hideTarget && f.target == slot && (now - f.startNs) / 1.0e9F * timeScale / DURATION_S < 1F) return true;
      }
      return false;
   }

   public boolean isEmpty() {
      return flights.isEmpty();
   }

   /** Draw every flight (container-local coordinates) and retire the ones that landed. */
   public void draw() {
      if (flights.isEmpty()) return;
      long now = System.nanoTime();
      MinecraftClient mc = MinecraftClient.getInstance();
      ItemRenderer ir = mc.getItemRenderer();
      float prevZ = ir.zOffset;
      try {
         for (int k = flights.size() - 1; k >= 0; k--) {
            Flight f = flights.get(k);
            float t = (now - f.startNs) / 1.0e9F * timeScale / DURATION_S;
            if (t >= 1F) { flights.remove(k); continue; }
            float e = 1F - (1F - t) * (1F - t) * (1F - t);                // ease-out cubic
            float bump = (float) Math.sin(Math.PI * t);
            float x = f.x0 + (f.x1 - f.x0) * e;
            float y = f.y0 + (f.y1 - f.y0) * e - ARC * bump;
            float s = 1F + LIFT * bump;
            // translate(x + 8, y + 8) * scale(s) * translate(-8, -8)  ==  translate(x + 8 - 8s, y + 8 - 8s) * scale(s)
            GuiItems.beginItemTransform(x + 8F - 8F * s, y + 8F - 8F * s, s, s);
            try {
               ir.zOffset = FLIGHT_Z;
               GuiItems.drawStack(mc.player, f.stack, 0, 0, null);
            } finally {
               GuiItems.endItemTransform();
            }
         }
      } finally {
         ir.zOffset = prevZ;
      }
   }
}
