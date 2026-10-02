package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.glass.render.HealthTrail;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Health damage trail ({@link HealthTrail}) — the 1.21.1 port of 26.2's {@code HeartTrailMixin}. 26.2 drove the trail
 * through the deferred {@code Hud.extractHearts}/{@code extractHeart}; 1.21.1 draws hearts immediately in
 * {@code InGameHud.renderHealthBar} (called once from {@code renderStatusBars}), whose params play the identical roles
 * ({@code lastHealth} = the "just lost" white-heart top, {@code blinking} = draw them at all).
 *
 * <p>Three matched hooks, verified against yarn 1.21.1+build.3 bytecode:
 * <ol>
 *   <li><b>{@code renderHealthBar} args</b> ({@link #s1mp1e$trailArgs}) — at the sole {@code renderHealthBar} INVOKE in
 *       {@code renderStatusBars}: feed the current {@code health} (arg 8) to {@link HealthTrail#update}, then set
 *       {@code lastHealth} (arg 7) to the trail top and {@code blinking} (arg 10) to whether it is active. Vanilla then
 *       draws white "lost" hearts across {@code [health, lastHealth)}; as the trail drains, that band shrinks smoothly
 *       instead of blinking on/off.</li>
 *   <li><b>Container blink off</b> ({@link #s1mp1e$noContainerBlink}) — the first {@code drawHeart} (ordinal 0, the
 *       heart CONTAINER) takes {@code blinking} at param index 5; force it {@code false} so the empty heart frames never
 *       flash white (the trail says it all). Byte-for-byte 26.2's {@code lg$noContainerBlink} (index 5).</li>
 *   <li><b>Ghost fade</b> ({@link #s1mp1e$ghostBegin}/{@link #s1mp1e$ghostEnd}) — the third {@code drawHeart}
 *       (ordinal 2, drawn only when {@code blinking && r < lastHealth}: the white ghost heart) is tinted to the trail's
 *       fading opacity. 26.2 used the deferred {@code GuiAlpha} stack (absent here), so instead the pending HUD batch is
 *       flushed, {@code RenderSystem.setShaderColor} carries the alpha through the ghost's own flush, then white is
 *       restored — the flush-around keeps the alpha off every other heart. Only runs during the brief damage window.</li>
 * </ol>
 */
@Mixin(InGameHud.class)
public abstract class HeartTrailMixin {

    /**
     * {@code renderHealthBar} args (verified in its bytecode — yarn's parameter names read the other way round):
     * 7 ({@code lastHealth}) is the CURRENT health, drawn as the normal hearts; 8 ({@code health}) is the display /
     * ghost top, drawn as the blinking hearts above it; 10 = blinking. The first port had 7 and 8 swapped: it fed the
     * trail into the NORMAL hearts, so after a hit the bar kept showing the old, full health until the trail drained.
     */
    @ModifyArgs(method = "renderStatusBars", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/hud/InGameHud;renderHealthBar(Lnet/minecraft/client/gui/DrawContext;"
                   + "Lnet/minecraft/entity/player/PlayerEntity;IIIIFIIIZ)V"))
    private void s1mp1e$trailArgs(Args args) {
        int health = args.get(7);                               // current health: left untouched
        HealthTrail.update(health);
        args.set(8, HealthTrail.displayHealth(health));         // ghost top
        args.set(10, HealthTrail.active(health));
    }

    /** Heart containers (1st drawHeart, ordinal 0): never the white "blinking" frame — the trail says it all. */
    @ModifyArg(method = "renderHealthBar", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/gui/hud/InGameHud;drawHeart(Lnet/minecraft/client/gui/DrawContext;"
                   + "Lnet/minecraft/client/gui/hud/InGameHud$HeartType;IIZZZ)V"),
            index = 5)
    private boolean s1mp1e$noContainerBlink(boolean blinking) {
        return false;
    }

    /** Before the white ghost heart (3rd drawHeart, ordinal 2): flush the committed hearts, then tint to the fade. */
    @Inject(method = "renderHealthBar", at = @At(value = "INVOKE", ordinal = 2,
            target = "Lnet/minecraft/client/gui/hud/InGameHud;drawHeart(Lnet/minecraft/client/gui/DrawContext;"
                   + "Lnet/minecraft/client/gui/hud/InGameHud$HeartType;IIZZZ)V"))
    private void s1mp1e$ghostBegin(DrawContext context, PlayerEntity player, int x, int y, int lines,
                                   int regeneratingHeartIndex, float maxHealth, int lastHealth, int health,
                                   int absorption, boolean blinking, CallbackInfo ci) {
        context.draw();   // commit everything drawn so far at full colour
        RenderSystem.setShaderColor(1f, 1f, 1f, HealthTrail.ghostAlpha(lastHealth));   // lastHealth = current health
    }

    @Inject(method = "renderHealthBar", at = @At(value = "INVOKE", ordinal = 2, shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/gui/hud/InGameHud;drawHeart(Lnet/minecraft/client/gui/DrawContext;"
                   + "Lnet/minecraft/client/gui/hud/InGameHud$HeartType;IIZZZ)V"))
    private void s1mp1e$ghostEnd(DrawContext context, PlayerEntity player, int x, int y, int lines,
                                 int regeneratingHeartIndex, float maxHealth, int lastHealth, int health,
                                 int absorption, boolean blinking, CallbackInfo ci) {
        context.draw();   // commit the ghost heart at the faded colour
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }
}
