package dev.s1mp1e.client.module;

import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.glass.hook.GlassHudHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.client.GuiIngameForge;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Visualises the player's own HIDDEN saturation as an effect painted DIRECTLY ON the vanilla food bar:
 * a soft golden tint plus a moving highlight "glint" whose strength tracks the saturation buffer
 * (saturation/20).
 *
 * <p><b>Alignment — no position setting.</b> The effect tracks the food bar AUTOMATICALLY. mc1211 captures
 * the bar's live-transformed rect through {@code InGameHudFoodMixin}; 1.12.2 has a real FOOD overlay element,
 * so the counterpart is a {@code RenderGameOverlayEvent.Pre(FOOD)} listener at LOW priority (after any mod
 * that might cancel the element at NORMAL): {@code GuiIngameForge.renderFood} computes the bar as right edge
 * {@code w/2 + 91}, 81 px wide and 9 tall, top {@code h - GuiIngameForge.right_height} — read at Pre time,
 * before renderFood bumps {@code right_height}, so the rect follows whatever rows other mods stacked above
 * it. Our own {@link GlassHudHandler} lifts the whole status-bar cluster up by
 * {@link GlassHudHandler#DECO_LIFT} (pushed at Pre HIGHEST), so the on-screen bar sits that much higher — the
 * same lift is subtracted here, and the glint is drawn at those absolute coords in the HUD pass (matrix back
 * to identity at Post(TEXT)). No capture this frame means no food bar this frame (creative/spectator, riding,
 * or another mod cancelled FOOD), so nothing is drawn.
 *
 * <p><b>Note.</b> Saturation is the HIDDEN buffer that depletes BEFORE the food bar drops and cannot be
 * refilled at full hunger, so it is 0 much of the time — the glint only shows for a while after eating.
 * That is by design (it visualises the hidden buffer); "nothing showing" usually just means "no saturation".
 *
 * <p>Fair play: reads {@code mc.player.getFoodStats().getSaturationLevel()} only — the local player's own
 * attribute.
 */
public final class HungerSaturationHudModule extends Module implements HudRenderer {

    public final Setting color = add(Setting.color("Glint colour", 0xFFF2C24B));   // saturation gold

    /** The food-bar rect captured at THIS overlay pass's Pre(FOOD); cleared at Pre(ALL), consumed by renderHud. */
    private boolean captured;
    private int capRight, capTop;

    public HungerSaturationHudModule() { super("HungerHUD", "HUD"); this.enabled = false; }

    @Override
    public void onEnable() {
        // Only the rect capture listens on the bus; the draw is driven by HudRenderDispatcher.
        // EventBus.register is idempotent and setEnabled fires this only on a real change.
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public void onDisable() {
        MinecraftForge.EVENT_BUS.unregister(this);
        captured = false;
    }

    // ---- capture: where GuiIngameForge is about to draw the food bar this frame ----

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onOverlayPre(RenderGameOverlayEvent.Pre e) {
        RenderGameOverlayEvent.ElementType t = e.getType();
        if (t == RenderGameOverlayEvent.ElementType.ALL) {   // a new overlay pass: forget last frame's bar
            captured = false;
            return;
        }
        if (t != RenderGameOverlayEvent.ElementType.FOOD || !enabled) return;
        try {
            ScaledResolution sr = e.getResolution() != null ? e.getResolution()
                                                            : new ScaledResolution(Minecraft.getMinecraft());
            capRight = sr.getScaledWidth() / 2 + 91;
            capTop   = sr.getScaledHeight() - GuiIngameForge.right_height - GlassHudHandler.DECO_LIFT;
            captured = true;
        } catch (Throwable ignored) {
            captured = false;   // no rect -> no glint, never a crash
        }
    }

    // ---- draw ----

    @Override
    public void renderHud() {
        boolean have = captured;
        captured = false;             // one capture feeds exactly one draw
        if (!enabled || !have) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null) return;
        if (mc.gameSettings.hideGUI && mc.currentScreen == null) return;
        // Vanilla food bar only shows in survival/adventure (never creative/spectator).
        if (mc.playerController == null || !mc.playerController.gameIsSurvivalOrAdventure()) return;

        float ratio = mc.player.getFoodStats().getSaturationLevel() / 20f;
        if (ratio < 0f) ratio = 0f; else if (ratio > 1f) ratio = 1f;
        if (ratio <= 0.01f) return;   // no buffer -> nothing to show

        // The captured food-bar rect (see class note), already lifted by DECO_LIFT like the glass cluster.
        int x1 = capRight;
        int x0 = x1 - 81;
        int y0 = capTop;
        int y1 = y0 + 9;
        int gold = color.colorValue & 0xFFFFFF;

        // base golden tint over the whole bar — alpha scales with saturation
        int baseA = Math.round(30f + ratio * 95f);     // ~30..125 alpha
        Gui.drawRect(x0, y0, x1, y1, (baseA << 24) | gold);

        // moving highlight band (enchant-glint feel), clipped to the bar
        float t = (System.nanoTime() % 1_800_000_000L) / 1.8e9f;   // 0..1 scroll every 1.8s
        int span = x1 - x0, band = 18;
        int bx = x0 - band + Math.round(t * (span + band * 2));
        for (int i = -band; i <= band; i++) {
            int px = bx + i;
            if (px < x0 || px >= x1) continue;
            float f = 1f - Math.abs(i) / (float) band;   // triangular peak
            int a = Math.round((0.35f + 0.65f * ratio) * f * f * 190f);   // stronger, still fades with saturation
            if (a > 0) Gui.drawRect(px, y0, px + 1, y1, (a << 24) | 0xFFFFFF);
        }
        // Gui.drawRect leaves blend disabled; re-enable + reset the colour cache (never switch blend off on exit).
        GlStateManager.enableBlend();
        GlStateManager.color(0f, 0f, 0f, 0f);
        GlStateManager.color(1f, 1f, 1f, 1f);
    }
}
