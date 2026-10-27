package dev.s1mp1e.o.client.module;

import dev.s1mp1e.o.client.HudBounds;
import dev.s1mp1e.o.client.HudRenderer;
import dev.s1mp1e.o.client.Module;
import dev.s1mp1e.o.client.Setting;
import dev.s1mp1e.o.client.gui.GlassFont;
import dev.s1mp1e.o.client.hud.HudText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.Window;
import net.minecraft.client.render.entity.ItemRenderer;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.platform.Lighting;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/**
 * How many arrows, golden apples, ender pearls and blocks the player carries, as icon + count rows in one glass pill
 * (Bedwars / Skywars: no need to open the inventory mid-fight). Reads only the player's own 36 inventory slots.
 *
 * <p>Drawn like {@link ArmorHudModule} (immediate-mode item icons under a GlStateManager matrix) with the
 * {@link FpsHudModule} pill. The default X lies past any screen width and is clamped to the right edge, so the pill
 * starts just under the minimap.
 */
public final class ItemCountHudModule extends Module implements HudBounds, HudRenderer {

    private static final int PAD = 3, ROW = 18, ICON = 16, GAP = 4;

    public final Setting arrows    = add(Setting.bool("Arrows", true));
    public final Setting gapples   = add(Setting.bool("Golden apples", true));
    public final Setting pearls    = add(Setting.bool("Ender pearls", true));
    public final Setting blocks    = add(Setting.bool("Blocks", true));
    public final Setting hideEmpty = add(Setting.bool("Hide empty", true));
    public final Setting bg        = add(Setting.bool("Background", true));
    public final Setting scale     = add(Setting.number("Scale", 1.0D, 0.5D, 2.0D));
    public final Setting posX      = add(Setting.integer("X", 4000, 0, 4000));
    public final Setting posY      = add(Setting.integer("Y", 108, 0, 4000));

    private ItemStack arrowIcon, gappleIcon, pearlIcon, woolIcon;
    // per-frame rows (render thread only)
    private final ItemStack[] rowIcon = new ItemStack[4];
    private final int[] rowCount = new int[4];
    private int lastW = 40, lastH = 22, lastX, lastY;

    public ItemCountHudModule() {
        super("ItemCount", "HUD");
        this.enabled = true;
    }

    @Override
    public void renderHud() {
        // visibility (incl. the fade-out after switching off) is decided by HudRenderDispatcher via HudFade
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.world == null) return;
        if (mc.options.hideGui && mc.screen == null) return;
        if (arrowIcon == null) {
            arrowIcon = new ItemStack(Items.ARROW);
            gappleIcon = new ItemStack(Items.GOLDEN_APPLE);
            pearlIcon = new ItemStack(Items.ENDER_PEARL);
            woolIcon = new ItemStack(net.minecraft.block.Blocks.WOOL);
        }

        int nArrow = 0, nGapple = 0, nPearl = 0, nBlock = 0;
        ItemStack blockIcon = null;
        int blockIconSize = 0;
        ItemStack[] inv = mc.player.inventory.items;
        for (int i = 0; i < inv.length; i++) {
            ItemStack st = inv[i];
            if (st == null) continue;
            Item it = st.getItem();
            if (it == Items.ARROW) nArrow += st.size;
            else if (it == Items.GOLDEN_APPLE) nGapple += st.size;
            else if (it == Items.ENDER_PEARL) nPearl += st.size;
            else if (it instanceof BlockItem) {
                nBlock += st.size;
                if (st.size > blockIconSize) { blockIcon = st; blockIconSize = st.size; }   // the biggest stack shows
            }
        }

        int n = 0;
        boolean hide = hideEmpty.boolValue;
        if (arrows.boolValue && (nArrow > 0 || !hide)) { rowIcon[n] = arrowIcon; rowCount[n++] = nArrow; }
        if (gapples.boolValue && (nGapple > 0 || !hide)) { rowIcon[n] = gappleIcon; rowCount[n++] = nGapple; }
        if (pearls.boolValue && (nPearl > 0 || !hide)) { rowIcon[n] = pearlIcon; rowCount[n++] = nPearl; }
        if (blocks.boolValue && (nBlock > 0 || !hide)) {
            rowIcon[n] = blockIcon != null ? blockIcon : woolIcon;
            rowCount[n++] = nBlock;
        }
        if (n == 0) return;

        int textW = 0;
        for (int i = 0; i < n; i++) textW = Math.max(textW, Math.round(GlassFont.width(Integer.toString(rowCount[i]))));
        int boxW = PAD + ICON + GAP + textW + PAD + 1;
        int boxH = PAD * 2 + (n - 1) * ROW + ICON;
        float sc = (float) scale.doubleValue;
        lastW = Math.round(boxW * sc);
        lastH = Math.round(boxH * sc);
        Window win = new Window(mc);
        int x0 = Math.max(0, Math.min(posX.intValue, win.getWidth() - lastW));
        int y0 = Math.max(0, Math.min(posY.intValue, win.getHeight() - lastH));
        lastX = x0;
        lastY = y0;
        if (bg.boolValue) HudGlass.glassBox(x0, y0, x0 + lastW, y0 + lastH, 0.85f);

        GlStateManager.pushMatrix();
        try {
            GlStateManager.translatef((float) x0, (float) y0, 0f);
            GlStateManager.scalef(sc, sc, 1f);

            ItemRenderer ri = mc.getItemRenderer();
            Lighting.turnOnGui();
            GlStateManager.enableRescaleNormal();
            for (int i = 0; i < n; i++) ri.renderGuiItem(rowIcon[i], PAD, PAD + i * ROW);
            GlStateManager.disableRescaleNormal();
            Lighting.turnOff();
            // ItemRenderer leaves alpha test off and the colour register non-white behind GlStateManager's cache:
            // force the cache (see GlassRenderer.endBatch), keep blend on (ArmorHudModule does the same)
            GlStateManager.color4f(0f, 0f, 0f, 0f);
            GlStateManager.color4f(1f, 1f, 1f, 1f);
            GlStateManager.enableAlphaTest();
            GlStateManager.enableBlend();

            float ty = (ICON - GlassFont.height()) / 2f;
            for (int i = 0; i < n; i++) {
                int c = rowCount[i];
                HudText.draw(Integer.toString(c), PAD + ICON + GAP, PAD + i * ROW + ty, c > 0 ? 0xFFFFFFFF : 0x80FFFFFF, true);
                rowIcon[i] = null;
            }
        } finally {
            GlStateManager.popMatrix();
        }
    }

    // ---- HudBounds (the HUD editor drags the clamped, on-screen position) ----
    public int hudX() { return lastX; }
    public int hudY() { return lastY; }
    public void hudSetPos(int x, int y) { posX.setInt(x); posY.setInt(y); }
    public int hudW() { return lastW > 0 ? lastW : 40; }
    public int hudH() { return lastH > 0 ? lastH : 22; }
    public void hudResetPos() { posX.reset(); posY.reset(); }
    public String hudLabel() { return name; }
}
