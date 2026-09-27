package dev.s1mp1e.client.module;

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
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;

/**
 * Your own active potion effects, each shown as its vanilla ICON hugged by a colour OUTLINE that follows
 * the icon's REAL silhouette — exactly like {@link ArmorHudModule}. No name, no level, no time: just the
 * icon plus the outline that clings to its actual curve, tinted the effect's own colour with a flowing
 * ripple (shared trace/draw via {@link Silhouette}).
 *
 * <p>Fair play: reads {@code mc.player.getStatusEffects()} only — the same list the vanilla HUD and
 * inventory already show. The silhouette is cached per effect type; any read failure degrades to just the
 * icon (never crashes).
 *
 * <p>1.14.4 port of mc1201's {@code PotionHudModule}: immediate-mode ({@link GlStateManager} matrix), the
 * effect sprite comes from {@code MinecraftClient.getStatusEffectSpriteManager()} and is drawn with the
 * static {@link DrawableHelper#blit(int, int, int, int, int, Sprite)} after binding
 * {@link SpriteAtlasTexture#STATUS_EFFECT_ATLAS_TEX}.
 */
public final class PotionHudModule extends Module implements HudBounds, HudRenderer {

    private static final int CELL = 20;   // per-effect cell (icon 16 + 1px outline + margin), matches ArmorHUD
    private static final Map<StatusEffect, int[]> CACHE = new HashMap<StatusEffect, int[]>();
    private static PotionHudModule instance;

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
        if (mc.player == null || mc.world == null || mc.options.hudHidden) return;

        Collection<StatusEffectInstance> active = mc.player.getStatusEffects();
        if (active == null || active.isEmpty()) return;

        List<StatusEffectInstance> sorted = new ArrayList<StatusEffectInstance>(active);
        Collections.sort(sorted, NAME_ORDER);
        int n = sorted.size();

        float s = (float) scale.doubleValue;
        lastW = Math.round(CELL * s);
        lastH = Math.round(n * CELL * s);
        float time = (System.nanoTime() % 3_000_000_000L) / 3.0e9f;

        GlStateManager.pushMatrix();
        try {
            GlStateManager.translatef((float) posX.intValue, (float) posY.intValue, 0f);
            GlStateManager.scalef(s, s, 1f);

            // Outlines first (immediate-mode fills). The outline sits 1px OUTSIDE the shape, so the icon
            // never covers it.
            for (int i = 0; i < n; i++) {
                StatusEffect type = sorted.get(i).getEffectType();
                Sprite sprite = mc.getStatusEffectSpriteManager().getSprite(type);
                int ix = CELL / 2 - 8, iy = i * CELL + CELL / 2 - 8;
                Silhouette.draw(contour(type, sprite), ix, iy, 1f, color(type), time);
            }
            // Reset the colour cache (hard rule 5) before the textured icon pass.
            GlassWidgets.resetColorCache();

            // Icons from the status-effect atlas.
            GlStateManager.enableBlend();
            GlStateManager.enableAlphaTest();
            GlStateManager.color4f(1f, 1f, 1f, 1f);
            mc.getTextureManager().bindTexture(SpriteAtlasTexture.STATUS_EFFECT_ATLAS_TEX);
            for (int i = 0; i < n; i++) {
                StatusEffect type = sorted.get(i).getEffectType();
                Sprite sprite = mc.getStatusEffectSpriteManager().getSprite(type);
                int ix = CELL / 2 - 8, iy = i * CELL + CELL / 2 - 8;
                DrawableHelper.blit(ix, iy, 0, 16, 16, sprite);
            }

            // Restore for later draws (glass pipeline). Force the colour cache white; leave blend enabled.
            GlassWidgets.resetColorCache();
            GlStateManager.enableAlphaTest();
            GlStateManager.enableBlend();
        } finally {
            GlStateManager.popMatrix();
        }
    }

    /** Cached-per-effect 1px-outside contour of the effect icon's silhouette (traced by {@link Silhouette}). */
    private static int[] contour(StatusEffect type, Sprite sprite) {
        int[] cached = CACHE.get(type);
        if (cached != null) return cached;
        int[] out = Silhouette.trace(sprite);
        if (out.length > 0) CACHE.put(type, out);
        return out;
    }

    /** The effect's own display colour (falls back to a soft grey if it reports 0). */
    private static int color(StatusEffect type) {
        int rgb = type.getColor() & 0xFFFFFF;
        return rgb == 0 ? 0xC0C0C8 : rgb;
    }

    // ---- HudBounds ----
    public int hudX() { return posX.intValue; }
    public int hudY() { return posY.intValue; }
    public void hudSetPos(int x, int y) { posX.setInt(x); posY.setInt(y); }
    public int hudW() { return lastW > 0 ? lastW : CELL; }
    public int hudH() { return lastH > 0 ? lastH : CELL; }
    public void hudResetPos() { posX.reset(); posY.reset(); }
    public String hudLabel() { return name; }

    /** Stable ordering by DISPLAY NAME, exactly as mc1211 sorts (its {@code getName().getString()}).
     *  1.14.4 yarn leaves {@code StatusEffect}'s name accessor unmapped ({@code method_5560}), so the
     *  same localised string is produced by translating the effect's translation key; the raw key is the
     *  fallback when a language file has no entry, which keeps the order deterministic either way. */
    private static final Comparator<StatusEffectInstance> NAME_ORDER = new Comparator<StatusEffectInstance>() {
        public int compare(StatusEffectInstance a, StatusEffectInstance b) {
            return displayName(a.getEffectType()).compareTo(displayName(b.getEffectType()));
        }
    };

    /** The effect's localised display name (the raw translation key if it is untranslated). */
    private static String displayName(StatusEffect type) {
        String key = type.getTranslationKey();
        try {
            String s = I18n.translate(key);
            return s == null ? key : s;
        } catch (Throwable t) {
            return key;
        }
    }
}
