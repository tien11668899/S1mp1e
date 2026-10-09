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
import dev.s1mp1e.glass.render.GuiFlush;
import dev.s1mp1e.glass.render.GuiItems;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.DyeableItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.MathHelper;

/**
 * Worn armour as icons, each hugged by a colour OUTLINE that follows the item's REAL
 * silhouette — the icon sprite's alpha mask is read (via {@link Silhouette}) and
 * the 1px-outside contour is traced, so the outline clings to the item's actual shape (like a
 * text outline follows the glyph). The trace is ordered CLOCKWISE from the top and covers the
 * remaining-durability fraction, so it disappears clockwise as durability drops; the item's
 * material colour flows a bright ripple along it. No held item, no number, no green bar.
 *
 * <p>Fair play: reads {@code mc.player.getInventory().armor} only. The silhouette is cached per
 * item type; any read failure degrades to just the icon (never crashes).
 *
 * <p><b>1.19.2 note.</b> No {@code DrawContext}: the silhouette fills take the passed {@link MatrixStack}
 * translate/scale, while the item MODEL goes through {@link GuiItems} (mirroring the same translate/scale
 * onto the RenderSystem model-view that GUI item rendering actually reads) with no count and no overlay bar.
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
    public void renderHud(MatrixStack matrices) {
        // visibility (incl. the fade-out after switching off) is decided by the HUD driver via HudFade
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.options.hudHidden) return;

        DefaultedList<ItemStack> armour = mc.player.getInventory().armor;
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

        // On 1.17.1 BOTH the silhouette outline (DrawableHelper.fill) and the item model
        // (GuiItems.drawModel via ItemRenderer) read the RenderSystem model-view, so the posX/posY
        // translate + scale must live ONLY there (via beginItemTransform). The silhouette is therefore
        // drawn with an IDENTITY MatrixStack so fill picks up that single model-view transform and lands
        // exactly behind its model. The old code also translated/scaled the passed matrices and fed THAT
        // to fill — fill then applied the transform twice (matrices AND model-view), so every outline
        // detached and drifted (posX, posY) below its icon (measured on 1.17.1: chestplate/boots outline
        // ghosts ~120 px under the armour at y~284 / y~364).
        GuiItems.beginItemTransform(posX.intValue, posY.intValue, s, s);
        MatrixStack idm = new MatrixStack();   // identity: fill uses ONLY the model-view transform above
        try {
            for (int i = 0; i < n; i++) {
                ItemStack st = rows.get(i);
                int ix = CELL / 2 - 8, iy = i * CELL + CELL / 2 - 8;
                Silhouette.draw(idm, contour(st), ix, iy, durabilityRatio(st), materialColor(st), time);
                GuiItems.drawModel(mc.player, st, ix, iy);   // model only: no count, no durability bar
            }
            GuiFlush.flush();                                // flush before the transform is popped
            DiffuseLighting.disableGuiDepthLighting();
        } finally {
            GuiItems.endItemTransform();
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
            BakedModel model = mc.getItemRenderer().getHeldItemModel(st, mc.world, mc.player, 0);
            out = Silhouette.trace(model.getParticleSprite());
        } catch (Throwable t) {
            out = new int[0];
        }
        if (out.length > 0) CACHE.put(item, out);
        return out;
    }

    private static int materialColor(ItemStack st) {
        if (st.getItem() instanceof DyeableItem dye && dye.hasColor(st)) {
            return dye.getColor(st) & 0xFFFFFF;   // leather-dye API (pre-DataComponents)
        }
        String id = st.getItem().toString().toLowerCase(Locale.ROOT);
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
