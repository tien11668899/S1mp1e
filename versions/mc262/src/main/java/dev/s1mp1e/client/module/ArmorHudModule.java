package dev.s1mp1e.client.module;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.S1mp1eHudCtx;
import dev.s1mp1e.client.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;

/**
 * Worn armour as icons, each hugged by a colour OUTLINE that follows the item's REAL
 * silhouette — the icon sprite's alpha mask is read (via {@link Silhouette}) and
 * the 1px-outside contour is traced, so the outline clings to the item's actual shape (like a
 * text outline follows the glyph). The trace is ordered CLOCKWISE from the top and covers the
 * remaining-durability fraction, so it disappears clockwise as durability drops; the item's
 * material colour flows a bright ripple along it. No held item, no number, no green bar.
 *
 * <p>26.2 port: the {@code armor} list is gone from {@code Inventory}, so the four armour slots are read with
 * {@code getItemBySlot(HEAD/CHEST/LEGS/FEET)} (same top-to-bottom order as mc1211's reversed armour list).
 * {@code BakedModel.getParticleSprite()} is gone; the icon sprite now comes from the item's GUI render state
 * ({@code ItemModelResolver.updateForLiving} → {@code pickParticleMaterial}), unioned over its layers.
 *
 * <p>Fair play: reads the local player's own worn armour only. The silhouette is cached per
 * item type; any read failure degrades to just the icon (never crashes).
 */
public final class ArmorHudModule extends Module implements HudBounds, HudRenderer {

    private static final int CELL = 20;      // per-piece cell (icon 16 + 1px outline + margin)
    /** contour points (x,y pairs) per item type; empty = read failed (retried next frame). */
    private static final Map<Item, int[]> CACHE = new HashMap<Item, int[]>();
    /** Top-to-bottom, as mc1211 iterated {@code armor} from index 3 (head) down to 0 (feet). */
    private static final EquipmentSlot[] SLOTS = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };

    public final Setting posX  = add(Setting.integer("X", 4, 0, 4000));
    public final Setting posY  = add(Setting.integer("Y", 60, 0, 4000));
    public final Setting scale = add(Setting.number("Scale", 1.0D, 0.5D, 2.0D));
    public int lastW = CELL, lastH = CELL;

    public ArmorHudModule() { super("ArmorHUD", "HUD"); this.enabled = true; }

    @Override
    public void renderHud(S1mp1eHudCtx c) {
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;   // F1 handled by HudDriverMixin
        GuiGraphicsExtractor g = c.g();

        List<ItemStack> rows = new ArrayList<ItemStack>(4);
        for (EquipmentSlot slot : SLOTS) {
            ItemStack st = mc.player.getItemBySlot(slot);
            if (st != null && !st.isEmpty()) rows.add(st);
        }
        int n = rows.size();
        if (n == 0) return;

        float s = (float) scale.doubleValue;
        lastW = Math.round(CELL * s);
        lastH = Math.round(n * CELL * s);
        float time = (System.nanoTime() % 3_000_000_000L) / 3.0e9f;

        g.pose().pushMatrix();
        try {
            g.pose().translate(posX.intValue, posY.intValue);
            g.pose().scale(s, s);
            for (int i = 0; i < n; i++) {
                ItemStack st = rows.get(i);
                int ix = CELL / 2 - 8, iy = i * CELL + CELL / 2 - 8;
                Silhouette.draw(g, contour(mc, st), ix, iy, durabilityRatio(st), materialColor(st), time);
                g.item(st, ix, iy);
            }
        } finally {
            g.pose().popMatrix();
        }
    }

    /** The 1px-outside contour of the item icon's silhouette (traced by {@link Silhouette}), cached per
     *  Item type. Only successful reads are cached, so a transient atlas miss retries next frame. */
    private static int[] contour(Minecraft mc, ItemStack st) {
        Item item = st.getItem();
        int[] cached = CACHE.get(item);
        if (cached != null) return cached;
        int[] out;
        try {
            LocalPlayer p = mc.player;
            ItemStackRenderState state = new ItemStackRenderState();
            mc.getItemModelResolver().updateForLiving(state, st, ItemDisplayContext.GUI, p);
            boolean[][] op = new boolean[16][16];
            List<TextureAtlasSprite> seen = new ArrayList<TextureAtlasSprite>(2);
            // pickParticleMaterial picks a random layer; sweep a few fixed seeds to cover every layer
            // (e.g. dyed leather's base + overlay, or a trim layer) and union their masks into one silhouette.
            // 32 seeds (once per Item, then cached) so a 3+ layer model can't statistically miss a layer.
            for (long seed = 0; seed < 32; seed++) {
                Material.Baked mat = state.pickParticleMaterial(RandomSource.create(seed));
                if (mat == null) continue;
                TextureAtlasSprite spr = mat.sprite();
                if (spr == null || seen.contains(spr)) continue;
                seen.add(spr);
                Silhouette.mask(spr, op);
            }
            out = seen.isEmpty() ? new int[0] : Silhouette.traceMask(op);
        } catch (Throwable t) {
            dev.s1mp1e.client.ErrorOnce.report("ArmorHUD outline", t);
            out = new int[0];
        }
        if (out.length > 0) CACHE.put(item, out);
        return out;
    }

    private static int materialColor(ItemStack st) {
        DyedItemColor dc = st.get(DataComponents.DYED_COLOR);
        if (dc != null) return dc.rgb() & 0xFFFFFF;
        String id = st.getItem().toString().toLowerCase(Locale.ROOT);   // "minecraft:diamond_helmet"
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
        if (st.isDamageableItem() && st.getMaxDamage() > 0) {
            return Mth.clamp((float) (st.getMaxDamage() - st.getDamageValue()) / (float) st.getMaxDamage(), 0f, 1f);
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
