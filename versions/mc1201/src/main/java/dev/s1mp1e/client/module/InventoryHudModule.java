package dev.s1mp1e.client.module;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;

/**
 * Hold a key (default Tab) to PEEK your own main inventory without opening the inventory screen: the 27
 * main slots (the three rows above the hotbar) as item icons on a liquid-glass panel.
 *
 * <p><b>Position.</b> It is a {@link HudBounds} module, so it shows as a draggable box in the HUD editor
 * (which draws the box from {@link #hudX}/{@link #hudY}/{@link #hudW}/{@link #hudH} — it never needs the
 * peek key held). {@code X}/{@code Y} default to {@code -1} = AUTO (horizontally centred, just above the
 * hotbar); the moment you drag it in the editor they become an absolute position, and "reset" returns it to
 * auto. So it can be placed anywhere, yet still centres itself on any screen size until you move it.
 *
 * <p><b>Fair play.</b> Reads {@code mc.player.getInventory().main} — your own items, information you already
 * have. OBSERVE-ONLY: it never moves, swaps, uses, or drops anything.
 */
public final class InventoryHudModule extends Module implements HudBounds, HudRenderer {

    private static final int SLOT = 18;         // 16px icon + 2px gap
    private static final int COLS = 9, ROWS = 3, PAD = 4;

    public final Setting key   = add(Setting.integer("Key (GLFW)", 258, 0, 400));   // GLFW_KEY_TAB
    public final Setting scale = add(Setting.number("Scale", 1.0D, 0.5D, 2.0D));
    public final Setting posX  = add(Setting.integer("X", -1, -1, 4000));            // -1 = auto-centre
    public final Setting posY  = add(Setting.integer("Y", -1, -1, 4000));            // -1 = auto (above hotbar)
    public final Setting bg    = add(Setting.bool("Background", true));
    public int lastW = 170, lastH = 62;

    public InventoryHudModule() { super("InventoryHUD", "HUD"); this.enabled = false; }

    private float sc()     { return (float) scale.doubleValue; }
    private int panelW()   { return Math.round((COLS * SLOT + PAD * 2) * sc()); }
    private int panelH()   { return Math.round((ROWS * SLOT + PAD * 2) * sc()); }

    /** Effective top-left X: the setting if placed (>=0), else horizontally centred for this screen. */
    private int effX() {
        if (posX.intValue >= 0) return posX.intValue;
        return (MinecraftClient.getInstance().getWindow().getScaledWidth() - panelW()) / 2;
    }
    /** Effective top-left Y: the setting if placed (>=0), else floated just above the hotbar. */
    private int effY() {
        if (posY.intValue >= 0) return posY.intValue;
        return MinecraftClient.getInstance().getWindow().getScaledHeight() - 45 - panelH();
    }

    @Override
    public void renderHud(DrawContext ctx) {
        if (!enabled) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        if (mc.currentScreen != null) return;   // real inventory / a screen is open -> don't double up
        int k = key.intValue;
        if (k <= 0 || !InputUtil.isKeyPressed(mc.getWindow().getHandle(), k)) return;

        float sc = sc();
        int pw = panelW(), ph = panelH();
        lastW = pw; lastH = ph;
        int x0 = effX(), y0 = effY();

        if (bg.boolValue) HudGlass.glassBox(ctx, x0, y0, x0 + pw, y0 + ph, 0.9f);

        ctx.getMatrices().push();
        ctx.getMatrices().translate(x0, y0, 0f);
        ctx.getMatrices().scale(sc, sc, 1f);
        try {
            // Prime the GUI item state after the raw-GL glass, exactly as the glass hotbar does, or the
            // first item drawn straight after the glass renders black (corrupted shader/lighting state).
            RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
            DiffuseLighting.enableGuiDepthLighting();
            RenderSystem.setShaderColor(0f, 0f, 0f, 0f);   // cache-defeat -> force white so no item tints black
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            PlayerEntity p = mc.player;
            for (int i = 0; i < COLS * ROWS; i++) {
                ItemStack st = p.getInventory().main.get(9 + i);   // 0-8 hotbar, 9-35 the three main rows
                if (st == null || st.isEmpty()) continue;
                int ix = PAD + (i % COLS) * SLOT + 1, iy = PAD + (i / COLS) * SLOT + 1;
                ctx.drawItem(p, st, ix, iy, 0);
                ctx.drawItemInSlot(mc.textRenderer, st, ix, iy);
            }
            ctx.draw();
            DiffuseLighting.disableGuiDepthLighting();
        } finally {
            ctx.getMatrices().pop();
        }
    }

    // ---- HudBounds (draggable box in the HUD editor; auto-centres until placed) ----
    public int hudX() { return effX(); }
    public int hudY() { return effY(); }
    public void hudSetPos(int x, int y) { posX.setInt(Math.max(0, x)); posY.setInt(Math.max(0, y)); }
    public int hudW() { lastW = panelW(); return lastW; }
    public int hudH() { lastH = panelH(); return lastH; }
    public void hudResetPos() { posX.setInt(-1); posY.setInt(-1); }   // back to auto-centre
    public String hudLabel() { return name; }
}
