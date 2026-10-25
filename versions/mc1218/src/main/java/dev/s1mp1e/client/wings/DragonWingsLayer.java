package dev.s1mp1e.client.wings;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.s1mp1e.client.module.DragonWingsModule;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * Draws {@link DragonWingsModel} on the local player's back while the DragonWings module is on (the cape layer stands
 * down at the same time). Attached to every {@code PlayerRenderer} by {@code AvatarRendererWingsMixin}; for anyone but
 * the local player it draws nothing.
 *
 * <p>1.21.6-1.21.8 port of mc262: the layer renders straight into the buffer source (26.x submits the model to a
 * collector, which poses it from the state).
 */
public final class DragonWingsLayer extends RenderLayer<PlayerRenderState, PlayerModel> {
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

    public DragonWingsLayer(RenderLayerParent<PlayerRenderState, PlayerModel> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffers, int light, PlayerRenderState state,
                       float yRot, float xRot) {
        if (!DragonWingsModule.showsOn(state)) return;
        poseStack.pushPose();
        // follow the body (it leans when sneaking and swings with the walk), then sit on the upper back
        getParentModel().body.translateAndRotate(poseStack);
        // just outside a chestplate (armor stands about a pixel off the body), between the shoulder blades
        poseStack.translate(0.0F, 3.0F / 16.0F, 3.4F / 16.0F);
        poseStack.mulPose(com.mojang.math.Axis.XP.rotation(TILT));
        float s = BASE_SCALE * DragonWingsModule.scale();
        poseStack.scale(s, s, s);
        model.setupAnim(DragonWingsModule.pose(state));
        model.renderToBuffer(poseStack, buffers.getBuffer(model.renderType(DragonWingsModel.TEXTURE)), light,
                OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
    }
}
