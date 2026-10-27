package dev.s1mp1e.o.wings;

import net.minecraft.client.render.model.Model;
import net.minecraft.client.render.model.ModelPart;
import net.minecraft.resource.Identifier;
import net.minecraft.util.math.MathHelper;

/**
 * The Ender Dragon's two wings, cut out of the vanilla dragon model (same boxes and UVs on {@code dragon.png} as
 * 1.8.9's {@code EnderDragonModel}: a 56-long bone with a 56×56 membrane, then a tip of the same length), for the
 * DragonWings cosmetic (ported from 26.3). Drawn at a fraction of their size by {@link DragonWingsLayer}.
 */
public final class DragonWingsModel extends Model {
    public static final Identifier TEXTURE = new Identifier("textures/entity/enderdragon/dragon.png");

    /** Wing roots sit this many dragon units either side of the spine, as on the dragon's back. */
    private static final float ROOT_GAP = 10.0F;

    private final ModelPart leftWing, leftTip, rightWing, rightTip;

    public DragonWingsModel() {
        this.textureWidth = 256;
        this.textureHeight = 256;
        for (String side : new String[]{"l", "r"}) {
            this.setTexturePos(side + "wing.bone", 112, 88);
            this.setTexturePos(side + "wing.skin", -56, 88);
            this.setTexturePos(side + "wingtip.bone", 112, 136);
            this.setTexturePos(side + "wingtip.skin", -56, 144);
        }
        this.leftWing = new ModelPart(this, "lwing");
        this.leftWing.setPos(ROOT_GAP, 0.0F, 0.0F);
        this.leftWing.addBox("bone", 0.0F, -4.0F, -4.0F, 56, 8, 8);
        this.leftWing.addBox("skin", 0.0F, 0.0F, 2.0F, 56, 0, 56);
        this.leftTip = new ModelPart(this, "lwingtip");
        this.leftTip.setPos(56.0F, 0.0F, 0.0F);
        this.leftTip.addBox("bone", 0.0F, -2.0F, -2.0F, 56, 4, 4);
        this.leftTip.addBox("skin", 0.0F, 0.0F, 2.0F, 56, 0, 56);
        this.leftWing.addChild(this.leftTip);

        this.rightWing = new ModelPart(this, "rwing");
        this.rightWing.setPos(-ROOT_GAP, 0.0F, 0.0F);
        this.rightWing.addBox("bone", -56.0F, -4.0F, -4.0F, 56, 8, 8);
        this.rightWing.addBox("skin", -56.0F, 0.0F, 2.0F, 56, 0, 56);
        this.rightTip = new ModelPart(this, "rwingtip");
        this.rightTip.setPos(-56.0F, 0.0F, 0.0F);
        this.rightTip.addBox("bone", -56.0F, -2.0F, -2.0F, 56, 4, 4);
        this.rightTip.addBox("skin", -56.0F, 0.0F, 2.0F, 56, 0, 56);
        this.rightWing.addChild(this.rightTip);
    }

    /**
     * The dragon's own flap (wing xRot 0.125 − cos f·0.2, zRot −(sin f + 0.125)·0.8, tip zRot (sin(f+2) + 0.5)·0.75),
     * scaled by {@code amplitude}, blended with a folded pose — bones swept back along the spine, tips tucked — by
     * {@code spread}. The right side mirrors the left. Same numbers as 26.3.
     */
    public void setupAnim(float phase, float amplitude, float spread) {
        float f = phase, a = amplitude, s = MathHelper.clamp(spread, 0.0F, 1.0F);
        // open: the dragon's stroke around a slightly raised rest angle
        float openX = 0.125F - MathHelper.cos(f) * 0.2F * a;
        float openY = -0.25F;
        float openZ = -0.55F - (MathHelper.sin(f) + 0.125F) * 0.8F * a;
        float openTipZ = 0.25F + (MathHelper.sin(f + 2.0F) + 0.5F) * 0.75F * a;
        // folded: bones swept back (about 60 degrees) and raised a little, tips folded back onto the bones
        float foldX = 0.1F, foldY = -1.1F, foldZ = -0.5F, foldTipZ = 0.0F, foldTipY = -2.0F;

        this.leftWing.rotationX = lerp(s, foldX, openX);
        this.leftWing.rotationY = lerp(s, foldY, openY);
        this.leftWing.rotationZ = lerp(s, foldZ, openZ);
        this.leftTip.rotationZ = lerp(s, foldTipZ, openTipZ);
        this.leftTip.rotationY = lerp(s, foldTipY, 0.0F);

        this.rightWing.rotationX = this.leftWing.rotationX;
        this.rightWing.rotationY = -this.leftWing.rotationY;
        this.rightWing.rotationZ = -this.leftWing.rotationZ;
        this.rightTip.rotationZ = -this.leftTip.rotationZ;
        this.rightTip.rotationY = -this.leftTip.rotationY;
    }

    public void renderWings(float scale) {
        this.leftWing.render(scale);
        this.rightWing.render(scale);
    }

    private static float lerp(float t, float a, float b) {
        return a + (b - a) * t;
    }
}
