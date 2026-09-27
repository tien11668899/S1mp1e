package dev.s1mp1e.client.module;

import org.lwjgl.input.Keyboard;

import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.KeyCodes;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

/**
 * Hold a key (default Tab) to PEEK your own main inventory without opening the inventory
 * screen: the 27 main slots (the three rows above the hotbar) as item icons on a
 * liquid-glass panel.
 *
 * <p><b>Cross-version key.</b> The setting keeps the GLFW namespace ("Key (GLFW)", default
 * {@code 258} = Tab) so a config file is portable across the whole S1mp1e line; on 1.12.2
 * (LWJGL2) the stored GLFW code is translated each frame through
 * {@link KeyCodes#glfwToLwjgl(int)} before {@link Keyboard#isKeyDown(int)}.
 *
 * <p><b>Position.</b> A {@link HudBounds} module (draggable box in the HUD editor).
 * {@code X}/{@code Y} default to {@code -1} = AUTO (horizontally centred, just above the
 * hotbar); dragging in the editor makes them absolute, and "reset" returns them to auto.
 *
 * <p><b>Fair play.</b> Reads {@code mc.player.inventory.mainInventory} — your own items,
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

    public InventoryHudModule() { super("InventoryHUD", "HUD"); this.enabled = false; }

    private float sc()   { return (float) scale.doubleValue; }
    private int panelW() { return Math.round((COLS * SLOT + PAD * 2) * sc()); }
    private int panelH() { return Math.round((ROWS * SLOT + PAD * 2) * sc()); }

    /** Effective top-left X: the setting if placed (>=0), else horizontally centred. */
    private int effX() {
        if (posX.intValue >= 0) return posX.intValue;
        return (new ScaledResolution(Minecraft.getMinecraft()).getScaledWidth() - panelW()) / 2;
    }
    /** Effective top-left Y: the setting if placed (>=0), else floated just above the hotbar. */
    private int effY() {
        if (posY.intValue >= 0) return posY.intValue;
        return new ScaledResolution(Minecraft.getMinecraft()).getScaledHeight() - 45 - panelH();
    }

    @Override
    public void renderHud() {
        if (!enabled) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null) return;
        if (mc.gameSettings.hideGUI && mc.currentScreen == null) return;
        if (mc.currentScreen != null) return;   // real inventory / a screen is open -> don't double up

        int lwjgl = KeyCodes.glfwToLwjgl(key.intValue);
        if (lwjgl <= 0 || !Keyboard.isKeyDown(lwjgl)) return;

        float sc = sc();
        int pw = panelW(), ph = panelH();
        lastW = pw; lastH = ph;
        int x0 = effX(), y0 = effY();

        if (bg.boolValue) HudGlass.glassBox(x0, y0, x0 + pw, y0 + ph, 0.9f);

        GlStateManager.pushMatrix();
        GlStateManager.translate((float) x0, (float) y0, 0f);
        GlStateManager.scale(sc, sc, 1f);
        try {
            // HudGlass.glassBox left GL in the fixed-function/white-cache state (GlassRenderer
            // .endBatch resets the colour cache and unbinds the shader), but prime item
            // lighting and defeat the colour cache once more before the first item, exactly
            // as ArmorHUD does, or the first icon can render black.
            GlStateManager.color(0f, 0f, 0f, 0f);
            GlStateManager.color(1f, 1f, 1f, 1f);
            RenderItem ri = mc.getRenderItem();
            RenderHelper.enableGUIStandardItemLighting();
            GlStateManager.enableRescaleNormal();
            try {
                EntityPlayer p = mc.player;
                for (int i = 0; i < COLS * ROWS; i++) {
                    ItemStack st = p.inventory.mainInventory.get(9 + i);   // 0-8 hotbar, 9-35 the three main rows
                    if (st == null || st.isEmpty()) continue;
                    int ix = PAD + (i % COLS) * SLOT + 1, iy = PAD + (i / COLS) * SLOT + 1;
                    ri.renderItemAndEffectIntoGUI(st, ix, iy);
                    // count, durability bar and (1.12.2) the item-cooldown shade
                    ri.renderItemOverlayIntoGUI(mc.fontRenderer, st, ix, iy, null);
                }
            } finally {
                // a throwing item renderer must not leave item lighting on for the rest of the HUD
                GlStateManager.disableRescaleNormal();
                RenderHelper.disableStandardItemLighting();
            }
            // Restore for whatever draws after us (including the glass pipeline). Force the
            // colour cache white; NEVER switch blend off on exit (memory rule).
            GlStateManager.color(0f, 0f, 0f, 0f);
            GlStateManager.color(1f, 1f, 1f, 1f);
            GlStateManager.enableAlpha();
            GlStateManager.enableBlend();
        } finally {
            GlStateManager.popMatrix();
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
