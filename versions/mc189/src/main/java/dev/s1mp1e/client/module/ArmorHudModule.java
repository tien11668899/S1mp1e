package dev.s1mp1e.client.module;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;

/**
 * Worn armour as icons, each hugged by a colour OUTLINE that follows the item's REAL
 * silhouette — the icon sprite's alpha mask is read (via {@link Silhouette}) and the 1px-outside
 * contour is traced, so the outline clings to the item's actual shape (like a text outline follows
 * the glyph). The trace is ordered CLOCKWISE from the top and covers the remaining-durability
 * fraction, so it disappears clockwise as durability drops; the item's material colour flows a
 * bright ripple along it. No held item, no number, no green bar — this is the mc1211 redesign.
 *
 * <p>Fair play: reads {@code mc.thePlayer.inventory.armorInventory} only. The silhouette is cached
 * per item type; any read failure degrades to just the icon (never crashes).
 *
 * <p>1.8.9 slot order: {@code InventoryPlayer.armorInventory} is indexed 0=boots .. 3=helmet, so a
 * head-to-toe list walks the array backwards.
 */
public final class ArmorHudModule extends Module implements HudBounds, HudRenderer {

    private static final int CELL = 20;      // per-piece cell (icon 16 + 1px outline + margin)
    /** contour points (x,y pairs) per item type; empty = read failed (retried next frame). */
    private static final Map<Item, int[]> CACHE = new HashMap<Item, int[]>();

    public final Setting posX  = add(Setting.integer("X", 4, 0, 4000));
    public final Setting posY  = add(Setting.integer("Y", 60, 0, 4000));
    public final Setting scale = add(Setting.number("Scale", 1.0D, 0.5D, 2.0D));
    private int lastW = CELL, lastH = CELL;

    public ArmorHudModule() {
        super("ArmorHUD", "HUD");
        // Purely additive readout of your own data -- on by default so a fresh install shows
        // something. The render half is driven centrally by HudRenderDispatcher, so this module
        // does NOT subscribe to any Forge event.
        this.enabled = true;
    }

    @Override
    public void renderHud() {
        if (!enabled) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (mc.gameSettings.hideGUI && mc.currentScreen == null) return;

        ItemStack[] armour = mc.thePlayer.inventory.armorInventory;
        List<ItemStack> rows = new ArrayList<ItemStack>(4);
        for (int i = armour.length - 1; i >= 0; i--) {
            if (armour[i] != null) rows.add(armour[i]);
        }
        int n = rows.size();
        if (n == 0) return;

        float s = (float) scale.doubleValue;
        lastW = Math.round(CELL * s);
        lastH = Math.round(n * CELL * s);
        float time = (System.nanoTime() % 3_000_000_000L) / 3.0e9f;

        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate((float) posX.intValue, (float) posY.intValue, 0f);
            GlStateManager.scale(s, s, 1f);

            // Outlines first (immediate-mode fills, no item lighting), icons on top. The outline
            // sits 1px OUTSIDE the shape, so the icon never covers it.
            for (int i = 0; i < n; i++) {
                ItemStack st = rows.get(i);
                int ix = CELL / 2 - 8, iy = i * CELL + CELL / 2 - 8;
                Silhouette.draw(contour(st), ix, iy, durabilityRatio(st), materialColor(st), time);
            }
            // Gui.drawRect left the colour register on the last ripple hue and blend disabled; reset
            // the colour cache (see GlassRenderer.endBatch) before the item pass multiplies against it.
            GlStateManager.color(0f, 0f, 0f, 0f);
            GlStateManager.color(1f, 1f, 1f, 1f);

            RenderItem ri = mc.getRenderItem();
            RenderHelper.enableGUIStandardItemLighting();
            GlStateManager.enableRescaleNormal();
            for (int i = 0; i < n; i++) {
                ItemStack st = rows.get(i);
                int ix = CELL / 2 - 8, iy = i * CELL + CELL / 2 - 8;
                ri.renderItemAndEffectIntoGUI(st, ix, iy);
            }
            GlStateManager.disableRescaleNormal();
            RenderHelper.disableStandardItemLighting();

            // Restore state for whatever draws after us this frame (including the glass pipeline).
            // RenderItem leaves GL_ALPHA_TEST off and the colour register non-white while
            // GlStateManager's cache still reads white, so a bare color(1,1,1,1) no-ops — force the
            // cache. NEVER disableBlend on exit (memory rule): leave blend enabled.
            GlStateManager.color(0f, 0f, 0f, 0f);
            GlStateManager.color(1f, 1f, 1f, 1f);
            GlStateManager.enableAlpha();
            GlStateManager.enableBlend();
        } finally {
            GlStateManager.popMatrix();
        }
    }

    /** The 1px-outside contour of the item icon's silhouette (traced by {@link Silhouette}), cached per
     *  Item type. Only successful reads are cached, so a transient atlas miss retries next frame. */
    private static int[] contour(ItemStack st) {
        Item item = st.getItem();
        int[] cached = CACHE.get(item);
        if (cached != null) return cached;
        int[] out;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            out = Silhouette.trace(mc.getRenderItem().getItemModelMesher().getItemModel(st).getParticleTexture());
        } catch (Throwable t) {
            out = new int[0];
        }
        if (out.length > 0) CACHE.put(item, out);
        return out;
    }

    private static int materialColor(ItemStack st) {
        Item item = st.getItem();
        if (item instanceof ItemArmor) {
            ItemArmor armor = (ItemArmor) item;
            if (armor.hasColor(st)) return armor.getColor(st) & 0xFFFFFF;   // dyed leather
        }
        String id;
        try {
            Object name = Item.itemRegistry.getNameForObject(item);
            id = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            id = "";
        }
        if (id.contains("netherite")) return 0x6A5E5A;
        if (id.contains("diamond"))   return 0x4AEDD9;
        if (id.contains("golden") || id.contains("gold")) return 0xFAEE4D;
        if (id.contains("iron"))      return 0xD8D8D8;
        if (id.contains("chainmail")) return 0x9A9A9A;
        if (id.contains("turtle"))    return 0x2FA149;
        if (id.contains("leather"))   return 0xA06540;
        return 0xC0C0C8;
    }

    private static float durabilityRatio(ItemStack st) {
        if (st.isItemStackDamageable() && st.getMaxDamage() > 0) {
            float left = (float) (st.getMaxDamage() - st.getItemDamage()) / (float) st.getMaxDamage();
            return Math.max(0f, Math.min(1f, left));
        }
        return 1f;
    }

    // ---- HudBounds ----
    public int hudX() { return posX.intValue; }
    public int hudY() { return posY.intValue; }
    public void hudSetPos(int x, int y) { posX.setInt(x); posY.setInt(y); }
    public int hudW() { return lastW > 0 ? lastW : CELL; }
    public int hudH() { return lastH > 0 ? lastH : CELL; }
    public void hudResetPos() { posX.reset(); posY.reset(); }
    public String hudLabel() { return name; }
}
