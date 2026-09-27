package dev.s1mp1e.client.module;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.potion.Potion;
import net.minecraftforge.client.event.RenderBlockOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Lowers and fades the first-person fire overlay while burning ("low fire"). 1.8.9 (Forge) port: the fire overlay is
 * {@code ItemRenderer.renderFireInFirstPerson}, fired to Forge as {@code RenderBlockOverlayEvent(OverlayType.FIRE)}. On
 * an active adjustment this cancels the vanilla overlay and redraws the same two flame quads (via 1.8.9's
 * {@code WorldRenderer}) with a downward shift ({@code Fire lower}, in vanilla's own -0.3 screen-effect units), a scale
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
        Minecraft mc = Minecraft.getMinecraft();
        return mc.thePlayer != null && mc.thePlayer.isPotionActive(Potion.fireResistance);
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

    /** Vanilla {@code ItemRenderer.renderFireInFirstPerson}, reproduced with the lower / scale / alpha adjustments. */
    private static void redrawFire(float drop, float scale, float opacity) {
        Minecraft mc = Minecraft.getMinecraft();
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 0.9F * opacity);
        GlStateManager.depthFunc(519);
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        float f = 1.0F;
        for (int i = 0; i < 2; ++i) {
            GlStateManager.pushMatrix();
            TextureAtlasSprite sprite = mc.getTextureMapBlocks().getAtlasSprite("minecraft:blocks/fire_layer_1");
            mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
            float u1 = sprite.getMinU(), u2 = sprite.getMaxU(), v1 = sprite.getMinV(), v2 = sprite.getMaxV();
            float x1 = (0.0F - f) / 2.0F, x2 = x1 + f, y1 = 0.0F - f / 2.0F, y2 = y1 + f, z = -0.5F;
            GlStateManager.translate((float) (-(i * 2 - 1)) * 0.24F, -0.3F - drop, 0.0F);
            GlStateManager.rotate((float) (i * 2 - 1) * 10.0F, 0.0F, 1.0F, 0.0F);
            GlStateManager.scale(scale, scale, 1.0F);
            wr.begin(7, DefaultVertexFormats.POSITION_TEX);
            wr.pos((double) x1, (double) y1, (double) z).tex((double) u2, (double) v2).endVertex();
            wr.pos((double) x2, (double) y1, (double) z).tex((double) u1, (double) v2).endVertex();
            wr.pos((double) x2, (double) y2, (double) z).tex((double) u1, (double) v1).endVertex();
            wr.pos((double) x1, (double) y2, (double) z).tex((double) u2, (double) v1).endVertex();
            tessellator.draw();
            GlStateManager.popMatrix();
        }
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableBlend();
        GlStateManager.depthMask(true);
        GlStateManager.depthFunc(515);
    }
}
