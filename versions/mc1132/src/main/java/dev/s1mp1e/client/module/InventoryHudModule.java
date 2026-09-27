package dev.s1mp1e.client.module;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.glass.compat.Mc1132;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.collection.DefaultedList;

/**
 * Hold a key (default Tab) to PEEK your own main inventory without opening the inventory screen: the 27
 * main slots (the three rows above the hotbar) as item icons on a liquid-glass panel.
 *
 * <p><b>Position.</b> It is a {@link HudBounds} module, so it shows as a draggable box in the HUD editor.
 * {@code X}/{@code Y} default to {@code -1} = AUTO (horizontally centred, just above the hotbar); the
 * moment you drag it in the editor they become an absolute position, and "reset" returns it to auto.
 *
 * <p><b>Fair play.</b> Reads {@code mc.player.getInventory().main} — your own items, information you already
 * have. OBSERVE-ONLY: it never moves, swaps, uses, or drops anything.
 *
 * <p>1.13.2 port of mc1144's {@code InventoryHudModule} (mc1211 layout: SLOT 18, 9x3, PAD 4, X/Y -1 =
 * auto-centre). The scaled-window auto-centre uses the {@link Mc1132} bridge ({@code method_18321/18322})
 * instead of {@code mc.window}; the peek key is polled as a native GLFW code through
 * {@link Mc1132#keyDown(int)}. The panel is a {@link HudGlass#glassBox}; the items are drawn in
 * immediate-mode GL with the legacy-yarn GUI item renderer ({@code HeldItemRenderer} misnomer):
 * {@code method_19376} renderGuiItem + {@code method_19383} renderGuiItemOverlay, reading the public
 * {@code PlayerInventory.field_15082} (main) indices 9..35, dispatched by {@code HudDispatch} from the
 * {@code InGameHud.render} TAIL.
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
        return (Mc1132.scaledW() - panelW()) / 2;
    }
    /** Effective top-left Y: the setting if placed (>=0), else floated just above the hotbar. */
    private int effY() {
        if (posY.intValue >= 0) return posY.intValue;
        return Mc1132.scaledH() - 45 - panelH();
    }

    @Override
    public void renderHud() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || Mc1132.hudHidden()) return;
        if (mc.currentScreen != null) return;   // real inventory / a screen is open -> don't double up
        int k = key.intValue;
        if (k <= 0 || !Mc1132.keyDown(k)) return;   // GLFW code, polled natively
        HeldItemRenderer ir = Mc1132.itemRenderer();
        if (ir == null) return;

        float sc = sc();
        int pw = panelW(), ph = panelH();
        lastW = pw; lastH = ph;
        int x0 = effX(), y0 = effY();

        if (bg.boolValue) HudGlass.glassBox(x0, y0, x0 + pw, y0 + ph, 0.9f);

        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate((float) x0, (float) y0, 0f);
            GlStateManager.scale(sc, sc, 1f);
            // Prime item lighting after the raw-GL glass and defeat the colour cache (hard rule 5) before
            // the first item, exactly as ArmorHUD does, or the first icon can render black.
            GlassWidgets.resetColorCache();
            PlayerEntity p = mc.player;
            DefaultedList<ItemStack> main = p.inventory.field_15082;
            GlStateManager.enableRescaleNormal();
            DiffuseLighting.enable();
            try {
                for (int i = 0; i < COLS * ROWS; i++) {
                    int slot = 9 + i;   // 0-8 hotbar, 9-35 the three main rows
                    if (slot >= main.size()) break;
                    ItemStack st = main.get(slot);
                    if (st == null || st.isEmpty()) continue;
                    int ix = PAD + (i % COLS) * SLOT + 1, iy = PAD + (i / COLS) * SLOT + 1;
                    ir.method_19376(st, ix, iy);                       // renderGuiItem
                    ir.method_19383(mc.textRenderer, st, ix, iy);      // renderGuiItemOverlay
                }
            } finally {
                DiffuseLighting.disable();
                GlStateManager.disableRescaleNormal();
            }
            // Restore for whatever draws after us (including the glass pipeline). Force the colour cache
            // white and re-enable alpha; NEVER disableBlend on exit (hard rule 5).
            GlassWidgets.resetColorCache();
            GlStateManager.enableAlphaTest();
            GlStateManager.enableBlend();
        } finally {
            GlStateManager.popMatrix();
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
