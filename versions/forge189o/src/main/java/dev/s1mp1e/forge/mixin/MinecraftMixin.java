package dev.s1mp1e.forge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.living.player.LocalClientPlayerEntity;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.resource.manager.ReloadableResourceManager;
import net.minecraft.client.resource.pack.ResourcePack;
import net.minecraft.client.world.ClientWorld;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.common.FMLCommonHandler;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge 在 Minecraft 上的掛勾（照 Forge 1.8.9 補丁的位置）：
 * FML 載入流程三段、ClientTick／RenderTick、GuiOpenEvent、滑鼠／鍵盤事件。
 */
@Mixin(value = Minecraft.class, priority = 1050)
public abstract class MinecraftMixin {
    @Shadow @Final private List<ResourcePack> defaultResourcePacks;
    @Shadow private ReloadableResourceManager resourceManager;
    @Shadow public ClientWorld world;
    @Shadow public LocalClientPlayerEntity player;

    @Shadow public abstract void openScreen(Screen screen);

    @Unique private boolean s1f$reopening;
    @Unique private boolean s1f$keyPending;
    @Unique private boolean s1f$mousePending;

    // ---- FML 載入：Forge 在 new TextureManager 之前 beginMinecraftLoading（建構＋preInit） ----
    @Inject(method = "init", at = @At(value = "NEW", target = "Lnet/minecraft/client/render/texture/TextureManager;"))
    private void s1f$begin(CallbackInfo ci) {
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("s1mp1e")) dev.s1mp1e.forge.bridge.S1mp1eBridge.install();
        dev.s1mp1e.forge.DevTest.installProbe();
        FMLClientHandler.instance().beginMinecraftLoading((Minecraft) (Object) this, defaultResourcePacks, resourceManager);
    }

    // ---- init／postInit：Forge 在 checkGLError("Post startup") 之前 ----
    @Inject(method = "init", at = @At(value = "CONSTANT", args = "stringValue=Post startup"))
    private void s1f$finish(CallbackInfo ci) {
        FMLClientHandler.instance().finishMinecraftLoading();
    }

    // ---- loadComplete：Forge 在 loadingScreen 建好之後 ----
    @Inject(method = "init", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lnet/minecraft/client/Minecraft;progressRenderer:Lnet/minecraft/client/render/ProgressRenderer;", shift = At.Shift.AFTER))
    private void s1f$complete(CallbackInfo ci) {
        FMLClientHandler.instance().onInitializationComplete();
        dev.s1mp1e.forge.DevHooks.onStarted((Minecraft) (Object) this);
    }

    // ---- GuiOpenEvent：在「null→標題/死亡畫面」替換之後發；取消＝不開 ----
    @Inject(method = "openScreen", at = @At("HEAD"), cancellable = true)
    private void s1f$guiOpen(Screen next, CallbackInfo ci) {
        if (s1f$reopening) return;
        Screen sub = next;
        if (sub == null && this.world == null) sub = new TitleScreen();
        else if (sub == null && this.player != null && this.player.getHealth() <= 0.0F) sub = new DeathScreen();
        GuiOpenEvent e = new GuiOpenEvent(sub);
        if (MinecraftForge.EVENT_BUS.post(e)) {
            ci.cancel();
            return;
        }
        if (e.gui != sub || sub != next) {
            ci.cancel();
            s1f$reopening = true;
            try {
                openScreen(e.gui);
            } finally {
                s1f$reopening = false;
            }
        }
    }

    // ---- WorldEvent.Unload：Forge 在 loadWorld 開頭、舊世界還在時發 ----
    @Inject(method = "setWorld(Lnet/minecraft/client/world/ClientWorld;Ljava/lang/String;)V", at = @At("HEAD"))
    private void s1f$worldUnload(ClientWorld next, String msg, CallbackInfo ci) {
        if (this.world != null) MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.world.WorldEvent.Unload(this.world));
    }

    // ---- ClientTickEvent ----
    @Inject(method = "tick", at = @At("HEAD"))
    private void s1f$tickStart(CallbackInfo ci) {
        FMLCommonHandler.instance().onPreClientTick();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void s1f$tickEnd(CallbackInfo ci) {
        FMLCommonHandler.instance().onPostClientTick();
    }

    // ---- MouseEvent（可取消）＋每個滑鼠事件處理完的 InputEvent.MouseInputEvent ----
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Mouse;next()Z"))
    private boolean s1f$mouse(Operation<Boolean> op) {
        if (s1f$mousePending) {
            s1f$mousePending = false;
            FMLCommonHandler.instance().fireMouseInput();
        }
        while (op.call()) {
            if (!ForgeHooksClient.postMouseEvent()) {
                s1f$mousePending = true;
                return true;
            }
        }
        return false;
    }

    // ---- 每個按鍵事件處理完的 InputEvent.KeyInputEvent ----
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Keyboard;next()Z"))
    private boolean s1f$key(Operation<Boolean> op) {
        if (s1f$keyPending) {
            s1f$keyPending = false;
            FMLCommonHandler.instance().fireKeyInput();
        }
        boolean r = op.call();
        s1f$keyPending = r;
        return r;
    }

    // ---- RenderTickEvent ----
    @WrapOperation(method = "runGame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;render(FJ)V"))
    private void s1f$renderTick(GameRenderer r, float pt, long t, Operation<Void> op) {
        FMLCommonHandler.instance().onRenderTickStart(pt);
        op.call(r, pt, t);
        FMLCommonHandler.instance().onRenderTickEnd(pt);
    }
}
