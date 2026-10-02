package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.GuiAlpha;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerServerListWidget;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server list: when a server's ping comes back, the parts the result replaces — the MOTD lines, the favicon, the
 * ping-bars icon and the player-count / status text — fade in and rise into place instead of swapping in one frame. The
 * server name, which never changes, is left alone. The trigger is the entry's {@link ServerInfo#getStatus()} leaving the
 * pending states ({@code INITIAL} / {@code PINGING}); every wrapped draw takes the same eased alpha ({@link GuiAlpha})
 * and a small downward offset that closes to 0. 1.21.1 port of 26.2's {@code ServerEntryPingFadeMixin}.
 *
 * <p>Draw calls verified with javap on {@code ServerEntry.render}: the name is {@code drawText(…String…)} (untouched),
 * the MOTD lines the only {@code drawText(…OrderedText…)}, the status text the only {@code drawText(…Text…)}, the
 * favicon the {@code draw(DrawContext,II,Identifier)} helper, and the ping icon the FIRST {@code drawGuiTexture} (the
 * later ones are the join / move arrows, shown on hover).
 */
@Mixin(MultiplayerServerListWidget.ServerEntry.class)
public abstract class ServerEntryPingFadeMixin {

    @Unique private static final float S1_W = 16.0f;       // ~0.33 s
    @Unique private static final float S1_RISE = 3.0f;

    @Shadow @Final private ServerInfo server;

    @Unique private ServerInfo.Status s1mp1e$lastStatus;
    @Unique private long s1mp1e$resultNs;

    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$watchStatus(DrawContext ctx, int index, int y, int x, int entryWidth, int entryHeight,
                                    int mouseX, int mouseY, boolean hovered, float tickDelta, CallbackInfo ci) {
        ServerInfo.Status s = this.server.getStatus();
        boolean pendingBefore = s1mp1e$lastStatus == ServerInfo.Status.INITIAL || s1mp1e$lastStatus == ServerInfo.Status.PINGING;
        boolean pendingNow = s == ServerInfo.Status.INITIAL || s == ServerInfo.Status.PINGING;
        if (s1mp1e$lastStatus != null && pendingBefore && !pendingNow) s1mp1e$resultNs = System.nanoTime();
        s1mp1e$lastStatus = s;
    }

    /** 0..1 progress of the result's entrance (1 = settled / nothing pending). */
    @Unique
    private float s1mp1e$p() {
        if (s1mp1e$resultNs == 0L) return 1f;
        float t = (System.nanoTime() - s1mp1e$resultNs) / 1.0e9f;
        float p = 1f - (1f + S1_W * t) * (float) Math.exp(-S1_W * t);
        if (p >= 0.998f) { s1mp1e$resultNs = 0L; return 1f; }
        return p;
    }

    @Unique
    private boolean s1mp1e$begin(DrawContext ctx) {
        float p = s1mp1e$p();
        if (p >= 1f) return false;
        float inv = 1f - p;
        GuiAlpha.push(ctx, 1f - inv * inv);
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0f, S1_RISE * inv, 0f);
        return true;
    }

    @Unique
    private static void s1mp1e$end(DrawContext ctx, boolean began) {
        if (!began) return;
        GuiAlpha.pop(ctx);            // flushes what was queued inside the scope while the matrix is still translated
        ctx.getMatrices().pop();
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/OrderedText;IIIZ)I"))
    private int s1mp1e$motd(DrawContext ctx, TextRenderer font, OrderedText line, int x, int y, int color, boolean shadow,
                            Operation<Integer> op) {
        boolean b = s1mp1e$begin(ctx);
        try { return op.call(ctx, font, line, x, y, color, shadow); } finally { s1mp1e$end(ctx, b); }
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;IIIZ)I"))
    private int s1mp1e$statusText(DrawContext ctx, TextRenderer font, Text text, int x, int y, int color, boolean shadow,
                                  Operation<Integer> op) {
        boolean b = s1mp1e$begin(ctx);
        try { return op.call(ctx, font, text, x, y, color, shadow); } finally { s1mp1e$end(ctx, b); }
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/gui/DrawContext;drawGuiTexture(Lnet/minecraft/util/Identifier;IIII)V"))
    private void s1mp1e$statusIcon(DrawContext ctx, Identifier sprite, int x, int y, int w, int h, Operation<Void> op) {
        boolean b = s1mp1e$begin(ctx);
        try { op.call(ctx, sprite, x, y, w, h); } finally { s1mp1e$end(ctx, b); }
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screen/multiplayer/MultiplayerServerListWidget$ServerEntry;draw(Lnet/minecraft/client/gui/DrawContext;IILnet/minecraft/util/Identifier;)V"))
    private void s1mp1e$favicon(MultiplayerServerListWidget.ServerEntry self, DrawContext ctx, int x, int y, Identifier tex,
                                Operation<Void> op) {
        boolean b = s1mp1e$begin(ctx);
        try { op.call(self, ctx, x, y, tex); } finally { s1mp1e$end(ctx, b); }
    }
}
