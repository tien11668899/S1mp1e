package com.seagull.liquidglass.client.render;


/**
 * The recipe book slides out from BEHIND the inventory instead of popping in, and slides back behind it when closed.
 * Vanilla toggles the book and moves the inventory {@code leftPos} by ~77 px in one frame; here the inventory glides to
 * its new place while the book travels from under the inventory (its start position is the inventory's closed left edge)
 * to its open place. The book is drawn in the stratum ABOVE the inventory, so "behind" is made with a clip: only the part
 * left of the inventory's (animated) left edge is shown. One critically damped spring drives everything.
 *
 * <p>Arming: {@code RecipeBookComponent.toggleVisibility} HEAD calls {@link #arm}; the screen's next frame sees
 * {@code leftPos} change and {@link #start}s with the before/after positions (robust to how the toggle is wired).
 * Narrow windows (book overlays the inventory) are not slid. Render thread only; one book on screen at a time.
 */
public final class RecipeBookSlide {
   private RecipeBookSlide() {}

   private static final float W = 14.0F;             // ~0.35 s settle

   /** Dev only (DevShot): slow motion. */
   public static float timeScale = 1.0F;

   private static boolean armed;
   private static boolean armedOpening;
   private static long armedNs;
   private static boolean active;
   private static boolean opening;
   private static long startNs;
   private static int leftClosed, leftOpen;
   private static Object component;

   /** The book is about to toggle ({@code opening} = it was hidden). */
   public static void arm(Object bookComponent, boolean isOpening) {
      armed = true;
      armedOpening = isOpening;
      armedNs = net.minecraft.util.Util.getNanos();
      component = bookComponent;
   }

   /** Called by the screen when its leftPos changed; starts the slide if a toggle is armed. */
   public static boolean start(int leftFrom, int leftTo) {
      if (!armed || net.minecraft.util.Util.getNanos() - armedNs > 200_000_000L) { armed = false; return false; }
      armed = false;
      opening = armedOpening;
      leftClosed = opening ? leftFrom : leftTo;
      leftOpen = opening ? leftTo : leftFrom;
      startNs = armedNs;
      active = true;
      return true;
   }

   public static void cancel() {
      active = false;
      armed = false;
   }

   private static float progress() {
      float t = (net.minecraft.util.Util.getNanos() - startNs) / 1.0e9F * timeScale;
      if (t <= 0F) return 0F;
      float p = 1F - (1F + W * t) * (float) Math.exp(-W * t);
      return p >= 0.998F ? 1F : p;
   }

   /** Advance; returns whether a slide is in progress this frame. */
   public static boolean update() {
      if (!active) return false;
      if (progress() >= 1F) { active = false; return false; }
      return true;
   }

   public static boolean active() { return active; }

   /** 0 = closed (book hidden behind the inventory), 1 = open. */
   public static float openness() {
      float p = progress();
      return opening ? p : 1F - p;
   }

   /** Is {@code bookComponent} still sliding shut (so it must keep drawing although vanilla already hid it)? */
   public static boolean closing(Object bookComponent) {
      return active && !opening && component == bookComponent && progress() < 1F;
   }

   /** The inventory's animated left edge (screen x). */
   public static float inventoryLeft() {
      return leftClosed + (leftOpen - leftClosed) * openness();
   }

   /** The category tab column sticks out this far left of the book body's x origin. */
   private static final int TAB_W = 30;

   /**
    * Horizontal offset for the whole book this frame. Closed, the book (tab column included) lies exactly under the
    * inventory's closed panel — its left edge at the inventory's closed left edge — so the inventory covers it
    * completely; opening, it slides left out from underneath to its open place while the inventory glides right, the
    * clip showing only what has cleared the inventory's edge. Closing reverses.
    */
   public static float bookShift(int bookOpenX) {
      if (!active) return 0F;
      return (1F - openness()) * (leftClosed + TAB_W - bookOpenX);
   }

   /** The book fades in as it slides out (and out as it slides back under). */
   public static float bookFade() {
      if (!active) return 1F;
      return 0.1F + 0.9F * openness();
   }

   /** When the recipe cascade should start: after most of the slide on an opening, else now. */
   public static long cascadeBase() {
      long now = net.minecraft.util.Util.getNanos();
      if (armed && armedOpening) return armedNs + (long) (120_000_000L / Math.max(0.01F, timeScale));
      if (active && opening) return Math.max(now, startNs + (long) (120_000_000L / Math.max(0.01F, timeScale)));
      return now;
   }
}
