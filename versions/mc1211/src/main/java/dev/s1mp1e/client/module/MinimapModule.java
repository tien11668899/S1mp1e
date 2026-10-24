package dev.s1mp1e.client.module;

import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.minimap.MinimapHud;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.InputUtil;

/**
 * Glass minimap (docs/MINIMAP_SPEC.md). The module only owns the settings and the HUD-editor bounds; the data layer is
 * {@code minimap.MinimapData} and the drawing {@link MinimapHud}.
 *
 * <p>Fair-play scope (see the spec): terrain the client already has, the player's own marker everywhere; other players
 * and the Tab radar (mobs, items) only in a world this client hosts. No caves, no ceiling dimensions.
 */
public final class MinimapModule extends Module implements HudBounds, HudRenderer {

    public static final String ROUND = "Round", SQUARE = "Rounded square";
    public static final String FOLLOW = "Follow facing", NORTH_UP = "North up";
    public static final String CLOSE = "Close", MEDIUM = "Medium", FAR = "Far";
    public static final String APPLE = "Apple", CLASSIC = "Classic";

    public final Setting shape       = add(Setting.mode("Shape", ROUND, ROUND, SQUARE));
    public final Setting orientation = add(Setting.mode("Orientation", FOLLOW, FOLLOW, NORTH_UP));
    public final Setting zoom        = add(Setting.mode("Zoom", MEDIUM, CLOSE, MEDIUM, FAR));
    public final Setting size        = add(Setting.integer("Size", 96, 64, 160));
    // Apple: the clean Apple-Maps palette with smooth hill shading. Classic: the vanilla map item (Soft colours applies to it).
    public final Setting style       = add(Setting.mode("Map style", APPLE, APPLE, CLASSIC));
    public final Setting soft        = add(Setting.bool("Soft colours", true));
    public final Setting bezel       = add(Setting.number("Bezel opacity", 0.85D, 0.20D, 1.00D));
    public final Setting players     = add(Setting.bool("Show players", true));
    public final Setting names       = add(Setting.bool("Player names", false));
    public final Setting radar       = add(Setting.bool("Tab radar", true));
    // M opens the full-screen map (gui.MinimapScreen); GLFW key code (77 = M), 0 = off
    public final Setting fullscreenKey = add(Setting.key("Fullscreen key", 77));
    // Default X past any screen width: the HUD clamps it to the right edge, so the map starts top-right everywhere.
    public final Setting posX        = add(Setting.integer("X", 4000, 0, 4000));
    public final Setting posY        = add(Setting.integer("Y", 4, 0, 4000));

    private static MinimapModule instance;
    private long disabledSince;

    public MinimapModule() { super("Minimap", "HUD"); instance = this; }

    public static MinimapModule get() { return instance; }

    @Override public void onEnable() { disabledSince = 0L; }

    @Override public void onDisable() { disabledSince = System.nanoTime(); }

    /** Client tick: free the texture once the switch-off fade is over, or when the world is gone. */
    public static void tick() {
        MinimapModule m = instance;
        if (m == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean off = !m.enabled && m.disabledSince != 0L && System.nanoTime() - m.disabledSince > 1_000_000_000L;
        pollFullscreen(mc, m);
        if (off || mc.world == null) MinimapHud.release();
    }

    private static boolean fullWasDown, fullArmed;

    /**
     * Full-screen map key edge, polled every client tick: opens the map in a world with no screen open. Armed only after
     * the key has been released, so the press that closed the map (or typed an M into chat) does not reopen it.
     */
    private static void pollFullscreen(MinecraftClient mc, MinimapModule m) {
        int k = m.fullscreenKey.intValue;
        boolean down = k > 0 && m.enabled && mc.isWindowFocused() && mc.getWindow() != null
                && InputUtil.isKeyPressed(mc.getWindow().getHandle(), k);
        if (!down) fullArmed = true;
        if (fullArmed && down && !fullWasDown && mc.currentScreen == null && mc.player != null && mc.world != null) {
            fullArmed = false;
            mc.setScreen(new dev.s1mp1e.client.gui.MinimapScreen());
        }
        fullWasDown = down;
    }

    @Override
    public void renderHud(DrawContext ctx) {
        MinimapHud.render(this, ctx);
    }

    // ---- HudBounds (the HUD editor drags the clamped, on-screen position) ----
    public int hudX() { return MinimapHud.clampX(this); }
    public int hudY() { return MinimapHud.clampY(this); }
    public void hudSetPos(int x, int y) { posX.setInt(x); posY.setInt(y); }
    public int hudW() { return size.intValue; }
    public int hudH() { return size.intValue; }
    public void hudResetPos() { posX.reset(); posY.reset(); }
    public String hudLabel() { return name; }
}
