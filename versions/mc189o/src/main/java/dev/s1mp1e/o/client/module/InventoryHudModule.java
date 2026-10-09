package dev.s1mp1e.o.client.module;

import org.lwjgl.input.Keyboard;

import dev.s1mp1e.o.client.HudBounds;
import dev.s1mp1e.o.client.HudRenderer;
import dev.s1mp1e.o.client.KeyCodes;
import dev.s1mp1e.o.client.Module;
import dev.s1mp1e.o.client.Setting;
import dev.s1mp1e.o.client.hud.HudFade;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.Window;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.platform.Lighting;
import net.minecraft.client.render.entity.ItemRenderer;
import net.minecraft.entity.living.player.PlayerEntity;
import net.minecraft.item.ItemStack;

/**
 * Hold a key (default Tab) to PEEK your own main inventory without opening the inventory
 * screen: the 27 main slots (the three rows above the hotbar) as item icons on a
 * liquid-glass panel.
 *
 * <p><b>Cross-version key.</b> The setting keeps the GLFW namespace ("Key (GLFW)", default
 * {@code 258} = Tab) so a config file is portable across the whole S1mp1e line; on 1.8.9
 * (LWJGL2) the stored GLFW code is translated each frame through
 * {@link KeyCodes#glfwToLwjgl(int)} before {@link Keyboard#isKeyDown(int)}.
 *
 * <p><b>Position.</b> A {@link HudBounds} module (draggable box in the HUD editor).
 * {@code X}/{@code Y} default to {@code -1} = AUTO (horizontally centred, just above the
 * hotbar); dragging in the editor makes them absolute, and "reset" returns them to auto.
 *
 * <p><b>Fair play.</b> Reads {@code mc.player.inventory.items} — your own items,
 * information you already have. OBSERVE-ONLY: it never moves, swaps, uses, or drops anything.
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

    /** Identity key for the peek (key held) fade in {@link HudFade}. */
    private static final Object PEEK = new Object();

    public InventoryHudModule() { super("InventoryHUD", "HUD"); this.enabled = false; }

    private float sc()   { return (float) scale.doubleValue; }
    private int panelW() { return Math.round((COLS * SLOT + PAD * 2) * sc()); }
    private int panelH() { return Math.round((ROWS * SLOT + PAD * 2) * sc()); }

    /** Effective top-left X: the setting if placed (>=0), else horizontally centred. */
    private int effX() {
        if (posX.intValue >= 0) return posX.intValue;
        return (new Window(Minecraft.getInstance()).getWidth() - panelW()) / 2;
    }
    /** Effective top-left Y: the setting if placed (>=0), else floated just above the hotbar. */
    private int effY() {
        if (posY.intValue >= 0) return posY.intValue;
        return new Window(Minecraft.getInstance()).getHeight() - 45 - panelH();
    }

    @Override
    public void renderHud() {
        // module visibility (incl. the fade-out after switching off) is decided by HudRenderDispatcher via HudFade; no enabled-guard
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.world == null) return;
        if (mc.options.hideGui && mc.screen == null) return;
        if (mc.screen != null) return;   // real inventory / a screen is open -> don't double up

        int lwjgl = KeyCodes.glfwToLwjgl(key.intValue);
        // Peek while the key is held. Holding / releasing fades + grows the panel in and out instead of popping it;
        // item icons can't take an alpha, so they ride the scale (ported from mc189/mc1122).
        boolean held = lwjgl > 0 && Keyboard.isKeyDown(lwjgl);
        float kv = HudFade.visibility(PEEK, held);
        if (kv <= 0.004f) return;

        float sc = sc();
        int pw = panelW(), ph = panelH();
        lastW = pw; lastH = ph;
        int x0 = effX(), y0 = effY();

        float saved = HudFade.alpha;
        HudFade.alpha = saved * kv;                  // fades the glass panel (glassBox multiplies HudFade.alpha)
        // peek centre-scale 0.85 -> 1 (ease-out), about the panel centre in screen px. The glass tile (glassBox) and the
        // items both ride the GL model-view, so folding the centre-scale into the GL model-view grows BOTH as one block.
        float ps = kv < 1f ? HudFade.SCALE_FROM + (1f - HudFade.SCALE_FROM) * HudFade.easeOut(kv) : 1f;
        float cx = x0 + pw * 0.5f, cy = y0 + ph * 0.5f;
        GlStateManager.pushMatrix();
        try {
        if (ps != 1f) {
            GlStateManager.translatef(cx, cy, 0f);
            GlStateManager.scalef(ps, ps, 1f);
            GlStateManager.translatef(-cx, -cy, 0f);
        }
        if (bg.boolValue) HudGlass.glassBox(x0, y0, x0 + pw, y0 + ph, 0.9f);

        GlStateManager.pushMatrix();
        GlStateManager.translatef((float) x0, (float) y0, 0f);
        GlStateManager.scalef(sc, sc, 1f);
        try {
            // HudGlass.glassBox left GL in the fixed-function/white-cache state (GlassRenderer
            // .endBatch resets the colour cache and unbinds the shader), but prime item
            // lighting and defeat the colour cache once more before the first item, exactly
            // as ArmorHUD does, or the first icon can render black.
            GlStateManager.color4f(0f, 0f, 0f, 0f);
            GlStateManager.color4f(1f, 1f, 1f, 1f);
            ItemRenderer ri = mc.getItemRenderer();
            Lighting.turnOnGui();
            GlStateManager.enableRescaleNormal();
            try {
                PlayerEntity p = mc.player;
                for (int i = 0; i < COLS * ROWS; i++) {
                    ItemStack st = p.inventory.items[9 + i];   // 0-8 hotbar, 9-35 the three main rows
                    if (st == null) continue;
                    int ix = PAD + (i % COLS) * SLOT + 1, iy = PAD + (i / COLS) * SLOT + 1;
                    ri.renderGuiItem(st, ix, iy);
                    ri.renderGuiItemDecorations(mc.textRenderer, st, ix, iy, null);
                }
            } finally {
                // a throwing item renderer must not leave item lighting on for the rest of the HUD
                GlStateManager.disableRescaleNormal();
                Lighting.turnOff();
            }
            // Restore for whatever draws after us (including the glass pipeline). Force the
            // colour cache white; NEVER disableBlend on exit (memory rule).
            GlStateManager.color4f(0f, 0f, 0f, 0f);
            GlStateManager.color4f(1f, 1f, 1f, 1f);
            GlStateManager.enableAlphaTest();
            GlStateManager.enableBlend();
        } finally {
            GlStateManager.popMatrix();
        }
        } finally {
            GlStateManager.popMatrix();
            HudFade.alpha = saved;
        }
    }

    // ---- HudBounds (draggable box; auto-centres until placed) ----
    public int hudX() { return effX(); }
    public int hudY() { return effY(); }
    public void hudSetPos(int x, int y) { posX.setInt(Math.max(0, x)); posY.setInt(Math.max(0, y)); }
    public int hudW() { lastW = panelW(); return lastW; }
    public int hudH() { lastH = panelH(); return lastH; }
    public void hudResetPos() { posX.setInt(-1); posY.setInt(-1); }   // back to auto-centre
    public String hudLabel() { return name; }
}
