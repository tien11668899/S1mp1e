package dev.s1mp1e.o.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.s1mp1e.o.glass.hook.ContextualBarHook;
import dev.s1mp1e.o.glass.hook.GlassActionBar;
import dev.s1mp1e.o.glass.hook.GlassBossBar;
import dev.s1mp1e.o.glass.hook.HudMotionHook;
import dev.s1mp1e.o.glass.hook.ScoreboardHook;
import net.minecraft.client.gui.GameGui;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * coremod 原本打在 Forge 的 GuiIngameForge／GuiIngame 上的修補，搬到原版 GameGui 的對應位置：
 * Boss 條換成玻璃膠囊、動作列文字加玻璃 pill、Tab 清單放開後淡出期間仍畫、計分板側欄淡入淡出、
 * 血條受傷拖尾（只套在愛心那段的貼圖）。
 */
@Mixin(value = GameGui.class, priority = 1100)
public abstract class GameGuiGlassMixin {

    // ---- Boss 條 ----
    @Inject(method = "renderBossBars", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$boss(CallbackInfo ci) {
        if (GlassBossBar.draw()) ci.cancel();
    }

    // ---- 動作列（overlay message）：render 裡唯一的 draw(String,III)I ----
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/TextRenderer;draw(Ljava/lang/String;III)I"))
    private int s1mp1e$actionBar(TextRenderer fr, String s, int x, int y, int c, Operation<Integer> op) {
        return GlassActionBar.draw(fr, s, x, y, c);
    }

    // ---- Tab 清單閘門 ----
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/options/KeyBinding;isPressed()Z"))
    private boolean s1mp1e$tabGate(KeyBinding key, Operation<Boolean> op) {
        return HudMotionHook.tabGate(key);
    }

    // ---- 計分板側欄查詢 ----
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/scoreboard/Scoreboard;getDisplayObjective(I)Lnet/minecraft/scoreboard/ScoreboardObjective;"))
    private ScoreboardObjective s1mp1e$sidebar(Scoreboard sb, int slot, Operation<ScoreboardObjective> op) {
        return ScoreboardHook.sidebar(sb, slot);
    }

    // ---- 計分板畫面記錄（消失後淡出用） ----
    @Inject(method = "renderScoreboardObjective", at = @At("HEAD"))
    private void s1mp1e$sbBegin(CallbackInfo ci) {
        ScoreboardHook.begin();
    }

    @Inject(method = "renderScoreboardObjective", at = @At("RETURN"))
    private void s1mp1e$sbEnd(CallbackInfo ci) {
        ScoreboardHook.end();
    }

    @WrapOperation(method = "renderScoreboardObjective", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GameGui;fill(IIIII)V"))
    private void s1mp1e$sbFill(int x0, int y0, int x1, int y1, int c, Operation<Void> op) {
        ScoreboardHook.fill(x0, y0, x1, y1, c);
    }

    @WrapOperation(method = "renderScoreboardObjective", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/TextRenderer;draw(Ljava/lang/String;III)I"))
    private int s1mp1e$sbText(TextRenderer fr, String s, int x, int y, int c, Operation<Integer> op) {
        return ScoreboardHook.text(fr, s, x, y, c);
    }

    // ---- 血條受傷拖尾 ----
    // 第一個 boolean 區域變數 = 白框閃爍旗標 → heartBlink(目前血量)
    @ModifyVariable(method = "renderStatusBars", at = @At(value = "STORE", ordinal = 0), ordinal = 0)
    private boolean s1mp1e$heartBlink(boolean orig, @Local(ordinal = 0) int health) {
        return HudMotionHook.heartBlink(health);
    }

    // 第二個 int 區域變數 = displayHealth（上一次血量）→ heartTop（拖尾頂端）。注意：heartBlink 吃目前血量、heartTop 回傳顯示用頂端
    @ModifyVariable(method = "renderStatusBars", at = @At(value = "STORE", ordinal = 0), ordinal = 1)
    private int s1mp1e$heartTop(int healthLast) {
        return HudMotionHook.heartTop(healthLast);
    }

    // 只有 "health" ~ "food" 這段的貼圖是愛心（盔甲半格、氣泡破裂也是 u=25，不能套）
    @WrapOperation(method = "renderStatusBars",
            slice = @Slice(from = @At(value = "CONSTANT", args = "stringValue=health"),
                    to = @At(value = "CONSTANT", args = "stringValue=food")),
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GameGui;drawTexture(IIIIII)V"))
    private void s1mp1e$heartBlit(GameGui self, int x, int y, int u, int v, int w, int h, Operation<Void> op) {
        HudMotionHook.heartBlit(self, x, y, u, v, w, h);
    }

    // ---- ALLGLASS #17 — the horse jump bar as a glass capsule (track v=84, fill v=89). The XP bar is owned by
    // GlassHudHandler (vanilla's is cancelled), which routes its own blits through ContextualBarHook separately. ----
    @WrapOperation(method = "renderJumpBar", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GameGui;drawTexture(IIIIII)V"))
    private void s1mp1e$jumpBar(GameGui self, int x, int y, int u, int v, int w, int h, Operation<Void> op) {
        ContextualBarHook.bar(self, x, y, u, v, w, h);
    }
}
