package dev.s1mp1e.forge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.integrated.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 原版 1.8.9 離開單人世界的卡死競態：「儲存並離開」先送斷線封包，內建伺服器收到後自己停機；
 * 用戶端接著呼叫 IntegratedServer.stop()，排一個「登出所有玩家」工作給伺服器執行緒並無限等待。
 * 如果伺服器已經離開主迴圈（還在存檔、還沒設 stopped），那個工作永遠沒人執行 → 卡在「儲存世界」。
 * 這裡改成：伺服器已停機（running=false）超過 250ms 就不再等（它停機時本來就會存檔＋登出玩家）。
 */
@Mixin(value = IntegratedServer.class, priority = 1050)
public abstract class IntegratedServerQuitMixin {
    @WrapOperation(method = "stop", at = @At(value = "INVOKE", target = "Lcom/google/common/util/concurrent/Futures;getUnchecked(Ljava/util/concurrent/Future;)Ljava/lang/Object;"))
    private Object s1f$boundedWait(Future<?> f, Operation<Object> op) {
        IntegratedServer self = (IntegratedServer) (Object) this;
        long stoppingSince = 0;
        // 注意：MinecraftServer.getThread() 只在專用伺服器端存在（用戶端 jar 沒有），只能用 isRunning()
        while (!f.isDone()) {
            if (!self.isRunning()) {
                long now = System.nanoTime();
                if (stoppingSince == 0) stoppingSince = now;
                else if (now - stoppingSince > TimeUnit.MILLISECONDS.toNanos(250)) return null;
            }
            try {
                f.get(5, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
            }
        }
        return op.call(f);
    }
}
