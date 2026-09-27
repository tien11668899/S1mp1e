package dev.s1mp1e.client.module;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.item.DyeableArmorItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.registry.Registry;

/**
 * Worn armour as icons, each hugged by a colour OUTLINE that follows the item's REAL silhouette — the
 * icon sprite's alpha mask is read (via {@link Silhouette}) and the 1px-outside contour is traced, so the
 * outline clings to the item's actual shape (like a text outline follows the glyph). The trace is ordered
 * CLOCKWISE from the top and covers the remaining-durability fraction, so it disappears clockwise as
 * durability drops; the item's material colour flows a bright ripple along it. No held item, no number,
 * no green bar.
 *
 * <p>Fair play: reads {@code mc.player.inventory} armour only. The silhouette is cached per item type;
 * any read failure degrades to just the icon (never crashes).
 *
 * <p>1.13.2 port of mc1144's {@code ArmorHudModule} (mc1211 layout, {@code CELL} 20), with the legacy-yarn
 * names (all javap-verified against 1.13.2+build.604-v2):
 * <ul>
 *   <li>armour list = {@code PlayerInventory.field_15083} (public {@code DefaultedList}; 0=boots ..
 *       3=helmet, so a head-to-toe list walks it backwards);</li>
 *   <li>the GUI item renderer is {@code HeldItemRenderer} (legacy MISNOMER, {@link Mc1132#itemRenderer()});
 *       {@code method_19379} = getModel with overrides, {@code method_19376} = renderGuiItem;</li>
 *   <li>{@code BakedModel.getParticleSprite()} is the icon sprite;</li>
 *   <li>dyed leather: {@code DyeableArmorItem.method_16049} (hasColor) / {@code method_16050} (getColor) —
 *       1.13.2 has no {@code DyeableItem} interface;</li>
 *   <li>{@code DiffuseLighting.enable()} is the GUI-item lighting (vanilla renderHotbar's bracket).</li>
 * </ul>
 * mc1211's material-colour rules match on the item's registry id; 1.13.2's {@code Item} has no
 * {@code toString()} override, so the id comes from {@code Registry.ITEM.getId(item)} (netherite entry
 * inert on 1.13.2, kept for parity).
 */
public final class ArmorHudModule extends Module implements HudBounds, HudRenderer {

    private static final int CELL = 20;      // per-piece cell (icon 16 + 1px outline + margin)
    /** contour points (x,y pairs) per item type; empty = read failed (retried next frame). */
    private static final Map<Item, int[]> CACHE = new HashMap<Item, int[]>();

    public final Setting posX  = add(Setting.integer("X", 4, 0, 4000));
    public final Setting posY  = add(Setting.integer("Y", 60, 0, 4000));
    public final Setting scale = add(Setting.number("Scale", 1.0D, 0.5D, 2.0D));
    public int lastW = CELL, lastH = CELL;

    public ArmorHudModule() { super("ArmorHUD", "HUD"); this.enabled = true; }

    @Override
    public void renderHud() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || Mc1132.hudHidden()) return;
        HeldItemRenderer ir = Mc1132.itemRenderer();
        if (ir == null) return;

        DefaultedList<ItemStack> armour = mc.player.inventory.field_15083;
        List<ItemStack> rows = new ArrayList<ItemStack>(4);
        for (int i = armour.size() - 1; i >= 0; i--) {
            ItemStack st = armour.get(i);
            if (st != null && !st.isEmpty()) rows.add(st);
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

            // Outlines first (immediate-mode fills, no item lighting), icons on top. The outline sits 1px
            // OUTSIDE the shape, so the icon never covers it.
            for (int i = 0; i < n; i++) {
                ItemStack st = rows.get(i);
                int ix = CELL / 2 - 8, iy = i * CELL + CELL / 2 - 8;
                Silhouette.draw(contour(st), ix, iy, durabilityRatio(st), materialColor(st), time);
            }
            // The silhouette fills left the colour register on the last ripple hue; reset the cache (hard
            // rule 5) before the item pass multiplies against it.
            GlassWidgets.resetColorCache();

            GlStateManager.enableRescaleNormal();
            DiffuseLighting.enable();
            try {
                for (int i = 0; i < n; i++) {
                    ItemStack st = rows.get(i);
                    int ix = CELL / 2 - 8, iy = i * CELL + CELL / 2 - 8;
                    ir.method_19376(st, ix, iy);   // renderGuiItem
                }
            } finally {
                DiffuseLighting.disable();
                GlStateManager.disableRescaleNormal();
            }

            // Restore state for whatever draws after us this frame (including the glass pipeline). Force the
            // colour cache white and re-enable alpha test; NEVER disableBlend on exit (hard rule 5).
            GlassWidgets.resetColorCache();
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
            MinecraftClient mc = MinecraftClient.getInstance();
            HeldItemRenderer ir = Mc1132.itemRenderer();
            BakedModel model = ir == null ? null : ir.method_19379(st, mc.world, mc.player);   // getModel
            out = model == null ? new int[0] : Silhouette.trace(model.getParticleSprite());
        } catch (Throwable t) {
            out = new int[0];
        }
        if (out.length > 0) CACHE.put(item, out);
        return out;
    }

    private static int materialColor(ItemStack st) {
        Item item = st.getItem();
        if (item instanceof DyeableArmorItem) {
            DyeableArmorItem dye = (DyeableArmorItem) item;
            if (dye.method_16049(st)) return dye.method_16050(st) & 0xFFFFFF;   // hasColor -> getColor (dyed leather)
        }
        String id = itemId(item);
        if (id.contains("netherite")) return 0x6A5E5A;   // inert on 1.13.2 (no netherite), kept for parity
        if (id.contains("diamond"))   return 0x4AEDD9;
        if (id.contains("golden") || id.contains("gold")) return 0xFAEE4D;
        if (id.contains("iron"))      return 0xD8D8D8;
        if (id.contains("chainmail")) return 0x9A9A9A;
        if (id.contains("turtle"))    return 0x2FA149;
        if (id.contains("leather"))   return 0xA06540;
        return 0xC0C0C8;
    }

    /** The item's registry id (e.g. {@code minecraft:diamond_helmet}), lower-cased — the string mc1211's
     *  {@code Item.toString()} yields. Falls back to the translation key, then to "". */
    private static String itemId(Item item) {
        try {
            Identifier id = Registry.ITEM.getId(item);
            if (id != null) return id.toString().toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
            // fall through to the translation key
        }
        try {
            String key = item.getTranslationKey();
            return key == null ? "" : key.toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return "";
        }
    }

    private static float durabilityRatio(ItemStack st) {
        if (st.isDamageable() && st.getMaxDamage() > 0) {
            return MathHelper.clamp((float) (st.getMaxDamage() - st.getDamage()) / (float) st.getMaxDamage(), 0f, 1f);
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
