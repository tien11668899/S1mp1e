package dev.s1mp1e.o.client.module;

import dev.s1mp1e.o.client.Module;
import dev.s1mp1e.o.client.Setting;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.vertex.BufferBuilder;
import net.minecraft.client.render.vertex.DefaultVertexFormat;
import net.minecraft.client.render.vertex.Tesselator;
import net.minecraft.client.render.world.WorldRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.living.LivingEntity;
import net.minecraft.entity.living.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.opengl.GL11;

/**
 * Hitbox options for vanilla's F3+B boxes: box colour and line width, the red eye-height plane and the blue look line
 * on or off, players only, and a red box while the entity is in its hurt time. Only changes how the boxes F3+B already
 * shows are drawn; nothing is shown that vanilla hides.
 *
 * <p>Replaces {@code EntityRenderDispatcher.renderHitbox} while on, with vanilla's geometry and GL state (depth writes
 * off, no texture, lighting or culling) and the line width restored to 1 afterwards.
 */
public final class HitboxModule extends Module {

    private static final int HURT_RGB = 0xFF3B30;

    public final Setting colour  = add(Setting.color("Box colour", 0xFFFFFFFF));
    public final Setting width   = add(Setting.number("Line width", 1.5D, 0.5D, 5.0D));
    public final Setting eye     = add(Setting.bool("Eye height", true));
    public final Setting look    = add(Setting.bool("Look direction", true));
    public final Setting players = add(Setting.bool("Players only", false));
    public final Setting hurtRed = add(Setting.bool("Red when hit", true));

    private static HitboxModule instance;

    public HitboxModule() {
        super("Hitboxes", "Visual");
        this.enabled = true;   // only changes the F3+B boxes, which are off until the player presses F3+B
        instance = this;
    }

    /**
     * Draws {@code e}'s box the configured way (dx..dz: its render offset from the camera).
     * @return false when the module is off and vanilla should draw
     */
    public static boolean render(Entity e, double dx, double dy, double dz, float tickDelta) {
        HitboxModule m = instance;
        if (m == null || !m.enabled) return false;
        if (m.players.boolValue && !(e instanceof PlayerEntity)) return true;

        int argb = m.colour.colorValue;
        if (m.hurtRed.boolValue && e instanceof LivingEntity && ((LivingEntity) e).damagedTimer > 0)
            argb = (argb & 0xFF000000) | HURT_RGB;
        int a = (argb >>> 24) & 0xFF, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        if (a == 0) return true;

        GlStateManager.depthMask(false);
        GlStateManager.disableTexture();
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        if (a < 255) {
            GlStateManager.enableBlend();
            GlStateManager.blendFuncSeparate(770, 771, 1, 0);
        } else {
            GlStateManager.disableBlend();
        }
        GL11.glLineWidth((float) m.width.doubleValue);
        try {
            Box box = e.getShape();
            WorldRenderer.renderOutlineShape(new Box(
                    box.minX - e.x + dx, box.minY - e.y + dy, box.minZ - e.z + dz,
                    box.maxX - e.x + dx, box.maxY - e.y + dy, box.maxZ - e.z + dz), r, g, b, a);
            if (m.eye.boolValue && e instanceof LivingEntity) {
                float f = e.width / 2.0F;
                double eyeY = dy + e.getEyeHeight();
                WorldRenderer.renderOutlineShape(new Box(dx - f, eyeY - 0.01F, dz - f, dx + f, eyeY + 0.01F, dz + f),
                        255, 0, 0, a);
            }
            if (m.look.boolValue) {
                Tesselator tesselator = Tesselator.getInstance();
                BufferBuilder buf = tesselator.getBuffer();
                Vec3d dir = e.getRotationVec(tickDelta);
                double eyeY = dy + e.getEyeHeight();
                buf.begin(3, DefaultVertexFormat.POSITION_COLOR);
                buf.vertex(dx, eyeY, dz).color(0, 0, 255, a).nextVertex();
                buf.vertex(dx + dir.x * 2.0, eyeY + dir.y * 2.0, dz + dir.z * 2.0).color(0, 0, 255, a).nextVertex();
                tesselator.end();
            }
        } finally {
            GL11.glLineWidth(1.0F);
            GlStateManager.enableTexture();
            GlStateManager.enableLighting();
            GlStateManager.enableCull();
            GlStateManager.disableBlend();
            GlStateManager.depthMask(true);
        }
        return true;
    }
}
