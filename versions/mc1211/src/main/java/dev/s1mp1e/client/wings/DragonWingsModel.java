package dev.s1mp1e.client.wings;

import net.minecraft.client.model.Model;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/**
 * The Ender Dragon's two wings, cut out of the vanilla dragon model (same boxes and UVs on {@code dragon.png}, as in
 * {@code EnderDragonEntityRenderer.getTexturedModelData}: a 56-long bone with a 56×56 membrane, then a tip of the same
 * length), for the DragonWings cosmetic. Drawn at a fraction of their size by {@link DragonWingsFeature}.
 *
 * <p>1.21.1 draws immediately, so the pose is set with {@link #setupAnim} right before {@link #render}.
 */
public final class DragonWingsModel extends Model {
    public static final Identifier TEXTURE = Identifier.ofVanilla("textures/entity/enderdragon/dragon.png");

    /**
     * One frame of the wings. {@code phase}: flap angle (radians, advancing). {@code amplitude}: 0 = still, 1 = the
     * dragon's full stroke. {@code spread}: 0 = folded along the back, 1 = fully open.
     */
    public record Pose(float phase, float amplitude, float spread) {}

    private final ModelPart root, leftWing, leftTip, rightWing, rightTip;

    private DragonWingsModel(ModelPart root) {
        super(RenderLayer::getEntityCutoutNoCull);   // the membrane is a zero-thickness plane: no back-face culling
        this.root = root;
        ModelPart wings = root.getChild("wings");
        this.leftWing = wings.getChild("left_wing");
        this.leftTip = leftWing.getChild("left_wing_tip");
        this.rightWing = wings.getChild("right_wing");
        this.rightTip = rightWing.getChild("right_wing_tip");
    }

    public static DragonWingsModel create() {
        return new DragonWingsModel(createLayer().createModel());
    }

    /** Wing roots sit {@link #ROOT_GAP} dragon units either side of the spine, as on the dragon's back. */
    private static final float ROOT_GAP = 10.0F;

    private static TexturedModelData createLayer() {
        ModelData mesh = new ModelData();
        ModelPartData root = mesh.getRoot();
        ModelPartData wings = root.addChild("wings", ModelPartBuilder.create(), ModelTransform.NONE);
        ModelPartData left = wings.addChild("left_wing", ModelPartBuilder.create()
                        .cuboid("bone", 0.0F, -4.0F, -4.0F, 56, 8, 8, 112, 88)
                        .cuboid("skin", 0.0F, 0.0F, 2.0F, 56, 0, 56, -56, 88),
                ModelTransform.pivot(ROOT_GAP, 0.0F, 0.0F));
        left.addChild("left_wing_tip", ModelPartBuilder.create()
                        .cuboid("bone", 0.0F, -2.0F, -2.0F, 56, 4, 4, 112, 136)
                        .cuboid("skin", 0.0F, 0.0F, 2.0F, 56, 0, 56, -56, 144),
                ModelTransform.pivot(56.0F, 0.0F, 0.0F));
        ModelPartData right = wings.addChild("right_wing", ModelPartBuilder.create()
                        .cuboid("bone", -56.0F, -4.0F, -4.0F, 56, 8, 8, 112, 88)
                        .cuboid("skin", -56.0F, 0.0F, 2.0F, 56, 0, 56, -56, 88),
                ModelTransform.pivot(-ROOT_GAP, 0.0F, 0.0F));
        right.addChild("right_wing_tip", ModelPartBuilder.create()
                        .cuboid("bone", -56.0F, -2.0F, -2.0F, 56, 4, 4, 112, 136)
                        .cuboid("skin", -56.0F, 0.0F, 2.0F, 56, 0, 56, -56, 144),
                ModelTransform.pivot(-56.0F, 0.0F, 0.0F));
        return TexturedModelData.of(mesh, 256, 256);
    }

    /**
     * The dragon's own flap (wing pitch 0.125 − cos f·0.2, roll −(sin f + 0.125)·0.8, tip roll (sin(f+2) + 0.5)·0.75),
     * scaled by the amplitude, blended with a folded pose — bones swept back along the spine, tips tucked — by the
     * spread. Right side mirrors the left.
     */
    public void setupAnim(Pose p) {
        root.traverse().forEach(ModelPart::resetTransform);
        float f = p.phase();
        float a = p.amplitude();
        float s = MathHelper.clamp(p.spread(), 0.0F, 1.0F);

        // open: the dragon's stroke around a slightly raised rest angle
        float openX = 0.125F - MathHelper.cos(f) * 0.2F * a;
        float openY = -0.25F;
        float openZ = -0.55F - (MathHelper.sin(f) + 0.125F) * 0.8F * a;
        float openTipZ = 0.25F + (MathHelper.sin(f + 2.0F) + 0.5F) * 0.75F * a;
        // folded: bones swept back (≈ 60°) and raised a little, tips folded back onto the bones
        float foldX = 0.1F, foldY = -1.1F, foldZ = -0.5F, foldTipZ = 0.0F;
        float foldTipY = -2.0F;

        leftWing.pitch = MathHelper.lerp(s, foldX, openX);
        leftWing.yaw = MathHelper.lerp(s, foldY, openY);
        leftWing.roll = MathHelper.lerp(s, foldZ, openZ);
        leftTip.roll = MathHelper.lerp(s, foldTipZ, openTipZ);
        leftTip.yaw = MathHelper.lerp(s, foldTipY, 0.0F);

        rightWing.pitch = leftWing.pitch;
        rightWing.yaw = -leftWing.yaw;
        rightWing.roll = -leftWing.roll;
        rightTip.roll = -leftTip.roll;
        rightTip.yaw = -leftTip.yaw;
    }

    @Override
    public void render(MatrixStack matrices, VertexConsumer vertices, int light, int overlay, int color) {
        root.render(matrices, vertices, light, overlay, color);
    }
}
