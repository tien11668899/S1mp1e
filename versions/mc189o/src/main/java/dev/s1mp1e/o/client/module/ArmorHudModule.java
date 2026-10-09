package dev.s1mp1e.o.client.module;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.s1mp1e.o.client.HudBounds;
import dev.s1mp1e.o.client.HudRenderer;
import dev.s1mp1e.o.client.Module;
import dev.s1mp1e.o.client.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.platform.Lighting;
import net.minecraft.client.render.entity.ItemRenderer;
import net.minecraft.item.Item;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;

/**
 * Worn armour as icons, each hugged by a colour OUTLINE that follows the item's REAL
 * silhouette — the icon sprite's alpha mask is read (via {@link Silhouette}) and the 1px-outside
 * contour is traced, so the outline clings to the item's actual shape (like a text outline follows
 * the glyph). The trace is ordered CLOCKWISE from the top and covers the remaining-durability
 * fraction, so it disappears clockwise as durability drops; the item's material colour flows a
 * bright ripple along it. No held item, no number, no green bar — this is the mc1211 redesign.
 *
 * <p>Fair play: reads {@code mc.player.inventory.armor} only. The silhouette is cached
 * per item type; any read failure degrades to just the icon (never crashes).
 *
 * <p>1.8.9 slot order: {@code PlayerInventory.armor} is indexed 0=boots .. 3=helmet, so a
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
        // visibility (incl. the fade-out after switching off) is decided by HudRenderDispatcher via HudFade; no enabled-guard
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.world == null) return;
        if (mc.options.hideGui && mc.screen == null) return;

        ItemStack[] armour = mc.player.inventory.armor;
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
            GlStateManager.translatef((float) posX.intValue, (float) posY.intValue, 0f);
            GlStateManager.scalef(s, s, 1f);

            // Outlines first (immediate-mode fills, no item lighting), icons on top. The outline
            // sits 1px OUTSIDE the shape, so the icon never covers it.
            for (int i = 0; i < n; i++) {
                ItemStack st = rows.get(i);
                int ix = CELL / 2 - 8, iy = i * CELL + CELL / 2 - 8;
                Silhouette.draw(contour(st), ix, iy, durabilityRatio(st), materialColor(st), time);
            }
            // GuiElement.fill left the colour register on the last ripple hue and blend disabled; reset
            // the colour cache (see GlassRenderer.endBatch) before the item pass multiplies against it.
            GlStateManager.color4f(0f, 0f, 0f, 0f);
            GlStateManager.color4f(1f, 1f, 1f, 1f);

            ItemRenderer ri = mc.getItemRenderer();
            Lighting.turnOnGui();
            GlStateManager.enableRescaleNormal();
            for (int i = 0; i < n; i++) {
                ItemStack st = rows.get(i);
                int ix = CELL / 2 - 8, iy = i * CELL + CELL / 2 - 8;
                ri.renderGuiItem(st, ix, iy);
            }
            GlStateManager.disableRescaleNormal();
            Lighting.turnOff();

            // Restore state for whatever draws after us this frame (including the glass pipeline).
            // ItemRenderer leaves GL_ALPHA_TEST off and the colour register non-white while
            // GlStateManager's cache still reads white, so a bare color(1,1,1,1) no-ops — force the
            // cache. NEVER disableBlend on exit (memory rule): leave blend enabled.
            GlStateManager.color4f(0f, 0f, 0f, 0f);
            GlStateManager.color4f(1f, 1f, 1f, 1f);
            GlStateManager.enableAlphaTest();
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
            Minecraft mc = Minecraft.getInstance();
            out = Silhouette.trace(mc.getItemRenderer().getModelShaper().getModel(st).getParticleIcon());
        } catch (Throwable t) {
            out = new int[0];
        }
        if (out.length > 0) CACHE.put(item, out);
        return out;
    }

    private static int materialColor(ItemStack st) {
        Item item = st.getItem();
        if (item instanceof ArmorItem) {
            ArmorItem armor = (ArmorItem) item;
            if (armor.hasColor(st)) return armor.getColor(st) & 0xFFFFFF;   // dyed leather
        }
        String id;
        try {
            Object name = Item.REGISTRY.getKey(item);
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
        if (st.isDamageable() && st.getMaxDamage() > 0) {
            float left = (float) (st.getMaxDamage() - st.getDamage()) / (float) st.getMaxDamage();
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
