package dev.s1mp1e.glass.render;

/**
 * When the recipe book opens/closes, the WHOLE inventory glides to its new place instead of jumping. Vanilla toggles the
 * book and, in the same click handler, moves the container's {@code x} (leftPos) by ~77 px in one frame; here a single
 * critically damped spring interpolates {@code x} from its old value to its new one so the panel, slots, items, labels,
 * the player preview and the recipe button all slide together (driven by {@code RecipeBookInvGlideMixin}, which
 * overrides {@code HandledScreen.x} to {@link #animatedX()} for the frame).
 *
 * <h3>The book slides out from BEHIND the inventory (26.2 parity)</h3>
 * 26.2 made "behind" with deferred render-state strata and an opaque-glass mask. This line draws the book AFTER (above)
 * the inventory, as a mix of immediate raw-GL glass and deferred {@code DrawContext} draws — but all of them honour two
 * global things: the {@code RenderSystem} model-view matrix and the GL scissor. So {@code RecipeBookGlassMixin} brackets
 * the whole book render with a model-view translate of {@link #bookShift} and a scissor at the inventory's animated
 * left edge ({@link #animatedX}), flushing at both ends: the book travels from under the inventory's closed position to
 * its open place and only the part that has cleared the inventory's edge is visible. Closing reverses, and
 * {@link #closing} keeps the book drawing for the length of the slide although vanilla already marked it hidden.
 * Narrow windows (the book overlays the inventory, {@code x} never moves) are not slid. Render thread only; one recipe
 * screen at a time.
 */
public final class RecipeBookSlide {
   private RecipeBookSlide() {}

   private static final float W = 14.0F;             // ~0.35 s settle (26.2's value)

   /** Dev only (DevShot): slow motion. */
   public static float timeScale = 1.0F;

   private static boolean armed;
   private static boolean armedOpening;
   private static long armedNs;

   private static boolean active;
   private static boolean opening;
   private static long startNs;
   private static int fromX, toX;
   private static Object armedComponent, component;

   /** The category tab column sticks out this far left of the book body's x origin. */
   private static final int TAB_W = 30;

   /** The book is about to toggle ({@code isOpening} = it was hidden). Called from {@code RecipeBookWidget.toggleOpen}. */
   public static void arm(Object bookComponent, boolean isOpening) {
      armed = true;
      armedOpening = isOpening;
      armedNs = System.nanoTime();
      armedComponent = bookComponent;
   }

   /** Called by the screen when its {@code x} changed; starts the glide. Returns whether a glide was started. */
   public static boolean start(int leftFrom, int leftTo) {
      if (leftFrom == leftTo) { armed = false; return false; }
      boolean stale = armed && System.nanoTime() - armedNs > 200_000_000L;
      opening = (armed && !stale) ? armedOpening : (leftTo > leftFrom);
      component = (armed && !stale) ? armedComponent : null;
      armed = false;
      fromX = leftFrom;
      toX = leftTo;
      startNs = System.nanoTime();
      active = true;
      return true;
   }

   // ---- 1.20.1: one detection point shared by the book render and the screen render ----
   private static Object seenScreen;
   private static int seenX = Integer.MIN_VALUE;

   /**
    * Report the recipe screen's real {@code x} for this frame; starts the glide when it moved since the last report and
    * advances it. Called from BOTH {@code RecipeBookWidget.render} HEAD and {@code HandledScreen.render} HEAD (whichever
    * runs first sees the change; the second call of the frame is a no-op): in 1.20.1 the book renders BEFORE the
    * inventory, so waiting for the screen's own render would leave the book one frame behind.
    */
   public static void observe(Object screen, int x) {
      if (screen != seenScreen) {
         seenScreen = screen;
         seenX = x;
      } else if (x != seenX) {
         start(seenX, x);
         seenX = x;
      }
      update();
   }

   public static void cancel() {
      active = false;
      armed = false;
   }

   private static float progress() {
      float t = (System.nanoTime() - startNs) / 1.0e9F * timeScale;
      if (t <= 0F) return 0F;
      float p = 1F - (1F + W * t) * (float) Math.exp(-W * t);
      return p >= 0.998F ? 1F : p;
   }

   /** Advance; returns whether a glide is in progress this frame. */
   public static boolean update() {
      if (!active) return false;
      if (progress() >= 1F) { active = false; return false; }
      return true;
   }

   public static boolean active() { return active; }

   /** 0 = at the start edge, 1 = fully arrived. */
   public static float openness() {
      return progress();
   }

   /** 0 = the book is hidden behind the inventory, 1 = fully open — runs backwards while closing. */
   public static float bookOpenness() {
      float p = progress();
      return opening ? p : 1F - p;
   }

   /** Is {@code bookComponent} sliding (so its render must be shifted + clipped this frame)? */
   public static boolean slides(Object bookComponent) {
      return active && component == bookComponent && progress() < 1F;
   }

   /** Is {@code bookComponent} still sliding shut (so it must keep drawing although vanilla already hid it)? */
   public static boolean closing(Object bookComponent) {
      return active && !opening && component == bookComponent && progress() < 1F;
   }

   /**
    * Horizontal offset for the whole book this frame. Closed, the book (tab column included) lies exactly under the
    * inventory's closed panel — its left edge at the inventory's closed left edge — so the inventory covers it
    * completely; opening, it slides left out from underneath to its open place while the inventory glides right.
    */
   public static float bookShift(int bookOpenX) {
      if (!active) return 0F;
      int leftClosed = opening ? fromX : toX;
      return (1F - bookOpenness()) * (leftClosed + TAB_W - bookOpenX);
   }

   /** The book fades in as it slides out (and out as it slides back under). */
   public static float bookFade() {
      if (!active) return 1F;
      return 0.1F + 0.9F * bookOpenness();
   }

   /** The inventory's animated left edge (screen x). */
   public static int animatedX() {
      return Math.round(fromX + (toX - fromX) * progress());
   }

   /** When the recipe cascade should start: after most of the slide-out on an opening, else now. */
   public static long cascadeBase() {
      long now = System.nanoTime();
      long delay = (long) (120_000_000L / Math.max(0.01F, timeScale));
      if (armed && armedOpening) return armedNs + delay;
      if (active && opening) return Math.max(now, startNs + delay);
      return now;
   }
}
