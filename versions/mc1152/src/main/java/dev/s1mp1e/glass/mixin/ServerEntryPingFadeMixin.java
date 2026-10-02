package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.GuiAlpha;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerServerListWidget;
import net.minecraft.client.network.ServerInfo;
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
 * server name, which never changes, is left alone. Every wrapped draw takes the same eased alpha ({@link GuiAlpha})
 * and a small downward offset that closes to 0. 1.15.2 port of the 1.20.1 line's (26.2's) {@code ServerEntryPingFadeMixin}.
 *
 * <p>1.15.2 has no {@code ServerInfo.Status}: an entry is pending while {@code server.ping == -2} (what vanilla's own
 * test reads — {@code online && ping != -2}), so the trigger is that test turning true.
 *
 * <p>Draw calls javap-verified in the 1.15.2 {@code ServerEntry.render}: the name is
 * {@code TextRenderer.draw(MatrixStack, String, …)} (untouched), the MOTD lines the only
 * {@code draw(MatrixStack, String, …)}, the status text the only {@code draw(MatrixStack, Text, …)}, the favicon
 * the {@code draw(MatrixStack, II, Identifier)} helper, and the ping icon the FIRST
 * {@code DrawableHelper.drawTexture(MatrixStack, IIFFIIII)} (the later ones are the join / move arrows, shown on
 * hover). Vanilla sets the shader colour itself around these blits; {@code ShaderColorScopeMixin} keeps the fade
 * applied through that.
 */
@Mixin(MultiplayerServerListWidget.ServerEntry.class)
public abstract class ServerEntryPingFadeMixin {

    @Unique private static final float S1_W = 16.0f;       // ~0.33 s
    @Unique private static final float S1_RISE = 3.0f;

    @Shadow @Final private ServerInfo server;

    @Unique private int s1mp1e$lastPending = -1;         // -1 unknown, 0 pinged, 1 pending
    @Unique private long s1mp1e$resultNs;
    /** The icon drawn while the entry was pending: a result that keeps it must not blink it out and back in. */
    @Unique private Identifier s1mp1e$pendingIcon;

    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$watchStatus(int index, int y, int x, int entryWidth, int entryHeight,
                                    int mouseX, int mouseY, boolean hovered, float tickDelta, CallbackInfo ci) {
        boolean pendingNow = !(this.server.online && this.server.ping != -2L);
        if (s1mp1e$lastPending == 1 && !pendingNow) s1mp1e$resultNs = System.nanoTime();
        s1mp1e$lastPending = pendingNow ? 1 : 0;
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
    private boolean s1mp1e$begin() {
        float p = s1mp1e$p();
        if (p >= 1f) return false;
        float inv = 1f - p;
        GuiAlpha.push(1f - inv * inv);
        RenderSystem.pushMatrix();
        RenderSystem.translated(0f, S1_RISE * inv, 0f);
        return true;
    }

    @Unique
    private static void s1mp1e$end(boolean began) {
        if (!began) return;
        GuiAlpha.pop();
        RenderSystem.popMatrix();
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/font/TextRenderer;draw(Ljava/lang/String;FFI)I"))
    private int s1mp1e$motd(TextRenderer font, String line, float x, float y, int color,
                            Operation<Integer> op) {
        boolean b = s1mp1e$begin();
        try { return op.call(font, line, x, y, color); } finally { s1mp1e$end(b); }
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/gui/DrawableHelper;blit(IIFFIIII)V"))
    private void s1mp1e$statusIcon(int x, int y, float u, float v, int w, int h,
                                   int tw, int th, Operation<Void> op) {
        boolean b = s1mp1e$begin();
        try { op.call(x, y, u, v, w, h, tw, th); } finally { s1mp1e$end(b); }
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screen/multiplayer/MultiplayerServerListWidget$ServerEntry;draw(IILnet/minecraft/util/Identifier;)V"))
    private void s1mp1e$favicon(MultiplayerServerListWidget.ServerEntry self, int x, int y,
                                Identifier tex, Operation<Void> op) {
        if (s1mp1e$lastPending == 1) s1mp1e$pendingIcon = tex;
        // same icon as before the result -> no fade, no blink (a 1.20.1-line fix carried over)
        boolean b = !(tex != null && tex.equals(s1mp1e$pendingIcon)) && s1mp1e$begin();
        try { op.call(self, x, y, tex); } finally { s1mp1e$end(b); }
    }
}
