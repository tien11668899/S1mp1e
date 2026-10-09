package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.NameTagGlass;
import dev.s1mp1e.client.module.NameTagModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityAttachmentType;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entity name-tag background plate: restyled, moved or removed per the {@code NameTags} module's
 * {@code Background} setting (Glass / Vanilla / Off).
 *
 * <p>Name tags are drawn in <b>world space</b>. 1.21.1's {@code EntityRenderer.renderLabelIfPresent} computes the plate
 * colour as {@code ((int)(getTextBackgroundOpacity(0.25f)*255)) << 24} (black at the options' background opacity) and
 * passes it as the {@code backgroundColor} arg (index 8) of {@code TextRenderer.draw(...)}.
 *
 * <ul>
 *   <li><b>Vanilla / Off</b> — the {@code @ModifyArg} hands that colour to {@link NameTagModule#plate(int)}, which keeps
 *       the vanilla alpha for Vanilla (identical presence / opacity / depth) and zeroes it for Off. Vanilla draws twice
 *       (a SEE_THROUGH pass with the plate and a NORMAL pass with {@code backgroundColor == 0}); the {@code a == 0}
 *       guard in {@code plate} leaves the second pass untouched.</li>
 *   <li><b>Glass</b> — the GUI-space SDF refraction pipeline samples a per-frame HUD backdrop grab, so the world-space
 *       plate has nothing to refract. The {@code @Inject} at HEAD reconstructs the label's pose exactly as the method
 *       does (push a copy of the top pose, translate to the NAME_TAG attachment, billboard-rotate, 0.025 scale), projects
 *       the plate rect to a GUI rect for {@link NameTagGlass}, then CANCELS the whole label — so the backdrop grab is
 *       clean terrain where {@link NameTagGlass#render} will draw the refractive glass plate + crisp text at the HUD
 *       stage. Cancelling at HEAD (before {@code matrices.push()}) keeps the matrix stack balanced.</li>
 * </ul>
 *
 * <p><b>Fair play.</b> This only re-skins, moves or hides the plate the game already draws. It does not change when or
 * where a name shows, does not touch depth / see-through-walls behaviour, and reads nothing about the entity beyond the
 * position vanilla itself uses to place the label. A zero-opacity plate (a discrete / no-plate name) is passed straight
 * through, and Glass mode skips it too, so no mode ever adds a plate where vanilla draws none.
 */
@Mixin(EntityRenderer.class)
public abstract class NameTagGlassMixin {

    /**
     * GLASS mode only: capture the label for the HUD-stage refraction pass and CANCEL the vanilla world-space label, so
     * the backdrop grab is clean terrain where the glass plate will refract. Runs at HEAD and reconstructs the label's
     * pose exactly as {@code renderLabelIfPresent} does below. Vanilla / Off modes fall through to the {@code @ModifyArg}.
     */
    @Inject(method = "renderLabelIfPresent", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$nameGlassCapture(Entity entity, Text text, MatrixStack matrices,
                                         net.minecraft.client.render.VertexConsumerProvider vertexConsumers,
                                         int light, float tickDelta, CallbackInfo ci) {
        if (!NameTagModule.glassMode()) return;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();

            // vanilla distance cull: beyond 64 blocks the label is not drawn — don't add a glass plate there either
            if (mc.getEntityRenderDispatcher().getSquaredDistanceToCamera(entity) > 4096.0) return;

            // vanilla plate alpha = (int)(getTextBackgroundOpacity(0.25) * 255) << 24; alpha 0 means no plate is drawn
            int plateAlpha = (int) (mc.options.getTextBackgroundOpacity(0.25f) * 255.0f);
            if (plateAlpha == 0) return;   // no plate -> leave the plateless name to vanilla

            // the NAME_TAG attachment point vanilla translates to (null -> vanilla also draws nothing)
            Vec3d off = entity.getAttachments().getPointNullable(EntityAttachmentType.NAME_TAG, 0, entity.getYaw(tickDelta));
            if (off == null) return;

            // reconstruct the label pose: copy the top (entity-relative) pose, translate -> billboard -> 0.025 scale
            Matrix4f pose = new Matrix4f(matrices.peek().getPositionMatrix());
            pose.translate((float) off.x, (float) (off.y + 0.5), (float) off.z);
            pose.rotate(mc.getEntityRenderDispatcher().getRotation());
            pose.scale(0.025f, -0.025f, 0.025f);

            float x = -mc.textRenderer.getWidth(text) / 2.0f;
            float y = "deadmau5".equals(text.getString()) ? -10f : 0f;

            // the name's colour is its own style (team / custom), defaulting to white like vanilla
            int rgb = 0xFFFFFF;
            TextColor tc = text.getStyle().getColor();
            if (tc != null) rgb = tc.getRgb();

            if (NameTagGlass.capture(pose, x, y, text, rgb)) {
                ci.cancel();   // drop the vanilla world-space label; the glass plate + text is drawn at the HUD stage
            }
        } catch (Throwable ignored) {
            // any failure: do nothing, let the vanilla label render normally (the @ModifyArg still re-skins it)
        }
    }

    @ModifyArg(
        method = "renderLabelIfPresent",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/font/TextRenderer;draw(Lnet/minecraft/text/Text;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/font/TextRenderer$TextLayerType;II)I"
        ),
        index = 8
    )
    private int s1mp1e$nameBackground(int backgroundColor) {
        // Vanilla = untouched translucent black (原封不動); Off = alpha cleared; Glass = cancelled before this runs.
        return NameTagModule.plate(backgroundColor);
    }
}
