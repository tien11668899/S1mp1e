package dev.s1mp1e.o.wings;

import dev.s1mp1e.o.client.module.DragonWingsModule;
import net.minecraft.client.entity.living.player.ClientPlayerEntity;
import net.minecraft.client.render.entity.PlayerRenderer;
import net.minecraft.client.render.entity.layer.EntityRenderLayer;
import net.minecraft.client.render.platform.GlStateManager;

/**
 * Draws {@link DragonWingsModel} on the local player's back while the DragonWings module is on (the cape layer stands
 * down at the same time). Added to both player renderers by {@code PlayerRendererWingsMixin}; for anyone but the local
 * player it draws nothing. Ported from 26.3: same placement, tilt and scale.
 */
public final class DragonWingsLayer implements EntityRenderLayer<ClientPlayerEntity> {
    /** Dragon units → blocks at size 1: each 112-unit wing comes out about 0.7 blocks long (span ≈ 1.5 blocks). */
    private static final float BASE_SCALE = 0.10F;
    /**
     * Tilt of the whole pair about the body's X axis: the bone (leading edge) stands on top and the membrane hangs down
     * behind the shoulders, so the beat swings back and forth behind the player like a bat's wings.
     */
    private static final float TILT_DEG = -62.0F;

    private final PlayerRenderer parent;
    private final DragonWingsModel model = new DragonWingsModel();

    public DragonWingsLayer(PlayerRenderer parent) {
        this.parent = parent;
    }

    @Override
    public void render(ClientPlayerEntity player, float limbAngle, float limbDistance, float tickDelta, float age,
                       float headYaw, float headPitch, float scale) {
        if (!DragonWingsModule.showsOn(player)) return;
        float[] pose = DragonWingsModule.pose(player);
        this.model.setupAnim(pose[0], pose[1], pose[2]);
        GlStateManager.color4f(1.0F, 1.0F, 1.0F, 1.0F);
        this.parent.bindTexture(DragonWingsModel.TEXTURE);
        GlStateManager.pushMatrix();
        // follow the body (it leans when sneaking and swings with the walk), then sit on the upper back,
        // just outside a chestplate, between the shoulder blades
        this.parent.getModel().body.transform(scale);
        GlStateManager.translatef(0.0F, 3.0F / 16.0F, 3.4F / 16.0F);
        GlStateManager.rotatef(TILT_DEG, 1.0F, 0.0F, 0.0F);
        float s = BASE_SCALE * DragonWingsModule.scale();
        GlStateManager.scalef(s, s, s);
        this.model.renderWings(scale);
        GlStateManager.popMatrix();
    }

    @Override
    public boolean colorsWhenDamaged() {
        return false;
    }
}
