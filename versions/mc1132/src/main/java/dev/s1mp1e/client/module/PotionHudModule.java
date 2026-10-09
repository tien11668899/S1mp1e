package dev.s1mp1e.client.module;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.client.hud.HudFade;
import dev.s1mp1e.client.hud.HudText;
import dev.s1mp1e.glass.compat.Mc1132;
import net.minecraft.class_4277;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;

/**
 * Your own active potion effects, each on a square liquid-glass tile: the vanilla ICON over a solid fill of its own
 * silhouette in the effect's colour (shared mask via {@link Silhouette}), plus an optional remaining-time label to the
 * right of the tile ("Show time").
 *
 * <p>Fair play: reads {@code mc.player.getStatusEffectInstances()} only — the same list the vanilla HUD
 * and inventory already show. The silhouette mask is cached per icon index; any read failure degrades to just
 * the icon (never crashes).
 *
 * <p>Icon path: no status-effect atlas here, so the icon is the 18&times;18 tile of
 * {@code textures/gui/container/inventory.png} at {@code u = idx % 12 * 18, v = 198 + idx / 12 * 18} (scaled to
 * 16&times;16), and the silhouette alpha mask comes from a one-time NativeImage read of the same sheet.
 *
 * <p><b>Appear / glide / disappear (2026-10-08, ported from mc1144).</b> Effects are kept as {@link Row}s in a
 * {@link LinkedHashMap}: a new effect's row fades IN from 0 (its first sighting IS its appearance, so {@code snapFirst}
 * is false), an expired one keeps drawing in place while it fades OUT then is forgotten, and when the sorted order
 * changes every row GLIDES to its new slot ({@code y += (slot-y)*(1-e^(-dt/0.07))}) instead of jumping. Each row's
 * glass tile / silhouette / label fade via {@link HudFade#alpha}; the inventory-sheet icon can't take an alpha through
 * a plain blit, so its fade rides {@code GlStateManager.color(1,1,1,alpha)} around the blit (reset to opaque white
 * after). 1.13.2 names: {@code translate} / {@code scale} / {@code color} (there is no {@code translatef} / {@code
 * color4f}), and the icon is the inventory-sheet tile (no status-effect atlas).
 */
public final class PotionHudModule extends Module implements HudBounds, HudRenderer {

    /** Square glass tile per effect (icon 16 + 1px colour fill + 2px air each side), and the row pitch (2px gap). */
    private static final int TILE = 22;
    private static final int CELL = TILE + 2;
    /** Gap between the tile and the remaining-time label. */
    private static final int TEXT_GAP = 4;
    /** The inventory.png effect-icon grid: 12 icons per row. */
    private static final int ICONS_PER_ROW = 12;
    private static final Identifier INVENTORY_TEX = new Identifier("textures/gui/container/inventory.png");
    /** 16x16 opaque mask per status-icon index; empty = read failed (retried next frame). */
    private static final Map<Integer, boolean[][]> CACHE = new HashMap<Integer, boolean[][]>();
    private static PotionHudModule instance;

    /** The inventory sheet's ARGB pixels, loaded once for the silhouette alpha mask. */
    private static int[] sheetArgb;
    private static int sheetW, sheetH;
    private static boolean sheetTried;

    public final Setting posX  = add(Setting.integer("X", 4, 0, 4000));
    public final Setting posY  = add(Setting.integer("Y", 160, 0, 4000));
    public final Setting scale = add(Setting.number("Scale", 1.0D, 0.5D, 2.0D));
    public final Setting hideVanilla = add(Setting.bool("Hide vanilla effects", true));
    public final Setting showTime = add(Setting.bool("Show time", true));
    public final Setting colorFill = add(Setting.bool("Colour fill", true));
    public int lastW = CELL, lastH = CELL;

    /** One effect's row: its gliding slot y (cell units, unscaled), icon index and last duration, so an expired
     *  effect keeps drawing in place while it fades out. */
    private static final class Row {
        final StatusEffect type;
        final int idx;        // inventory.png icon index (1.13.2 has no status-effect atlas sprite)
        float y;
        int dur;
        boolean inf;
        Row(StatusEffect type, int idx) { this.type = type; this.idx = idx; }
    }

    /** Known rows: live effects plus expired ones still fading out. Render thread only. */
    private final LinkedHashMap<StatusEffect, Row> rows = new LinkedHashMap<StatusEffect, Row>();
    private long lastFrameNs;
    /** Time constant of a row's glide to its new slot when the sorted list changes. */
    private static final float GLIDE_S = 0.07f;

    public PotionHudModule() { super("PotionHUD", "HUD"); instance = this; this.enabled = true; }

    /** Hot-path gate for {@code InGameHudEffectMixin}: true when the vanilla top-right status-effect
     *  overlay (the grey boxes) should be hidden because this module already draws the effects. */
    public static boolean replacesVanilla() {
        PotionHudModule m = instance;
        return m != null && m.enabled && m.hideVanilla.boolValue;
    }

    @Override
    public void renderHud() {
        // module visibility (incl. the fade-out after switching off) is decided by the HUD driver via HudFade
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || Mc1132.hudHidden()) return;

        Collection<StatusEffectInstance> active = mc.player.getStatusEffectInstances();
        List<StatusEffectInstance> sorted = active == null
                ? new ArrayList<StatusEffectInstance>() : new ArrayList<StatusEffectInstance>(active);
        Collections.sort(sorted, NAME_ORDER);

        // Only effects with a status icon on the sheet are drawable.
        List<StatusEffectInstance> drawable = new ArrayList<StatusEffectInstance>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            StatusEffect type = sorted.get(i).getStatusEffect();
            if (type == null || !type.hasIcon()) continue;
            drawable.add(sorted.get(i));
        }
        int n = drawable.size();

        long now = System.nanoTime();
        float dt = lastFrameNs == 0L ? 0f : Math.min(0.1f, (now - lastFrameNs) / 1.0e9f);
        lastFrameNs = now;
        float glide = 1f - (float) Math.exp(-dt / GLIDE_S);

        float s = (float) scale.doubleValue;
        boolean time = showTime.boolValue;
        TextRenderer font = mc.textRenderer;

        // Live effects take their sorted slot. A new one appears in its slot (fading in); the others GLIDE to their
        // new slot instead of jumping when an effect is gained or runs out.
        HashSet<StatusEffect> live = new HashSet<StatusEffect>();
        int textW = 0;
        for (int i = 0; i < n; i++) {
            StatusEffectInstance inst = drawable.get(i);
            StatusEffect type = inst.getStatusEffect();
            live.add(type);
            float slot = i * CELL;
            Row r = rows.get(type);
            if (r == null) {
                r = new Row(type, type.getIconLevel());
                r.y = slot;
                rows.put(type, r);
            } else {
                r.y += (slot - r.y) * glide;
            }
            int d = inst.getDuration();
            r.inf = d < 0 || d >= 1_000_000;   // 1.13.2 has no StatusEffectInstance#isInfinite(); treat huge/negative as infinite
            r.dur = d;
            if (time) textW = Math.max(textW, font.getStringWidth(label(r)));
        }

        if (rows.isEmpty()) return;

        if (n > 0) {
            lastW = Math.round((TILE + (time ? TEXT_GAP + textW : 0)) * s);
            lastH = Math.round((n * CELL - (CELL - TILE)) * s);
        }

        float saved = HudFade.alpha;
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate((float) posX.intValue, (float) posY.intValue, 0f);
            GlStateManager.scale(s, s, 1f);
            Iterator<Row> it = rows.values().iterator();
            while (it.hasNext()) {
                Row r = it.next();
                boolean on = live.contains(r.type);
                float v = HudFade.visibility(r, on, false);   // new effect fades in, expired one fades out
                if (v <= 0.004f) {
                    if (!on) {
                        HudFade.forget(r);
                        it.remove();
                    }
                    continue;
                }
                HudFade.alpha = saved * v;
                int ty = Math.round(r.y);
                int ix = (TILE - 16) / 2, iy = ty + (TILE - 16) / 2;

                // Square liquid-glass tile (baked through the current GL matrix by glassBoxCtx; fades via HudFade.alpha).
                HudGlass.glassBoxCtx(0, ty, TILE, ty + TILE, 0.85f);
                GlassWidgets.resetColorCache();

                // Filled colour silhouette UNDER the icon (optional): a solid fill (fades via HudFade in Silhouette.fill).
                if (colorFill.boolValue) {
                    Silhouette.fill(mask(mc, r.idx), ix, iy, color(r.type));
                    GlassWidgets.resetColorCache();
                }

                // Icon from the inventory sheet (18x18 tile scaled to 16x16). DrawableHelper.drawTexture modulates by
                // the GL colour, so color(1,1,1,alpha) carries the appear/disappear fade. Reset to opaque white after.
                int u = r.idx % ICONS_PER_ROW * 18, vtex = 198 + r.idx / ICONS_PER_ROW * 18;
                GlStateManager.enableBlend();
                GlStateManager.enableAlphaTest();
                GlStateManager.color(1f, 1f, 1f, HudFade.alpha);
                mc.getTextureManager().bindTexture(INVENTORY_TEX);
                DrawableHelper.drawTexture(ix, iy, (float) u, (float) vtex, 18, 18, 16, 16, 256f, 256f);
                GlStateManager.color(1f, 1f, 1f, 1f);

                // Remaining-time label (optional); fades via HudText honouring HudFade.alpha.
                if (time) {
                    HudText.draw(label(r), TILE + TEXT_GAP, ty + (TILE - font.fontHeight) / 2f, 0xFFFFFFFF, true);
                }
            }
            GlassWidgets.resetColorCache();
            GlStateManager.enableAlphaTest();
            GlStateManager.enableBlend();
        } finally {
            GlStateManager.popMatrix();
            HudFade.alpha = saved;
        }
    }

    /** Cached-per-icon-index 16x16 opaque mask, read once from the inventory sheet; empty mask on failure. */
    private static boolean[][] mask(MinecraftClient mc, int idx) {
        Integer key = Integer.valueOf(idx);
        boolean[][] cached = CACHE.get(key);
        if (cached != null) return cached;
        boolean[][] op = new boolean[16][16];
        int[] argb = sheet(mc);
        if (argb == null) return op;
        try {
            float kx = sheetW / 256f, ky = sheetH / 256f;
            int u = idx % ICONS_PER_ROW * 18, v = 198 + idx / ICONS_PER_ROW * 18;
            Silhouette.maskTile(argb, sheetW, Math.round(u * kx), Math.round(v * ky),
                    Math.max(1, Math.round(18 * kx)), Math.max(1, Math.round(18 * ky)), op);
            CACHE.put(key, op);
        } catch (Throwable t) {
            // degrade to just the icon
        }
        return op;
    }

    /** Remaining time as m:ss (h:mm:ss past an hour), or ∞ for an effectively-infinite effect. */
    private static String label(Row r) {
        if (r.inf) return "∞";
        int sec = Math.max(0, r.dur) / 20;
        int h = sec / 3600, m = (sec / 60) % 60, ss = sec % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, ss) : String.format("%d:%02d", m, ss);
    }

    /** The effect's own display colour (falls back to a soft grey if it reports 0). */
    private static int color(StatusEffect type) {
        int rgb = type.getColor() & 0xFFFFFF;
        return rgb == 0 ? 0xC0C0C8 : rgb;
    }

    /**
     * Load the inventory sheet's ARGB pixels once (for the silhouette alpha mask); null on failure. The image and
     * the Resource are always closed; any failure yields no outline, never a crash.
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

    /** Stable ordering by DISPLAY NAME (the raw translation key is the fallback). */
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
