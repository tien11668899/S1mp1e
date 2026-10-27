package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.client.PackSky;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.platform.Lighting;
import net.minecraft.client.render.texture.TextureManager;
import net.minecraft.client.render.vertex.BufferBuilder;
import net.minecraft.client.render.vertex.DefaultVertexFormat;
import net.minecraft.client.render.vertex.Tesselator;
import net.minecraft.client.render.world.WorldRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.resource.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A resource pack's sky box ({@link PackSky#skybox}) in place of the overworld's sky dome, sunrise glow, stars and
 * void: the six faces of a Bedrock overworld cubemap, unfogged and as bright at night as by day, the way Bedrock draws
 * them, a little darker in rain and thunder. The sun and the moon still cross it, drawn as vanilla draws them. The
 * Nether and the End keep their own skies.
 */
@Mixin(WorldRenderer.class)
public abstract class PackSkyboxMixin {
    /** Half the cube's side: its corners (87 blocks away) stay inside the sky pass's far plane (2 × render distance ≥ 128). */
    private static final float S1MP1E$HALF = 50.0F;

    @Shadow @Final private TextureManager textureManager;
    @Shadow private ClientWorld world;
    @Shadow @Final private static Identifier SUN_LOCATION;
    @Shadow @Final private static Identifier MOON_PHASES_LOCATION;

    @Inject(method = "renderSky(FI)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$skybox(float tickDelta, int anaglyphRenderPass, CallbackInfo ci) {
        if (!PackSky.active || !PackSky.skybox || world == null || !world.dimension.isNatural()) return;
        ci.cancel();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buf = tesselator.getBuffer();
        GlStateManager.disableFog();
        GlStateManager.disableAlphaTest();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture();
        GlStateManager.depthMask(false);
        GlStateManager.disableCull();   // seen from inside, the panorama's quads face away
        Lighting.turnOff();
        float b = (1.0F - world.getRain(tickDelta) * 0.3F) * (1.0F - world.getThunder(tickDelta) * 0.5F);
        GlStateManager.color4f(b, b, b, 1.0F);
        float s = S1MP1E$HALF;
        for (int k = 0; k < 6; k++) {
            textureManager.bind(PackSky.SKYBOX[k]);
            GlStateManager.pushMatrix();
            // The title-screen panorama's transforms: face 0 ahead along -Z (north), 1 east, 2 south, 3 west, 4 up, 5 down.
            GlStateManager.rotatef(180.0F, 1.0F, 0.0F, 0.0F);
            if (k == 1) GlStateManager.rotatef(90.0F, 0.0F, 1.0F, 0.0F);
            else if (k == 2) GlStateManager.rotatef(180.0F, 0.0F, 1.0F, 0.0F);
            else if (k == 3) GlStateManager.rotatef(-90.0F, 0.0F, 1.0F, 0.0F);
            else if (k == 4) GlStateManager.rotatef(90.0F, 1.0F, 0.0F, 0.0F);
            else if (k == 5) GlStateManager.rotatef(-90.0F, 1.0F, 0.0F, 0.0F);
            buf.begin(7, DefaultVertexFormat.POSITION_TEX);
            buf.vertex(-s, -s, s).texture(0.0, 0.0).nextVertex();
            buf.vertex(s, -s, s).texture(1.0, 0.0).nextVertex();
            buf.vertex(s, s, s).texture(1.0, 1.0).nextVertex();
            buf.vertex(-s, s, s).texture(0.0, 1.0).nextVertex();
            tesselator.end();
            GlStateManager.popMatrix();
        }
        GlStateManager.enableCull();

        // The sun and the moon as vanilla draws them: additive, faded by rain.
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(770, 1, 1, 0);
        GlStateManager.pushMatrix();
        GlStateManager.color4f(1.0F, 1.0F, 1.0F, 1.0F - world.getRain(tickDelta));
        GlStateManager.rotatef(-90.0F, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotatef(world.getTimeOfDay(tickDelta) * 360.0F, 1.0F, 0.0F, 0.0F);
        float m = 30.0F;
        textureManager.bind(SUN_LOCATION);
        buf.begin(7, DefaultVertexFormat.POSITION_TEX);
        buf.vertex(-m, 100.0, -m).texture(0.0, 0.0).nextVertex();
        buf.vertex(m, 100.0, -m).texture(1.0, 0.0).nextVertex();
        buf.vertex(m, 100.0, m).texture(1.0, 1.0).nextVertex();
        buf.vertex(-m, 100.0, m).texture(0.0, 1.0).nextVertex();
        tesselator.end();
        m = 20.0F;
        textureManager.bind(MOON_PHASES_LOCATION);
        int phase = world.getMoonPhase();
        float u0 = (phase % 4) / 4.0F, v0 = (phase / 4 % 2) / 2.0F, u1 = (phase % 4 + 1) / 4.0F, v1 = (phase / 4 % 2 + 1) / 2.0F;
        buf.begin(7, DefaultVertexFormat.POSITION_TEX);
        buf.vertex(-m, -100.0, m).texture(u1, v1).nextVertex();
        buf.vertex(m, -100.0, m).texture(u0, v1).nextVertex();
        buf.vertex(m, -100.0, -m).texture(u0, v0).nextVertex();
        buf.vertex(-m, -100.0, -m).texture(u1, v0).nextVertex();
        tesselator.end();
        GlStateManager.popMatrix();

        GlStateManager.color4f(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableBlend();
        GlStateManager.enableAlphaTest();
        GlStateManager.enableFog();
        GlStateManager.depthMask(true);
    }
}
