package dev.s1mp1e.client.wings;

import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * The Ender Dragon's two wings, cut out of the vanilla dragon model (same boxes and UVs on {@code dragon.png}, javap'd
 * from {@code EnderDragonModel.createBodyLayer}: a 56-long bone with a 56×56 membrane, then a tip of the same length),
 * for the DragonWings cosmetic. Drawn at a fraction of their size by {@link DragonWingsLayer}.
 *
 * <p>The pose is not stored on the model: submits are drawn later, so every submit hands in its own immutable
 * {@link Pose} and {@link #setupAnim} applies it right before the draw.
 */
public final class DragonWingsModel extends Model<DragonWingsModel.Pose> {
    public static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/entity/enderdragon/dragon.png");

    /**
     * One frame of the wings. {@code phase}: flap angle (radians, advancing). {@code amplitude}: 0 = still, 1 = the
     * dragon's full stroke. {@code spread}: 0 = folded along the back, 1 = fully open.
     */
    public record Pose(float phase, float amplitude, float spread) {}

    private final ModelPart leftWing, leftTip, rightWing, rightTip;

    private DragonWingsModel(ModelPart root) {
        super(root, RenderTypes::entityCutout);   // the membrane is a zero-thickness plane: no back-face culling
        ModelPart wings = root.getChild("wings");
        this.leftWing = wings.getChild("left_wing");
        this.leftTip = leftWing.getChild("left_wing_tip");
        this.rightWing = wings.getChild("right_wing");
        this.rightTip = rightWing.getChild("right_wing_tip");
    }

    public static DragonWingsModel create() {
        return new DragonWingsModel(createLayer().bakeRoot());
    }

    /** Wing roots sit {@link #ROOT_GAP} dragon units either side of the spine, as on the dragon's back. */
    private static final float ROOT_GAP = 10.0F;

    private static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        PartDefinition wings = root.addOrReplaceChild("wings", CubeListBuilder.create(), PartPose.ZERO);
        PartDefinition left = wings.addOrReplaceChild("left_wing", CubeListBuilder.create()
                        .addBox("bone", 0.0F, -4.0F, -4.0F, 56, 8, 8, 112, 88)
                        .addBox("skin", 0.0F, 0.0F, 2.0F, 56, 0, 56, -56, 88),
                PartPose.offset(ROOT_GAP, 0.0F, 0.0F));
        left.addOrReplaceChild("left_wing_tip", CubeListBuilder.create()
                        .addBox("bone", 0.0F, -2.0F, -2.0F, 56, 4, 4, 112, 136)
                        .addBox("skin", 0.0F, 0.0F, 2.0F, 56, 0, 56, -56, 144),
                PartPose.offset(56.0F, 0.0F, 0.0F));
        PartDefinition right = wings.addOrReplaceChild("right_wing", CubeListBuilder.create()
                        .addBox("bone", -56.0F, -4.0F, -4.0F, 56, 8, 8, 112, 88)
                        .addBox("skin", -56.0F, 0.0F, 2.0F, 56, 0, 56, -56, 88),
                PartPose.offset(-ROOT_GAP, 0.0F, 0.0F));
        right.addOrReplaceChild("right_wing_tip", CubeListBuilder.create()
                        .addBox("bone", -56.0F, -2.0F, -2.0F, 56, 4, 4, 112, 136)
                        .addBox("skin", -56.0F, 0.0F, 2.0F, 56, 0, 56, -56, 144),
                PartPose.offset(-56.0F, 0.0F, 0.0F));
        return LayerDefinition.create(mesh, 256, 256);
    }

    /**
     * The dragon's own flap (EnderDragonModel.setupAnim: wing xRot 0.125 − cos f·0.2, zRot −(sin f + 0.125)·0.8, tip
     * zRot (sin(f+2) + 0.5)·0.75), scaled by the amplitude, blended with a folded pose — bones swept back along the
     * spine, tips tucked — by the spread. Right side mirrors the left.
     */
    @Override
    public void setupAnim(Pose p) {
        resetPose();
        float f = p.phase();
        float a = p.amplitude();
        float s = Mth.clamp(p.spread(), 0.0F, 1.0F);

        // open: the dragon's stroke around a slightly raised rest angle
        float openX = 0.125F - Mth.cos(f) * 0.2F * a;
        float openY = -0.25F;
        float openZ = -0.55F - (Mth.sin(f) + 0.125F) * 0.8F * a;
        float openTipZ = 0.25F + (Mth.sin(f + 2.0F) + 0.5F) * 0.75F * a;
        // folded: bones swept back (≈ 60°) and raised a little, tips folded back onto the bones
        float foldX = 0.1F, foldY = -1.1F, foldZ = -0.5F, foldTipZ = 0.0F;
        float foldTipY = -2.0F;

        leftWing.xRot = Mth.lerp(s, foldX, openX);
        leftWing.yRot = Mth.lerp(s, foldY, openY);
        leftWing.zRot = Mth.lerp(s, foldZ, openZ);
        leftTip.zRot = Mth.lerp(s, foldTipZ, openTipZ);
        leftTip.yRot = Mth.lerp(s, foldTipY, 0.0F);

        rightWing.xRot = leftWing.xRot;
        rightWing.yRot = -leftWing.yRot;
        rightWing.zRot = -leftWing.zRot;
        rightTip.zRot = -leftTip.zRot;
        rightTip.yRot = -leftTip.yRot;
    }
}
