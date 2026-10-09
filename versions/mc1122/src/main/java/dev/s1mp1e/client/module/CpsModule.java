package dev.s1mp1e.client.module;

import java.util.ArrayDeque;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.client.event.MouseEvent;
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
 * event ({@link MouseEvent#getButton()} 0 = left, 1 = right;
 * {@link MouseEvent#isButtonstate()} true = press; on 1.12.2 the fields are
 * private, so the getters are used). That event is posted from
 * {@code Minecraft.runTickMouse()}'s {@code Mouse.next()} drain loop, so every
 * hardware press is seen even when several land inside one 50 ms tick —
 * polling {@code Mouse.isButtonDown} in a tick handler would drop them. The
 * event is {@code @Cancelable} and cancelling it makes vanilla skip the click
 * entirely, so this handler NEVER touches {@code setCanceled}; it also runs at
 * HIGHEST priority with {@code receiveCanceled} so it still observes a press
 * that some other mod cancels. Only the local player's own clicks are counted.
 */
public final class CpsModule extends Module implements HudBounds, HudRenderer {

    private int lastW = 40, lastH = 10;   // last rendered footprint, for the HUD editor

    /** Width of the rolling window, in milliseconds. */
    private static final long WINDOW_MS = 1000L;

    private final Setting posX     = add(Setting.integer("PosX", 4, 0, 2000));
    private final Setting posY     = add(Setting.integer("PosY", 4, 0, 2000));
    private final Setting showRight = add(Setting.bool("Show right CPS", false));
    private final Setting color    = add(Setting.color("Colour", 0xFFFFFFFF));
    // Global no-text-shadow rule: in-game text never draws a drop shadow, so this toggle is
    // now dead. Keep it (persisted) but hide its row, matching the newer lines.
    private final Setting shadow   = add(Setting.bool("Shadow", true).hide());

    private final ArrayDeque<Long> leftClicks  = new ArrayDeque<Long>();
    private final ArrayDeque<Long> rightClicks = new ArrayDeque<Long>();

    public CpsModule() {
        super("CPS", "Combat");
        // Purely additive readout of your own data -- on by default so a fresh
        // install shows something without hand-editing config. The behaviour-
        // changing modules (crosshair replacement, old animations, no-hurt-cam)
        // stay OFF until the player opts in via their keybind.
        this.enabled = true;

    }

    @Override
    public void onEnable() {
        // Forge's EventBus.register is idempotent (it returns early for an object
        // that is already registered), and Module.setEnabled only calls onEnable on
        // a real false->true change, so the click listener is never on the bus twice.
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
        if (!e.isButtonstate()) return;                   // presses only, not releases

        Minecraft mc = Minecraft.getMinecraft();
        // In-world clicks only. MouseEvent is posted for every drained mouse event,
        // screens included, and inventory clicks are not "combat" clicks.
        if (mc.currentScreen != null) return;
        if (mc.player == null) return;

        long now = System.currentTimeMillis();
        int button = e.getButton();
        if (button == 0) {
            leftClicks.addLast(Long.valueOf(now));
        } else if (button == 1) {
            rightClicks.addLast(Long.valueOf(now));
        }
        // Prune here as well as in the render pass: while the HUD is hidden the
        // render pass returns early and would otherwise never evict, leaking stamps.
        prune(leftClicks, now);
        prune(rightClicks, now);
    }

    // ---- drawing ----------------------------------------------------------

    /**
     * Called once per frame by {@code HudRenderDispatcher} (which gates player/world/hideGUI).
     * The render half does not self-subscribe to {@code Post(TEXT)}; only the click-capture
     * {@code MouseEvent} handler stays on the bus (registered in {@link #onEnable()}). Matching
     * mc1211, the debug overlay (F3) does NOT hide this readout.
     */
    @Override
    public void renderHud() {
        // visibility (incl. the fade-out after switching off) is decided by HudRenderDispatcher via HudFade; no enabled-guard

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) return;

        long now = System.currentTimeMillis();
        int left  = prune(leftClicks, now);
        int right = prune(rightClicks, now);

        String text = String.valueOf(left) + " CPS";
        if (showRight.boolValue) {
            text = String.valueOf(left) + " | " + String.valueOf(right) + " CPS";
        }
        lastW = Math.round(dev.s1mp1e.client.gui.GlassFont.width(text));
        lastH = Math.round(dev.s1mp1e.client.gui.GlassFont.height());

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
        dev.s1mp1e.client.hud.HudText.draw(text, (float) posX.intValue, (float) posY.intValue,
                                      color.colorValue, shadow.boolValue);
    }

    // ---- HudBounds (for the HUD editor) ----
    public int hudX() { return posX.intValue; }
    public int hudY() { return posY.intValue; }
    public void hudSetPos(int x, int y) { posX.setInt(x); posY.setInt(y); }
    public int hudW() { return lastW > 0 ? lastW : 40; }
    public int hudH() { return lastH > 0 ? lastH : 10; }
    public void hudResetPos() { posX.reset(); posY.reset(); }
    public String hudLabel() { return name; }

    /** Drop stamps that fell out of the window and return what is left. */
    private static int prune(ArrayDeque<Long> stamps, long now) {
        while (!stamps.isEmpty() && now - stamps.peekFirst().longValue() >= WINDOW_MS) {
            stamps.pollFirst();
        }
        return stamps.size();
    }
}
