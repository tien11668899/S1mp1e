package dev.s1mp1e.o.client.module;

import dev.s1mp1e.o.client.Module;
import dev.s1mp1e.o.client.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.vertex.Tesselator;
import net.minecraft.client.render.vertex.BufferBuilder;
import net.minecraft.client.render.texture.TextureAtlasSprite;
import net.minecraft.client.render.texture.TextureAtlas;
import net.minecraft.client.render.vertex.DefaultVertexFormat;
import net.minecraft.entity.living.effect.StatusEffect;
import dev.s1mp1e.o.event.RenderBlockOverlayEvent;
import dev.s1mp1e.o.event.MinecraftForge;
import dev.s1mp1e.o.event.EventPriority;
import dev.s1mp1e.o.event.SubscribeEvent;

/**
 * Lowers and fades the first-person fire overlay while burning ("low fire"). 1.8.9 (Forge) port: the fire overlay is
 * {@code ItemInHandRenderer.renderOnFireEffect}, fired to Forge as {@code RenderBlockOverlayEvent(OverlayType.FIRE)}. On
 * an active adjustment this cancels the vanilla overlay and redraws the same two flame quads (via 1.8.9's
 * {@code BufferBuilder}) with a downward shift ({@code Fire lower}, in vanilla's own -0.3 screen-effect units), a scale
 * ({@code Fire size}) and a scaled alpha ({@code Fire opacity}); "Hide fire" just cancels it.
 *
 * <p>FAIR-PLAY: purely the on-screen fire overlay's look. It never changes the burning state, damage or timing.
 */
public final class LowFireModule extends Module {
    public final Setting lower       = add(Setting.number("Fire lower", 0.3D, 0.0D, 0.6D));
    public final Setting opacity     = add(Setting.number("Fire opacity", 0.6D, 0.1D, 1.0D));
    public final Setting size        = add(Setting.number("Fire size", 1.0D, 0.4D, 1.0D));
    public final Setting hideAll     = add(Setting.bool("Hide fire", false));
    public final Setting hideFireRes = add(Setting.bool("Hide with Fire Res", false));

    private static LowFireModule instance;

    public LowFireModule() {
        super("LowFire", "Combat");
        this.enabled = true;
        instance = this;
    }

    @Override public void onEnable()  { MinecraftForge.EVENT_BUS.register(this); }
    @Override public void onDisable() { MinecraftForge.EVENT_BUS.unregister(this); }

    private boolean hidden() {
        if (hideAll.boolValue) return true;
        if (!hideFireRes.boolValue) return false;
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.hasStatusEffect(StatusEffect.FIRE_RESISTANCE);
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public void onFireOverlay(RenderBlockOverlayEvent e) {
        if (!enabled || e.overlayType != RenderBlockOverlayEvent.OverlayType.FIRE) return;
        try {
            if (hidden()) { e.setCanceled(true); return; }
            float drop = (float) lower.doubleValue;
            float sz = (float) size.doubleValue;
            float op = (float) opacity.doubleValue;
            if (drop <= 0.0f && sz >= 0.999f && op >= 0.999f) return;   // nothing to change -> vanilla draws it
            e.setCanceled(true);
            redrawFire(drop, sz, op);
        } catch (Throwable t) {
            // never-throw
        }
    }

    /** Vanilla {@code ItemInHandRenderer.renderOnFireEffect}, reproduced with the lower / scale / alpha adjustments. */
    private static void redrawFire(float drop, float scale, float opacity) {
        Minecraft mc = Minecraft.getInstance();
        Tesselator tessellator = Tesselator.getInstance();
        BufferBuilder wr = tessellator.getBuffer();
        GlStateManager.color4f(1.0F, 1.0F, 1.0F, 0.9F * opacity);
        GlStateManager.depthFunc(519);
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);
        float f = 1.0F;
        for (int i = 0; i < 2; ++i) {
            GlStateManager.pushMatrix();
            TextureAtlasSprite sprite = mc.getBlocksAtlas().getSprite("minecraft:blocks/fire_layer_1");
            mc.getTextureManager().bind(TextureAtlas.BLOCKS_LOCATION);
            float u1 = sprite.getUMin(), u2 = sprite.getUMax(), v1 = sprite.getVMin(), v2 = sprite.getVMax();
            float x1 = (0.0F - f) / 2.0F, x2 = x1 + f, y1 = 0.0F - f / 2.0F, y2 = y1 + f, z = -0.5F;
            GlStateManager.translatef((float) (-(i * 2 - 1)) * 0.24F, -0.3F - drop, 0.0F);
            GlStateManager.rotatef((float) (i * 2 - 1) * 10.0F, 0.0F, 1.0F, 0.0F);
            GlStateManager.scalef(scale, scale, 1.0F);
            wr.begin(7, DefaultVertexFormat.POSITION_TEX);
            wr.vertex((double) x1, (double) y1, (double) z).texture((double) u2, (double) v2).nextVertex();
            wr.vertex((double) x2, (double) y1, (double) z).texture((double) u1, (double) v2).nextVertex();
            wr.vertex((double) x2, (double) y2, (double) z).texture((double) u1, (double) v1).nextVertex();
            wr.vertex((double) x1, (double) y2, (double) z).texture((double) u2, (double) v1).nextVertex();
            tessellator.end();
            GlStateManager.popMatrix();
        }
        GlStateManager.color4f(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableBlend();
        GlStateManager.depthMask(true);
        GlStateManager.depthFunc(515);
    }
}
