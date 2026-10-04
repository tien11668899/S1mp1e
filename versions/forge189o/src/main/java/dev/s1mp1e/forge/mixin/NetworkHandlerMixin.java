package dev.s1mp1e.forge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.network.handler.ClientPlayNetworkHandler;
import net.minecraft.network.Connection;
import net.minecraft.network.packet.s2c.play.ChatMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.LoginS2CPacket;
import net.minecraft.text.Text;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Forge 的 ClientChatReceivedEvent（可改訊息／取消）與 FML 的連線／斷線事件。 */
@Mixin(value = ClientPlayNetworkHandler.class, priority = 1050)
public abstract class NetworkHandlerMixin {
    @Shadow @Final private Connection connection;

    @Unique private Text s1f$chat;

    @Inject(method = "handleChatMessage", cancellable = true, at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/PacketUtils;ensureOnSameThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/handler/PacketHandler;Lnet/minecraft/util/BlockableEventLoop;)V"))
    private void s1f$chatEvent(ChatMessageS2CPacket p, CallbackInfo ci) {
        s1f$chat = ForgeEventFactory.onClientChat(p.getType(), p.getMessage());
        if (s1f$chat == null) ci.cancel();
    }

    @WrapOperation(method = "handleChatMessage", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/packet/s2c/play/ChatMessageS2CPacket;getMessage()Lnet/minecraft/text/Text;"))
    private Text s1f$chatMessage(ChatMessageS2CPacket p, Operation<Text> op) {
        return s1f$chat != null ? s1f$chat : op.call(p);
    }

    @Inject(method = "handleLogin", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/PacketUtils;ensureOnSameThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/handler/PacketHandler;Lnet/minecraft/util/BlockableEventLoop;)V"))
    private void s1f$connected(LoginS2CPacket p, CallbackInfo ci) {
        FMLCommonHandler.instance().bus().post(new FMLNetworkEvent.ClientConnectedToServerEvent(this.connection, "VANILLA"));
    }

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void s1f$disconnected(Text reason, CallbackInfo ci) {
        FMLCommonHandler.instance().bus().post(new FMLNetworkEvent.ClientDisconnectionFromServerEvent(this.connection));
    }
}
