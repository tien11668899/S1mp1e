package dev.s1mp1e.client.module;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassWidgets;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.item.DyeableItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DefaultedList;
import net.minecraft.util.math.MathHelper;

/**
 * Worn armour as icons, each hugged by a colour OUTLINE that follows the item's REAL silhouette — the
 * icon sprite's alpha mask is read (via {@link Silhouette}) and the 1px-outside contour is traced, so the
 * outline clings to the item's actual shape (like a text outline follows the glyph). The trace is ordered
 * CLOCKWISE from the top and covers the remaining-durability fraction, so it disappears clockwise as
 * durability drops; the item's material colour flows a bright ripple along it. No held item, no number,
 * no green bar.
 *
 * <p>Fair play: reads {@code mc.player.inventory.armor} only. The silhouette is cached per item type; any
 * read failure degrades to just the icon (never crashes).
 *
 * <p>1.15.2 port of mc1201's {@code ArmorHudModule}: immediate-mode ({@link RenderSystem} matrix +
 * {@link ItemRenderer#renderGuiItem}) like mc189, but the silhouette source is a {@link net.minecraft.client.texture.Sprite}
 * (via {@link Silhouette}) as on the newer lines. Slot order: {@code inventory.armor} is 0=boots .. 3=helmet,
 * so a head-to-toe list walks the list backwards.
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
        if (mc.player == null || mc.world == null || mc.options.hudHidden) return;

        DefaultedList<ItemStack> armour = mc.player.inventory.armor;
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

        RenderSystem.pushMatrix();
        try {
            RenderSystem.translatef((float) posX.intValue, (float) posY.intValue, 0f);
            RenderSystem.scalef(s, s, 1f);

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

            ItemRenderer ir = mc.getItemRenderer();
            // 1.15.2: renderGuiItem self-manages its GL state (pushMatrix, rescale-normal, alpha test, blend,
            // z 100+zOffset, flat/3D GUI light vectors). Only the 3D GUI light vectors are primed here;
            // DiffuseLighting.enable()/disable() are NEVER called, because enable() leaves GL_LIGHTING on
            // for every later fixed-function draw.
            DiffuseLighting.enableGuiDepthLighting();
            RenderSystem.enableRescaleNormal();
            for (int i = 0; i < n; i++) {
                ItemStack st = rows.get(i);
                int ix = CELL / 2 - 8, iy = i * CELL + CELL / 2 - 8;
                ir.renderGuiItem(st, ix, iy);
            }
            RenderSystem.disableRescaleNormal();

            // Restore state for whatever draws after us this frame (including the glass pipeline). Force the
            // colour cache white and re-enable alpha test; NEVER disableBlend on exit (hard rule 5).
            GlassWidgets.resetColorCache();
            RenderSystem.enableAlphaTest();
            RenderSystem.enableBlend();
        } finally {
            RenderSystem.popMatrix();
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
            BakedModel model = mc.getItemRenderer().getHeldItemModel(st, mc.world, mc.player);
            out = Silhouette.trace(model.getSprite());
        } catch (Throwable t) {
            out = new int[0];
        }
        if (out.length > 0) CACHE.put(item, out);
        return out;
    }

    private static int materialColor(ItemStack st) {
        Item item = st.getItem();
        if (item instanceof DyeableItem) {
            DyeableItem dye = (DyeableItem) item;
            if (dye.hasColor(st)) return dye.getColor(st) & 0xFFFFFF;   // dyed leather
        }
        String id = item.toString().toLowerCase(Locale.ROOT);
        if (id.contains("netherite")) return 0x6A5E5A;   // inert on 1.15.2 (no netherite), kept for parity
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
