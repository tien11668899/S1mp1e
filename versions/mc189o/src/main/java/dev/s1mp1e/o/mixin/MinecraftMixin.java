package dev.s1mp1e.o.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.o.Bench;
import dev.s1mp1e.o.event.GuiOpenEvent;
import dev.s1mp1e.o.event.MinecraftForge;
import dev.s1mp1e.o.event.MouseEvent;
import dev.s1mp1e.o.event.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.entity.living.player.LocalClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge 在 Minecraft 上的事件：GuiOpenEvent（開畫面）、ClientTickEvent（tick 前後）、MouseEvent（滑鼠事件迴圈）、
 * RenderTickEvent（每幀渲染前後；END 也是 DevShot/Bench 的每幀結尾點）。
 */
@Mixin(value = Minecraft.class, priority = 1100)
public abstract class MinecraftMixin {

    @Shadow public ClientWorld world;
    @Shadow public LocalClientPlayerEntity player;
    @Shadow public Screen screen;

    @Shadow public abstract void openScreen(Screen screen);

    @Unique private boolean s1mp1e$reopening;

    // ---- 對應 Forge FMLInitialization：Forge 在 startGame 開第一個畫面（標題/連線）之前跑 init，
    //      所以注入在 init 裡 this.gui = new GameGui(this) 之後（兩條開畫面分支之前都會經過；
    //      品牌開場要攔到第一個標題畫面） ----
    @Inject(method = "init", at = @At(value = "FIELD", opcode = org.objectweb.asm.Opcodes.PUTFIELD,
            target = "Lnet/minecraft/client/Minecraft;gui:Lnet/minecraft/client/gui/GameGui;", shift = At.Shift.AFTER))
    private void s1mp1e$lateInit(CallbackInfo ci) {
        dev.s1mp1e.o.S1mp1eClient.init();
    }

    // ---- GuiOpenEvent：和 Forge 一樣在「null→標題/死亡畫面」替換之後發；取消＝整個不開、舊畫面也不關 ----
    @Inject(method = "openScreen", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$guiOpen(Screen next, CallbackInfo ci) {
        if (s1mp1e$reopening) return;
        Screen sub = next;
        if (sub == null && this.world == null) sub = new TitleScreen();
        else if (sub == null && this.player != null && this.player.getHealth() <= 0.0F) sub = new DeathScreen();
        GuiOpenEvent e = new GuiOpenEvent(sub);
        if (MinecraftForge.EVENT_BUS.post(e)) {
            ci.cancel();
            return;
        }
        if (e.gui != sub || sub != next) {
            // 監聽者換了畫面（或做了替換）：用替換後的畫面重開一次，這次不再發事件
            ci.cancel();
            s1mp1e$reopening = true;
            try {
                openScreen(e.gui);
            } finally {
                s1mp1e$reopening = false;
            }
        }
    }

    // ---- ClientTickEvent ----
    @Inject(method = "tick", at = @At("HEAD"))
    private void s1mp1e$tickStart(CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void s1mp1e$tickEnd(CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
    }

    // ---- MouseEvent：每個 Mouse.next() 之後發；被取消的事件原版就不處理（跳到下一個） ----
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Mouse;next()Z"))
    private boolean s1mp1e$mouse(Operation<Boolean> op) {
        while (op.call()) {
            if (!MinecraftForge.EVENT_BUS.post(new MouseEvent())) return true;
        }
        return false;
    }

    // ---- RenderTickEvent：GameRenderer.render 前後 ----
    @WrapOperation(method = "runGame", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/GameRenderer;render(FJ)V"))
    private void s1mp1e$renderTick(GameRenderer r, float pt, long t, Operation<Void> op) {
        MinecraftForge.EVENT_BUS.post(new TickEvent.RenderTickEvent(TickEvent.Phase.START, pt));
        op.call(r, pt, t);
        MinecraftForge.EVENT_BUS.post(new TickEvent.RenderTickEvent(TickEvent.Phase.END, pt));
        try {
            Bench.onRenderEnd((Minecraft) (Object) this);   // 效能量測：沒設 S1MP1E_BENCH 時什麼都不做
        } catch (Throwable ignored) {
        }
    }
}
