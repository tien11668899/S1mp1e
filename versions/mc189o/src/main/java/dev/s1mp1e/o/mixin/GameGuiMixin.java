package dev.s1mp1e.o.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.o.event.MinecraftForge;
import dev.s1mp1e.o.event.RenderGameOverlayEvent;
import dev.s1mp1e.o.event.RenderGameOverlayEvent.ElementType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GameGui;
import net.minecraft.client.gui.chat.ChatGui;
import net.minecraft.client.gui.overlay.PlayerTabOverlay;
import net.minecraft.client.render.Window;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在原版 HUD（GameGui.render）裡重現 Forge GuiIngameForge 的元素事件：每段子呼叫前發
 * {@code RenderGameOverlayEvent.Pre}（被取消＝跳過原版那段）、之後發 {@code Post}。用 {@code @WrapOperation}
 * 而不是 {@code @Redirect}，才能和 Argentum 等其他模組對同一呼叫的修改疊加。
 *
 * <p>和 Forge 的差異：原版把血/盔甲/飢餓/空氣在同一個 renderStatusBars 裡畫，這裡把整段包在
 * Pre/Post(HEALTH) 之間，再補發 ARMOR/FOOD/AIR 的 Pre/Post（中間沒有原版繪製）。GlassHudHandler 對這四個
 * 元素做的是「Pre 推一層上移矩陣、Post 還原」，所以結果等價。
 */
@Mixin(value = GameGui.class, priority = 1100)
public abstract class GameGuiMixin {

    @Unique private RenderGameOverlayEvent s1mp1e$parent;

    @Unique
    private boolean s1mp1e$pre(ElementType t) {
        RenderGameOverlayEvent p = s1mp1e$parent;
        return p != null && MinecraftForge.EVENT_BUS.post(new RenderGameOverlayEvent.Pre(p, t));
    }

    @Unique
    private void s1mp1e$post(ElementType t) {
        RenderGameOverlayEvent p = s1mp1e$parent;
        if (p != null) MinecraftForge.EVENT_BUS.post(new RenderGameOverlayEvent.Post(p, t));
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$head(float tickDelta, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        Window w = new Window(mc);
        int mx = Mouse.getX() * w.getWidth() / Math.max(1, mc.width);
        int my = w.getHeight() - Mouse.getY() * w.getHeight() / Math.max(1, mc.height) - 1;
        s1mp1e$parent = new RenderGameOverlayEvent(tickDelta, w, mx, my);
        if (MinecraftForge.EVENT_BUS.post(new RenderGameOverlayEvent.Pre(s1mp1e$parent, ElementType.ALL))) {
            s1mp1e$parent = null;
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void s1mp1e$tail(float tickDelta, CallbackInfo ci) {
        s1mp1e$post(ElementType.ALL);
        s1mp1e$parent = null;
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GameGui;renderHotbar(Lnet/minecraft/client/render/Window;F)V"))
    private void s1mp1e$hotbar(GameGui self, Window w, float td, Operation<Void> op) {
        if (!s1mp1e$pre(ElementType.HOTBAR)) op.call(self, w, td);
        s1mp1e$post(ElementType.HOTBAR);
    }

    // 準星：Forge 不論 showCrosshair 都發 Pre(CROSSHAIRS)；hasCrosshair() 每幀必呼叫一次，包在這裡最穩
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GameGui;hasCrosshair()Z"))
    private boolean s1mp1e$crosshair(GameGui self, Operation<Boolean> op) {
        boolean show = op.call(self);
        if (s1mp1e$pre(ElementType.CROSSHAIRS)) return false;
        return show;
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GameGui;renderBossBars()V"))
    private void s1mp1e$boss(GameGui self, Operation<Void> op) {
        s1mp1e$post(ElementType.CROSSHAIRS);          // 準星那段剛結束
        if (!s1mp1e$pre(ElementType.BOSSHEALTH)) op.call(self);
        s1mp1e$post(ElementType.BOSSHEALTH);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GameGui;renderStatusBars(Lnet/minecraft/client/render/Window;)V"))
    private void s1mp1e$status(GameGui self, Window w, Operation<Void> op) {
        if (!s1mp1e$pre(ElementType.HEALTH)) op.call(self, w);
        s1mp1e$post(ElementType.HEALTH);
        for (ElementType t : new ElementType[] { ElementType.ARMOR, ElementType.FOOD, ElementType.AIR }) {
            s1mp1e$pre(t);
            s1mp1e$post(t);
        }
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GameGui;renderXpBar(Lnet/minecraft/client/render/Window;I)V"))
    private void s1mp1e$xp(GameGui self, Window w, int x, Operation<Void> op) {
        if (!s1mp1e$pre(ElementType.EXPERIENCE)) op.call(self, w, x);
        s1mp1e$post(ElementType.EXPERIENCE);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GameGui;renderJumpBar(Lnet/minecraft/client/render/Window;I)V"))
    private void s1mp1e$jump(GameGui self, Window w, int x, Operation<Void> op) {
        if (!s1mp1e$pre(ElementType.JUMPBAR)) op.call(self, w, x);
        s1mp1e$post(ElementType.JUMPBAR);
    }

    // TEXT：Forge 在物品名稱/除錯畫面之後、動作列訊息之前發；S1mp1e 的 HUD 模組掛在 Post(TEXT)
    @Inject(method = "render", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/gui/GameGui;overlayMessageCooldown:I", ordinal = 0))
    private void s1mp1e$text(float tickDelta, CallbackInfo ci) {
        s1mp1e$pre(ElementType.TEXT);
        s1mp1e$post(ElementType.TEXT);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/chat/ChatGui;render(I)V"))
    private void s1mp1e$chat(ChatGui chat, int ticks, Operation<Void> op) {
        if (!s1mp1e$pre(ElementType.CHAT)) op.call(chat, ticks);
        s1mp1e$post(ElementType.CHAT);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/overlay/PlayerTabOverlay;render(ILnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/ScoreboardObjective;)V"))
    private void s1mp1e$tab(PlayerTabOverlay tab, int w, Scoreboard sb, ScoreboardObjective obj, Operation<Void> op) {
        if (!s1mp1e$pre(ElementType.PLAYER_LIST)) op.call(tab, w, sb, obj);
        s1mp1e$post(ElementType.PLAYER_LIST);
    }
}
