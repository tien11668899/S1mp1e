package dev.s1mp1e.client.module;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.glass.compat.Mc1132;
import net.minecraft.class_4277;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;

/**
 * Your own active potion effects, each shown as its vanilla ICON hugged by a colour OUTLINE that follows
 * the icon's REAL silhouette — exactly like {@link ArmorHudModule}. No name, no level, no time: just the
 * icon plus the outline that clings to its actual curve, tinted the effect's own colour with a flowing
 * ripple (shared trace/draw via {@link Silhouette}).
 *
 * <p>Fair play: reads {@code mc.player.getStatusEffectInstances()} only — the same list the vanilla HUD
 * and inventory already show. The silhouette is cached per icon index; any read failure degrades to just
 * the icon (never crashes).
 *
 * <p><b>1.13.2 icon path.</b> 1.13.2 has NO status-effect atlas (that arrived in 1.14), so this follows
 * mc189's inventory-sheet path: the icon is the 18&times;18 tile of
 * {@code textures/gui/container/inventory.png} at {@code u = idx % 12 * 18, v = 198 + idx / 12 * 18}
 * (scaled to 16&times;16), and the silhouette alpha mask comes from a one-time NativeImage read of the
 * same sheet. <b>The sheet is 12 icons per row on 1.13.2</b> — verified in vanilla
 * {@code InGameHud.method_18363} ({@code bipush 12; irem/idiv}, {@code 198 + row * 18}); mc189's
 * {@code idx % 8} would pick the wrong icon for every {@code idx >= 8}. Both the draw and the silhouette
 * tile use the same 12-per-row grid.
 *
 * <p>Legacy-yarn names (javap-verified against 1.13.2+build.604-v2): {@code getStatusEffectInstances()}
 * is the Collection ({@code getStatusEffects()} is the Map); {@code StatusEffectInstance.getStatusEffect()};
 * {@code StatusEffect.hasIcon/getIconLevel/getColor/getTranslationKey}; NativeImage is
 * {@code net.minecraft.class_4277} ({@code method_19472} read forcing RGBA, {@code method_19482}
 * makePixelArray = ARGB, {@code method_19458}/{@code method_19478} width/height); the static
 * {@code DrawableHelper.drawTexture(x, y, u, v, regionW, regionH, drawW, drawH, texW, texH)}.
 */
public final class PotionHudModule extends Module implements HudBounds, HudRenderer {

    private static final int CELL = 20;   // per-effect cell (icon 16 + 1px outline + margin), matches ArmorHUD
    /** 1.13.2's inventory.png effect-icon grid: 12 icons per row (NOT 1.8.9's 8). */
    private static final int ICONS_PER_ROW = 12;
    private static final Identifier INVENTORY_TEX = new Identifier("textures/gui/container/inventory.png");
    /** contour points (x,y pairs) per status-icon index; empty = read failed (retried next frame). */
    private static final Map<Integer, int[]> CACHE = new HashMap<Integer, int[]>();
    private static PotionHudModule instance;

    /** The inventory sheet's ARGB pixels, loaded once for the silhouette alpha mask. */
    private static int[] sheetArgb;
    private static int sheetW, sheetH;
    private static boolean sheetTried;

    public final Setting posX  = add(Setting.integer("X", 4, 0, 4000));
    public final Setting posY  = add(Setting.integer("Y", 160, 0, 4000));
    public final Setting scale = add(Setting.number("Scale", 1.0D, 0.5D, 2.0D));
    public final Setting hideVanilla = add(Setting.bool("Hide vanilla effects", true));
    public int lastW = CELL, lastH = CELL;

    public PotionHudModule() { super("PotionHUD", "HUD"); instance = this; this.enabled = true; }

    /** Hot-path gate for {@code InGameHudEffectMixin}: true when the vanilla top-right status-effect
     *  overlay (the grey boxes) should be hidden because this module already draws the effects. */
    public static boolean replacesVanilla() {
        PotionHudModule m = instance;
        return m != null && m.enabled && m.hideVanilla.boolValue;
    }

    @Override
    public void renderHud() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || Mc1132.hudHidden()) return;

        Collection<StatusEffectInstance> active = mc.player.getStatusEffectInstances();
        if (active == null || active.isEmpty()) return;

        List<StatusEffectInstance> sorted = new ArrayList<StatusEffectInstance>(active);
        Collections.sort(sorted, NAME_ORDER);

        // Only effects with a status icon on the sheet are drawable (as mc189 filters hasStatusIcon).
        List<StatusEffect> drawable = new ArrayList<StatusEffect>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            StatusEffect type = sorted.get(i).getStatusEffect();
            if (type == null || !type.hasIcon()) continue;
            drawable.add(type);
        }
        int n = drawable.size();
        if (n == 0) return;

        float s = (float) scale.doubleValue;
        lastW = Math.round(CELL * s);
        lastH = Math.round(n * CELL * s);
        float time = (System.nanoTime() % 3_000_000_000L) / 3.0e9f;

        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate((float) posX.intValue, (float) posY.intValue, 0f);
            GlStateManager.scale(s, s, 1f);

            // Outlines first (immediate-mode fills). The outline sits 1px OUTSIDE the shape, so the icon
            // never covers it.
            for (int i = 0; i < n; i++) {
                StatusEffect type = drawable.get(i);
                int ix = CELL / 2 - 8, iy = i * CELL + CELL / 2 - 8;
                Silhouette.draw(contour(mc, type.getIconLevel()), ix, iy, 1f, color(type), time);
            }
            // Reset the colour cache (hard rule 5) before the textured icon pass.
            GlassWidgets.resetColorCache();

            // Icons from the inventory sheet: 18x18 tile scaled to 16x16.
            GlStateManager.enableBlend();
            GlStateManager.enableAlphaTest();
            GlStateManager.color(1f, 1f, 1f, 1f);
            mc.getTextureManager().bindTexture(INVENTORY_TEX);
            for (int i = 0; i < n; i++) {
                int idx = drawable.get(i).getIconLevel();
                int u = idx % ICONS_PER_ROW * 18, v = 198 + idx / ICONS_PER_ROW * 18;
                int ix = CELL / 2 - 8, iy = i * CELL + CELL / 2 - 8;
                DrawableHelper.drawTexture(ix, iy, (float) u, (float) v, 18, 18, 16, 16, 256f, 256f);
            }

            // Restore for later draws (glass pipeline). Force the colour cache white; leave blend enabled.
            GlassWidgets.resetColorCache();
            GlStateManager.enableAlphaTest();
            GlStateManager.enableBlend();
        } finally {
            GlStateManager.popMatrix();
        }
    }

    /** Cached-per-icon-index 1px-outside contour, traced from the inventory-sheet ARGB tile. The tile rect
     *  is given in the sheet's own pixels (a higher-resolution resource-pack sheet is scaled from the
     *  vanilla 256&times;256 layout), on the same 12-per-row grid as the icon draw. */
    private static int[] contour(MinecraftClient mc, int idx) {
        Integer key = Integer.valueOf(idx);
        int[] cached = CACHE.get(key);
        if (cached != null) return cached;
        int[] argb = sheet(mc);
        if (argb == null) return new int[0];
        float kx = sheetW / 256f, ky = sheetH / 256f;
        int u = idx % ICONS_PER_ROW * 18, v = 198 + idx / ICONS_PER_ROW * 18;
        int[] out = Silhouette.traceTile(argb, sheetW, Math.round(u * kx), Math.round(v * ky),
                                         Math.max(1, Math.round(18 * kx)), Math.max(1, Math.round(18 * ky)));
        if (out.length > 0) CACHE.put(key, out);
        return out;
    }

    /** The effect's own display colour (falls back to a soft grey if it reports 0). */
    private static int color(StatusEffect type) {
        int rgb = type.getColor() & 0xFFFFFF;
        return rgb == 0 ? 0xC0C0C8 : rgb;
    }

    /**
     * Load the inventory sheet's ARGB pixels once (for the silhouette alpha mask); null on failure. The
     * NativeImage read ({@code class_4277.method_19472}) forces RGBA, so {@code method_19482}
     * (makePixelArray) is legal and returns ARGB with alpha in the high byte — exactly mc189's input
     * format. The image and the Resource are always closed; any failure yields no outline, never a crash.
     */
    private static int[] sheet(MinecraftClient mc) {
        if (sheetTried) return sheetArgb;
        sheetTried = true;
        Resource res = null;
        class_4277 img = null;
        try {
            res = mc.getResourceManager().getResource(INVENTORY_TEX);
            InputStream in = res.getInputStream();
            img = class_4277.method_19472(in);
            int[] px = img.method_19482();
            sheetW = img.method_19458();
            sheetH = img.method_19478();
            sheetArgb = (sheetW > 0 && sheetH > 0) ? px : null;
        } catch (Throwable t) {
            sheetArgb = null;
        } finally {
            if (img != null) { try { img.close(); } catch (Throwable ignored) { } }
            if (res != null) { try { res.close(); } catch (Throwable ignored) { } }
        }
        return sheetArgb;
    }

    // ---- HudBounds ----
    public int hudX() { return posX.intValue; }
    public int hudY() { return posY.intValue; }
    public void hudSetPos(int x, int y) { posX.setInt(x); posY.setInt(y); }
    public int hudW() { return lastW > 0 ? lastW : CELL; }
    public int hudH() { return lastH > 0 ? lastH : CELL; }
    public void hudResetPos() { posX.reset(); posY.reset(); }
    public String hudLabel() { return name; }

    /** Stable ordering by DISPLAY NAME, exactly as mc1211 sorts (its {@code getName().getString()}): the
     *  same localised string is produced by translating the effect's translation key; the raw key is the
     *  fallback when a language file has no entry, which keeps the order deterministic either way. */
    private static final Comparator<StatusEffectInstance> NAME_ORDER = new Comparator<StatusEffectInstance>() {
        public int compare(StatusEffectInstance a, StatusEffectInstance b) {
            return displayName(a.getStatusEffect()).compareTo(displayName(b.getStatusEffect()));
        }
    };

    /** The effect's localised display name (the raw translation key if it is untranslated). */
    private static String displayName(StatusEffect type) {
        if (type == null) return "";
        String key = type.getTranslationKey();
        if (key == null) return "";
        try {
            String s = I18n.translate(key);
            return s == null ? key : s;
        } catch (Throwable t) {
            return key;
        }
    }
}
