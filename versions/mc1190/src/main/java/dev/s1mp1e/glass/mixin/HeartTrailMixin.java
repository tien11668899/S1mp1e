package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.GuiAlpha;
import dev.s1mp1e.glass.render.HealthTrail;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Health damage trail ({@link HealthTrail}) — the 1.19.2 port of 26.2's {@code HeartTrailMixin}. 26.2 drove the trail
 * through the deferred {@code Hud.extractHearts}/{@code extractHeart}; 1.19.2 draws hearts immediately in
 * {@code InGameHud.renderHealthBar} (called once from {@code renderStatusBars}), whose params play the identical roles.
 *
 * <p>Three matched hooks, javap-verified on the 1.19.2 {@code InGameHud} ({@code renderHealthBar(MatrixStack,
 * PlayerEntity, IIIIFIIIZ)} — the same parameter order as 1.20.1 / 1.21.1; {@code drawHeart(MatrixStack, HeartType,
 * x, y, v, blinking, half)}, so its {@code blinking} is index 5):
 * <ol>
 *   <li><b>{@code renderHealthBar} args</b> ({@link #s1mp1e$trailArgs}) — at the sole {@code renderHealthBar} INVOKE in
 *       {@code renderStatusBars}: feed the CURRENT health to {@link HealthTrail#update}, then set the display / ghost
 *       top and {@code blinking}. Vanilla then draws white "lost" hearts across the band between them; as the trail
 *       drains, that band shrinks smoothly instead of blinking on/off.</li>
 *   <li><b>Container blink off</b> ({@link #s1mp1e$noContainerBlink}) — the first {@code drawHeart} (ordinal 0, the
 *       heart CONTAINER) takes {@code blinking} at param index 5; force it {@code false} so the empty heart frames never
 *       flash white (the trail says it all).</li>
 *   <li><b>Ghost fade</b> ({@link #s1mp1e$ghostBegin}/{@link #s1mp1e$ghostEnd}) — the third {@code drawHeart}
 *       (ordinal 2, drawn only for the white ghost heart) is drawn inside a {@link GuiAlpha} scope at the trail's
 *       fading opacity; hearts are immediate blits here, so the scope touches that one heart only.</li>
 * </ol>
 */
@Mixin(InGameHud.class)
public abstract class HeartTrailMixin {

    @Unique private boolean s1mp1e$ghostScope;

    /**
     * {@code renderHealthBar} args (verified in its bytecode on the newer lines — yarn's parameter names read the other
     * way round): 7 ({@code lastHealth}) is the CURRENT health, drawn as the normal hearts; 8 ({@code health}) is the
     * display / ghost top, drawn as the blinking hearts above it; 10 = blinking. (The first 1.21.1 port had 7 and 8
     * swapped: it fed the trail into the NORMAL hearts, so after a hit the bar kept showing the old, full health until
     * the trail drained.)
     */
    @ModifyArgs(method = "renderStatusBars", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/hud/InGameHud;renderHealthBar(Lnet/minecraft/client/util/math/MatrixStack;"
                   + "Lnet/minecraft/entity/player/PlayerEntity;IIIIFIIIZ)V"))
    private void s1mp1e$trailArgs(Args args) {
        int health = args.get(7);                               // current health: left untouched
        HealthTrail.update(health);
        args.set(8, HealthTrail.displayHealth(health));         // ghost top
        args.set(10, HealthTrail.active(health));
    }

    /** Heart containers (1st drawHeart, ordinal 0): never the white "blinking" frame — the trail says it all. */
    @ModifyArg(method = "renderHealthBar", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/gui/hud/InGameHud;drawHeart(Lnet/minecraft/client/util/math/MatrixStack;"
                   + "Lnet/minecraft/client/gui/hud/InGameHud$HeartType;IIIZZ)V"),
            index = 5)
    private boolean s1mp1e$noContainerBlink(boolean blinking) {
        return false;
    }

    /** Before the white ghost heart (3rd drawHeart, ordinal 2): open the fade scope. */
    @Inject(method = "renderHealthBar", at = @At(value = "INVOKE", ordinal = 2,
            target = "Lnet/minecraft/client/gui/hud/InGameHud;drawHeart(Lnet/minecraft/client/util/math/MatrixStack;"
                   + "Lnet/minecraft/client/gui/hud/InGameHud$HeartType;IIIZZ)V"))
    private void s1mp1e$ghostBegin(MatrixStack matrices, PlayerEntity player, int x, int y, int lines,
                                   int regeneratingHeartIndex, float maxHealth, int lastHealth, int health,
                                   int absorption, boolean blinking, CallbackInfo ci) {
        GuiAlpha.push(matrices, HealthTrail.ghostAlpha(lastHealth));   // lastHealth = current health
        s1mp1e$ghostScope = true;
    }

    @Inject(method = "renderHealthBar", at = @At(value = "INVOKE", ordinal = 2, shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/gui/hud/InGameHud;drawHeart(Lnet/minecraft/client/util/math/MatrixStack;"
                   + "Lnet/minecraft/client/gui/hud/InGameHud$HeartType;IIIZZ)V"))
    private void s1mp1e$ghostEnd(MatrixStack matrices, PlayerEntity player, int x, int y, int lines,
                                 int regeneratingHeartIndex, float maxHealth, int lastHealth, int health,
                                 int absorption, boolean blinking, CallbackInfo ci) {
        if (s1mp1e$ghostScope) {
            s1mp1e$ghostScope = false;
            GuiAlpha.pop(matrices);
        }
    }
}
