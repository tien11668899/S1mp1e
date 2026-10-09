package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.gui.AllGlass;
import dev.s1mp1e.client.module.HudGlass;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * All-glass pass, 1.20.1 — the counterpart of 1.21.1's {@code AllGlassSpriteMixin}. 1.20.1 has no GUI sprites: these
 * elements are REGIONS of shared atlas textures, and every public {@code DrawContext.drawTexture} overload ends in the
 * 12-arg {@code drawTexture(Identifier, x1, x2, y1, y2, z, regionWidth, regionHeight, u, v, textureWidth, textureHeight)}
 * (the same funnel {@code SfIconMixin} uses). One HEAD hook there matches each element by texture + exact uv region
 * (verified with javap on each caller), so nothing else on those atlases is touched:
 * <ul>
 *   <li>#6 header/footer separators ({@code CreateWorldScreen.HEADER/FOOTER_SEPARATOR_TEXTURE}; 1.20.1 has no in-world
 *       variants) → 0.5 px white hairline fading at both ends;</li>
 *   <li>#12 anvil rename field ({@code anvil.png} u0 v166/182 110x16, v182 = no item) → frosted scrim; enchanting-table
 *       option rows ({@code enchanting_table.png} u0 108x19: v166 available, v204 hovered, v185 unavailable) →
 *       glass-button capsule (hover lift) / faint scrim;</li>
 *   <li>#17 XP and horse-jump bars ({@code icons.png} 5 px rows: v64 / v84 track → glass-button capsule; v69 XP
 *       #30D158, v89 jump #FF9F0A, v74 jump cooldown #8E8E93 → round-capped colour bar);</li>
 *   <li>#20 spectator hotbar ({@code widgets.png} u0 v0 182x22 bar + u0 v22 24x22 selection, ONLY while the player is a
 *       spectator — the survival hotbar is already the glass bar) → HUD glass strip + lifted selection, honouring the
 *       menu's fade (shader-colour alpha).</li>
 * </ul>
 * Everything goes through {@link AllGlass} (flush + ctx-matrix bake + no depth test), so it lines up inside the HUD's
 * lifted XP matrix ({@code InGameHudMixin}) and the containers' translations.
 */
@Mixin(DrawContext.class)
public abstract class AllGlassTextureMixin {

    @Inject(method = "drawTexture(Lnet/minecraft/util/Identifier;IIIIIIIFFII)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$allGlass(Identifier tex, int x1, int x2, int y1, int y2, int z, int rw, int rh,
                                 float u, float v, int tw, int th, CallbackInfo ci) {
        if (tex == null) return;
        // Inside a container's drawBackground, DrawContextBodyBlitMixin's @ModifyVariable may already have swapped a
        // vanilla container texture for its keyed copy "s1mp1e:keyed/<path>" (injector order is not guaranteed), so
        // accept both spellings of the same atlas.
        String ns = tex.getNamespace(), p = tex.getPath();
        if ("s1mp1e".equals(ns) && p.startsWith("keyed/textures/")) { ns = "minecraft"; p = p.substring("keyed/".length()); }
        if (!"minecraft".equals(ns)) return;
        DrawContext ctx = (DrawContext) (Object) this;
        int w = x2 - x1, h = y2 - y1;

        // #6 separators
        if (tex.equals(CreateWorldScreen.HEADER_SEPARATOR_TEXTURE) || tex.equals(CreateWorldScreen.FOOTER_SEPARATOR_TEXTURE)) {
            ci.cancel();
            if (w <= 0) return;
            float cy = y1 + h / 2f, fade = Math.min(48f, w / 4f);
            AllGlass.scrim(ctx, x1, cy - 0.25f, x1 + fade, cy + 0.25f, 0f, 0x0FFFFFFF);
            AllGlass.scrim(ctx, x1 + fade, cy - 0.25f, x2 - fade, cy + 0.25f, 0f, 0x26FFFFFF);
            AllGlass.scrim(ctx, x2 - fade, cy - 0.25f, x2, cy + 0.25f, 0f, 0x0FFFFFFF);
            return;
        }
        if (u != 0f) return;
        switch (p) {
            case "textures/gui/container/anvil.png" -> {                       // #12 rename field
                if (rw != 110 || rh != 16 || (v != 166f && v != 182f)) return;
                ci.cancel();
                AllGlass.scrim(ctx, x1, y1, x2, y2, 4f, v == 182f ? 0x14FFFFFF : 0x2EFFFFFF);
            }
            case "textures/gui/container/enchanting_table.png" -> {            // #12 option rows
                if (rw != 108 || rh != 19 || (v != 166f && v != 185f && v != 204f)) return;
                ci.cancel();
                if (v == 185f) AllGlass.scrim(ctx, x1, y1, x2, y2, Math.min(6.3f, h / 2f), 0x14FFFFFF);
                else AllGlass.capsule(ctx, x1, y1, x2, y2, AllGlass.hotbarCorner(w, h), v == 204f ? 0.81f : 0f, 1f);
            }
            case "textures/gui/icons.png" -> {                                 // #17 XP / jump bars
                if (rh != 5) return;
                if (v == 64f || v == 84f) {
                    ci.cancel();
                    AllGlass.capsule(ctx, x1 - 0.5f, y1 - 0.5f, x2 + 0.5f, y2 + 0.5f, 1f, 0f, 0.9f);
                    return;
                }
                int rgb;
                if (v == 69f) rgb = 0x30D158;
                else if (v == 89f) rgb = 0xFF9F0A;
                else if (v == 74f) rgb = 0x8E8E93;
                else return;
                ci.cancel();
                if (w > 0) AllGlass.scrim(ctx, x1, y1 + 0.5f, x2, y2 - 0.5f, (h - 1) / 2f, 0xF2000000 | rgb);
            }
            case "textures/gui/widgets.png" -> {                               // #20 spectator hotbar
                boolean bar = v == 0f && rw == 182 && rh == 22, sel = v == 22f && rw == 24 && rh == 22;
                if (!bar && !sel) return;
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player == null || !mc.player.isSpectator()) return;
                ci.cancel();
                float a = Math.max(0f, Math.min(1f, RenderSystem.getShaderColor()[3]));
                if (bar) HudGlass.glassBoxCtx(ctx, x1, y1, x2, y2, 0.85f * a);
                else AllGlass.capsule(ctx, x1 + 1, y1 + 1, x2 - 1, y2 - 1, AllGlass.hotbarCorner(w - 2, h - 2), 0.81f, a);
            }
            default -> { }
        }
    }
}
