package dev.s1mp1e.client.knife;

import com.mojang.blaze3d.platform.InputConstants;
import dev.s1mp1e.client.gui.KnifeInventoryScreen;
import dev.s1mp1e.client.module.KnifeModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/** Game input -> knife animation events. Visual only: nothing here changes what the attack does. */
public final class KnifeInput {

    private KnifeInput() {}

    /** Minecraft.startAttack HEAD (left click). */
    public static void onAttack() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (!KnifeRenderer.holdingKnife(p) || mc.gui.screen() != null) return;
        Entity target = target(mc.hitResult);
        if (KnifeRenderer.animator().light(target != null, target != null && behind(p, target)))
            KnifeSounds.attack(false, target != null, wall(mc.hitResult));
    }

    /** Minecraft.startUseItem HEAD (right click). */
    public static void onUse() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        KnifeModule m = KnifeModule.get();
        if (m == null || !m.heavy.boolValue || !KnifeRenderer.holdingKnife(p) || mc.gui.screen() != null) return;
        Entity target = target(mc.hitResult);
        if (KnifeRenderer.animator().heavy(target != null, target != null && behind(p, target)))
            KnifeSounds.attack(true, target != null, wall(mc.hitResult));
    }

    private static boolean wall(HitResult hr) {
        return hr != null && hr.getType() == HitResult.Type.BLOCK;
    }

    /**
     * Minecraft.handleKeybinds HEAD: with a knife out, the swap-hands key (F by default — CS2's inspect key too)
     * inspects instead of swapping. Draining the clicks here means vanilla's swap loop sees none.
     */
    public static void onKeybinds() {
        Minecraft mc = Minecraft.getInstance();
        KnifeModule m = KnifeModule.get();
        pollLocker(mc, m);
        if (m == null || !m.inspect.boolValue || !KnifeRenderer.holdingKnife(mc.player)) return;
        boolean pressed = false;
        while (mc.options.keySwapOffhand.consumeClick()) pressed = true;
        if (pressed) KnifeRenderer.animator().inspect();
    }

    private static boolean lockerWasDown, lockerArmed;

    /** Locker key (B) edge -> CS2-inventory knife locker; armed only after a release, like the menu key. */
    private static void pollLocker(Minecraft mc, KnifeModule m) {
        int k = m == null ? 0 : m.lockerKey.intValue;
        boolean down = k > 0 && KnifeModule.active() && KnifePack.available() && mc.isWindowActive()
                && InputConstants.isKeyDown(mc.getWindow(), k);
        if (!down) lockerArmed = true;
        if (lockerArmed && down && !lockerWasDown && mc.gui.screen() == null && mc.player != null) {
            mc.gui.setScreen(new KnifeInventoryScreen());
        }
        lockerWasDown = down;
    }

    private static Entity target(HitResult hr) {
        return hr instanceof EntityHitResult ehr ? ehr.getEntity() : null;
    }

    /** CS2 backstab: the attacker stands behind the victim (both facing roughly the same way). */
    private static boolean behind(LocalPlayer p, Entity e) {
        float d = Mth.wrapDegrees(e.getYRot() - p.getYRot());
        return Math.abs(d) < 60f;
    }
}
