package dev.s1mp1e.client.wings;

import dev.s1mp1e.client.module.DragonWingsModule;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;

/**
 * Draws {@link DragonWingsModel} on the local player's back while the DragonWings module is on (the cape feature stands
 * down at the same time). Attached to every {@code PlayerEntityRenderer} by {@code PlayerRendererWingsMixin}; for
 * anyone but the local player it draws nothing.
 */
public final class DragonWingsFeature
        extends FeatureRenderer<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> {
    /** Dragon units → blocks at size 1: each 112-unit wing comes out about 0.7 blocks long (span ≈ 1.5 blocks). */
    private static final float BASE_SCALE = 0.10F;
    /**
     * Tilt of the whole pair about the body's X axis. The dragon's wing is a bone (leading edge) with the membrane
     * trailing behind it (+z), lying flat on a flying body; tipping that frame by -62 degrees stands the bone on top and
     * hangs the membrane down behind the shoulders, so the beat swings back and forth behind the player like a bat's
     * wings. (Model space is y-down: a positive tilt would send the membrane up and the stroke toward the front.)
     */
    private static final float TILT = (float) Math.toRadians(-62.0D);

    private final DragonWingsModel model = DragonWingsModel.create();

    public DragonWingsFeature(FeatureRendererContext<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> context) {
        super(context);
    }

    @Override
    public void render(MatrixStack matrices, VertexConsumerProvider consumers, int light, AbstractClientPlayerEntity player,
                       float limbAngle, float limbDistance, float tickDelta, float animationProgress, float headYaw,
                       float headPitch) {
        if (!DragonWingsModule.showsOn(player)) return;
        matrices.push();
        // follow the body (it leans when sneaking and swings with the walk), then sit on the upper back
        getContextModel().body.rotate(matrices);
        // just outside a chestplate (armor stands about a pixel off the body), between the shoulder blades
        matrices.translate(0.0F, 3.0F / 16.0F, 3.4F / 16.0F);
        matrices.multiply(RotationAxis.POSITIVE_X.rotation(TILT));
        float s = BASE_SCALE * DragonWingsModule.scale();
        matrices.scale(s, s, s);
        model.setupAnim(DragonWingsModule.pose(player));
        model.render(matrices, consumers.getBuffer(model.getLayer(DragonWingsModel.TEXTURE)), light, OverlayTexture.DEFAULT_UV, 1f, 1f, 1f, 1f);
        matrices.pop();
    }
}
