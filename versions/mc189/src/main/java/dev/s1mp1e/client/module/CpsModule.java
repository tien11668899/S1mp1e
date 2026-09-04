package dev.s1mp1e.client.module;

import java.util.ArrayDeque;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Clicks-per-second readout for the left and right mouse buttons.
 *
 * <p><b>FAIR-PLAY GUARDRAIL — DO NOT "IMPROVE" THIS CLASS.</b> This module
 * ONLY OBSERVES. It must never inject, shape, smooth, schedule, or suggest a
 * click, and it must never display click-timing advice (no "target CPS", no
 * jitter/blocking hints, no consistency score). It reads input and draws a
 * number. Anything beyond that turns a legal HUD element into an automation
 * aid and gets the whole client banned.
 *
 * <p>Counting is a rolling one-second window rather than a per-tick average:
 * a click is stamped with {@link System#currentTimeMillis()} and stamps older
 * than 1000 ms are evicted, so the displayed value is exactly "clicks in the
 * last second" and reacts immediately when the player stops.
 *
 * <p>Clicks come from Forge's {@link MouseEvent}, which carries the raw LWJGL
 * event ({@code button} 0 = left, 1 = right; {@code buttonstate} true = press).
 * That event is posted from {@code Minecraft.runTick()}'s {@code Mouse.next()}
 * drain loop, so every hardware press is seen even when several land inside one
 * 50 ms tick — polling {@code Mouse.isButtonDown} in a tick handler would drop
 * them. The event is {@code @Cancelable} and cancelling it makes vanilla skip
 * the click entirely, so this handler NEVER touches {@code setCanceled}; it
 * also runs at HIGHEST priority so it still observes a press that some other
 * mod later cancels.
 */
public final class CpsModule extends Module {

    /** Width of the rolling window, in milliseconds. */
    private static final long WINDOW_MS = 1000L;

    private final Setting posX     = add(Setting.integer("PosX", 4, 0, 2000));
    private final Setting posY     = add(Setting.integer("PosY", 4, 0, 2000));
    private final Setting showRight = add(Setting.bool("Show right CPS", false));
    private final Setting color    = add(Setting.color("Colour", 0xFFFFFFFF));
    private final Setting shadow   = add(Setting.bool("Shadow", true));

    private final ArrayDeque<Long> leftClicks  = new ArrayDeque<Long>();
    private final ArrayDeque<Long> rightClicks = new ArrayDeque<Long>();

    public CpsModule() {
        super("CPS", "HUD");
        // Purely additive readout of your own data -- on by default so a fresh
        // install shows something without hand-editing config. The behaviour-
        // changing modules (crosshair replacement, old animations, no-hurt-cam)
        // stay OFF until the player opts in via their keybind.
        this.enabled = true;

    }

    @Override
    public void onEnable() {
        // Registering at runtime makes FML log "Unable to determine registrant
        // mod" (no active mod container outside load); it falls back to the
        // Minecraft container and works, the line is cosmetic.
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public void onDisable() {
        MinecraftForge.EVENT_BUS.unregister(this);
        leftClicks.clear();
        rightClicks.clear();
    }

    // ---- observation ------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onMouse(MouseEvent e) {
        if (!enabled) return;
        if (!e.buttonstate) return;                       // presses only, not releases

        Minecraft mc = Minecraft.getMinecraft();
        // In-world clicks only. MouseEvent still fires for screens that set
        // allowUserInput (chat), and inventory clicks are not "combat" clicks.
        if (mc.currentScreen != null) return;
        if (mc.thePlayer == null) return;

        long now = System.currentTimeMillis();
        if (e.button == 0) {
            leftClicks.addLast(Long.valueOf(now));
        } else if (e.button == 1) {
            rightClicks.addLast(Long.valueOf(now));
        }
        // Prune here as well as in the render pass: while F3 is held the HUD
        // returns early and would otherwise never evict, leaking stamps.
        prune(leftClicks, now);
        prune(rightClicks, now);
    }

    // ---- drawing ----------------------------------------------------------

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post e) {
        if (!enabled) return;
        // TEXT is the one element GuiIngameForge always posts once per frame
        // (renderHUDText is called unconditionally), so it is a stable anchor.
        if (e.type != RenderGameOverlayEvent.ElementType.TEXT) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        if (mc.gameSettings.showDebugInfo) return;        // F3 owns the top-left corner

        long now = System.currentTimeMillis();
        int left  = prune(leftClicks, now);
        int right = prune(rightClicks, now);

        String text = String.valueOf(left) + " CPS";
        if (showRight.boolValue) {
            text = String.valueOf(left) + " | " + String.valueOf(right) + " CPS";
        }

        // The hotbar glass pass leaves a tinted colour on the stack; reset so
        // the setting's colour is what actually lands on screen.
        // Defeat GlStateManager's colour cache (see GlassRenderer.endBatch):
        // a bare color(1,1,1,1) no-ops when the cache already reads white
        // while the real GL colour is not, which leaks a tint onto the
        // glass pipeline that draws after us.
        GlStateManager.color(0f, 0f, 0f, 0f);
        GlStateManager.color(1f, 1f, 1f, 1f);
        // FontRenderer promotes an all-zero alpha to opaque, so a packed ARGB
        // value from the colour setting can be handed over as-is.
        mc.fontRendererObj.drawString(text, (float) posX.intValue, (float) posY.intValue,
                                      color.colorValue, shadow.boolValue);
    }

    /** Drop stamps that fell out of the window and return what is left. */
    private static int prune(ArrayDeque<Long> stamps, long now) {
        while (!stamps.isEmpty() && now - stamps.peekFirst().longValue() >= WINDOW_MS) {
            stamps.pollFirst();
        }
        return stamps.size();
    }
}
