package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.GuiAlpha;
import dev.s1mp1e.glass.render.HealthTrail;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Health damage trail ({@link HealthTrail}) — the 1.16.5 port of 26.2's {@code HeartTrailMixin}. 26.2 drove the trail
 * through the deferred {@code Hud.extractHearts}/{@code extractHeart}; 1.17+ has {@code InGameHud.renderHealthBar} with
 * the current and the displayed health as arguments. <b>1.16.5 has neither</b>: the hearts are drawn inline in
 * {@code renderStatusBars(MatrixStack)}, and the values the newer lines pass as arguments are its LOCALS.
 *
 * <p>javap of the 1.16.5 {@code InGameHud.renderStatusBars} (yarn 1.16.5+build.10):
 * <pre>
 *   local 3  int   i   = ceil(player.getHealth())        the CURRENT health -> the normal hearts
 *   local 4  bool  bl  = heart-jump blink phase            "blinking": white container frame + the ghost hearts
 *   local 7  int   j   = this.renderHealthValue            the DISPLAYED health -> the top of the white ghost hearts
 *   this.drawTexture(matrices, x, y, u, v, 9, 9) INVOKEs, in order:
 *     0..2  armour (full / half / empty)
 *     3     heart container            u = 16 + (bl ? 9 : 0)
 *     4     ghost heart, full          if (bl &amp;&amp; z*2+1 &lt;  j)
 *     5     ghost heart, half          if (bl &amp;&amp; z*2+1 == j)
 *     6, 7  absorption full / half
 *     8, 9  normal heart full / half   against i
 * </pre>
 * So the same three things the newer lines do, on those seams (mind which value is which — the first 1.21.1 port fed
 * the trail into the NORMAL hearts and the bar kept showing the old, full health; here {@code i} is never touched):
 * <ol>
 *   <li>{@code bl} (the store to local 4): {@link HealthTrail#update} with the current health, then "blinking" =
 *       the trail is active — the ghost hearts show for as long as the trail drains instead of flashing on and off;</li>
 *   <li>{@code j} (the store to local 7): the ghost top = the trail;</li>
 *   <li>the container blit (ordinal 3): never the white "blinking" frame ({@code u} = 16) — the trail says it all;</li>
 *   <li>the two ghost blits (ordinals 4, 5): inside a {@link GuiAlpha} scope, so the band fades as it drains.</li>
 * </ol>
 */
@Mixin(InGameHud.class)
public abstract class HeartTrailMixin {

    @Shadow private PlayerEntity getCameraPlayer() { throw new AssertionError(); }

    /** The current health this frame's bar is drawn for (set where {@code bl} is stored, the first of the hooks). */
    @Unique private int s1mp1e$health;

    @ModifyVariable(method = "renderStatusBars", at = @At(value = "STORE", ordinal = 0), index = 4)
    private boolean s1mp1e$trailBlink(boolean blinking) {
        PlayerEntity p = this.getCameraPlayer();
        if (p == null) return blinking;
        int health = MathHelper.ceil(p.getHealth());            // = local 3, the CURRENT health
        this.s1mp1e$health = health;
        HealthTrail.update(health);
        return HealthTrail.active(health);
    }

    @ModifyVariable(method = "renderStatusBars", at = @At(value = "STORE", ordinal = 0), index = 7)
    private int s1mp1e$trailTop(int displayed) {
        return HealthTrail.displayHealth(this.s1mp1e$health);   // ghost top
    }

    /** Heart containers (ordinal 3): never the white "blinking" frame. */
    @ModifyArg(method = "renderStatusBars", at = @At(value = "INVOKE", ordinal = 3,
            target = "Lnet/minecraft/client/gui/hud/InGameHud;drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"),
            index = 3)
    private int s1mp1e$noContainerBlink(int u) {
        return 16;
    }

    /** The white ghost hearts (ordinals 4 = full, 5 = half): faded with the trail. */
    @WrapOperation(method = "renderStatusBars", at = @At(value = "INVOKE", ordinal = 4,
            target = "Lnet/minecraft/client/gui/hud/InGameHud;drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$ghostFull(InGameHud self, MatrixStack matrices, int x, int y, int u, int v, int w, int h,
                                  Operation<Void> original) {
        s1mp1e$ghost(self, matrices, x, y, u, v, w, h, original);
    }

    @WrapOperation(method = "renderStatusBars", at = @At(value = "INVOKE", ordinal = 5,
            target = "Lnet/minecraft/client/gui/hud/InGameHud;drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$ghostHalf(InGameHud self, MatrixStack matrices, int x, int y, int u, int v, int w, int h,
                                  Operation<Void> original) {
        s1mp1e$ghost(self, matrices, x, y, u, v, w, h, original);
    }

    @Unique
    private void s1mp1e$ghost(InGameHud self, MatrixStack matrices, int x, int y, int u, int v, int w, int h,
                              Operation<Void> original) {
        float a = HealthTrail.ghostAlpha(this.s1mp1e$health);
        if (a >= 0.999f) {
            original.call(self, matrices, x, y, u, v, w, h);
            return;
        }
        GuiAlpha.push(matrices, a);
        try {
            original.call(self, matrices, x, y, u, v, w, h);
        } finally {
            GuiAlpha.pop(matrices);
        }
    }
}
