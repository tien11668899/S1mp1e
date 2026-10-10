package dev.s1mp1e.o.knife;

import dev.s1mp1e.o.client.module.KnifeModule;
import dev.s1mp1e.o.event.MouseEvent;
import dev.s1mp1e.o.event.SubscribeEvent;
import dev.s1mp1e.o.event.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.living.player.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.HitResult;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;

/**
 * Game input -> knife animation events (1.8.9). Visual only: nothing here changes what the attack does, so the client
 * stays auditable. Left click = light swing, right click = heavy (if enabled), the inspect key (F, CS2's own) plays
 * the inspect clip. Registered on the module's event bus on enable.
 */
public final class KnifeInput {

    public static final KnifeInput INSTANCE = new KnifeInput();

    private KnifeInput() {}

    @SubscribeEvent
    public void onMouse(MouseEvent e) {
        if (!e.buttonstate) return;                       // presses only
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null) return;
        if (!KnifeRenderer.holdingKnife(mc.player)) return;
        KnifeModule m = KnifeModule.get();
        HitResult hr = mc.crosshairTarget;
        Entity target = hr != null && hr.type == HitResult.Type.ENTITY ? hr.entity : null;
        boolean wall = hr != null && hr.type == HitResult.Type.BLOCK;
        if (e.button == 0) {
            if (KnifeRenderer.animator().light(target != null, target != null && behind(mc.player, target)))
                KnifeSounds.attack(false, target != null, wall);
        } else if (e.button == 1 && m != null && m.heavy.boolValue) {
            if (KnifeRenderer.animator().heavy(target != null, target != null && behind(mc.player, target)))
                KnifeSounds.attack(true, target != null, wall);
        }
    }

    private boolean inspectWasDown;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        KnifeRenderer.tickEquipState();
        Minecraft mc = Minecraft.getInstance();
        KnifeModule m = KnifeModule.get();
        boolean down = m != null && m.inspect.boolValue && mc.screen == null && Display.isActive()
                && KnifeRenderer.holdingKnife(mc.player) && Keyboard.isKeyDown(Keyboard.KEY_F);
        if (down && !inspectWasDown) KnifeRenderer.animator().inspect();
        inspectWasDown = down;
        pollLocker(mc, m);
    }

    private boolean lockerWasDown, lockerArmed;

    /** Locker key (B) edge -> CS2-inventory knife locker; armed only after a release, like the menu key. */
    private void pollLocker(Minecraft mc, KnifeModule m) {
        int k = m == null ? 0 : m.lockerKey.intValue;
        boolean down = k > 0 && KnifeModule.active() && KnifePack.available() && Display.isActive()
                && Keyboard.isKeyDown(k);
        if (!down) lockerArmed = true;
        if (lockerArmed && down && !lockerWasDown && mc.screen == null && mc.player != null) {
            mc.openScreen(new dev.s1mp1e.o.client.gui.KnifeLockerScreen());
        }
        lockerWasDown = down;
    }

    /** CS2 backstab: the attacker stands behind the victim (both facing roughly the same way). */
    private static boolean behind(ClientPlayerEntity p, Entity e) {
        float d = MathHelper.wrapDegrees(e.yaw - p.yaw);
        return Math.abs(d) < 60f;
    }
}
