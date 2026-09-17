package dev.s1mp1e.client.module;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.Sprite;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;

/**
 * Your own active potion effects, each shown as its vanilla ICON hugged by a colour OUTLINE that
 * follows the icon's REAL silhouette — exactly like {@link ArmorHudModule}. No name, no level, no
 * time: just the icon plus the outline that clings to its actual curve, tinted the effect's own
 * colour with a flowing ripple (shared trace/draw via {@link Silhouette}).
 *
 * <p>Fair play: reads {@code mc.player.getStatusEffects()} only — the same list the vanilla HUD and
 * inventory already show. The silhouette is cached per effect type; any read failure degrades to just
 * the icon (never crashes).
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
    public void renderHud(DrawContext ctx) {
        if (!enabled) return;
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

        // push in try/finally so a swallowed throw can't leave the shared matrix stack unbalanced.
        ctx.getMatrices().push();
        try {
            ctx.getMatrices().translate(posX.intValue, posY.intValue, 0f);
            ctx.getMatrices().scale(s, s, 1f);
            for (int i = 0; i < n; i++) {
                StatusEffectInstance eff = sorted.get(i);
                StatusEffect type = eff.getEffectType();
                Sprite sprite = mc.getStatusEffectSpriteManager().getSprite(type);
                int ix = CELL / 2 - 8, iy = i * CELL + CELL / 2 - 8;
                // Full outline (no time encoding), the icon on top. Outline sits 1px OUTSIDE the shape,
                // so the icon never covers it.
                Silhouette.draw(ctx, contour(type, sprite), ix, iy, 1f, color(type), time);
                ctx.drawSprite(ix, iy, 0, 16, 16, sprite);
            }
        } finally {
            ctx.getMatrices().pop();
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

    /** Stable ordering (getStatusEffects' iteration order is not guaranteed). */
    private static final Comparator<StatusEffectInstance> NAME_ORDER = new Comparator<StatusEffectInstance>() {
        public int compare(StatusEffectInstance a, StatusEffectInstance b) {
            return a.getEffectType().getName().getString()
                    .compareTo(b.getEffectType().getName().getString());
        }
    };
}
