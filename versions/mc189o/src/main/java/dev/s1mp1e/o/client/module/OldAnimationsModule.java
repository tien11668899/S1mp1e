package dev.s1mp1e.o.client.module;

import dev.s1mp1e.o.client.Module;
import dev.s1mp1e.o.client.Setting;
import dev.s1mp1e.o.client.asm.CombatHooks;

/**
 * Restores 1.7-style first-person hand animation.
 *
 * <p><b>Honest scope note.</b> The first-person swing math is byte-for-byte
 * identical between 1.7.10 and 1.8.9 — same translate, the same three rotations
 * driven by {@code sin(swing*swing*PI)} and {@code sin(sqrt(swing)*PI)}, and the
 * same 0.4 scale (verified against the 1.8.9 {@code ItemInHandRenderer} source). So there
 * is no secret set of "1.7 numbers" to plug in; a mod that claims to change the
 * swing arc for a normal attack is either inventing values or shipping a
 * hit-timing/block-hit change under the "animations" label — the latter is
 * server-affecting and BANNED here.
 *
 * <p>The one genuine, purely-visual 1.7 difference this restores: 1.8 passes
 * {@code swingProgress = 0} to the transform while an item is in use (blocking,
 * eating, drinking, drawing a bow), which freezes the weapon; 1.7 let it keep
 * swinging. When enabled, {@link CombatHooks#transformFirstPersonItem} substitutes
 * the player's live swing progress in exactly those frames. That only changes how
 * the held item is DRAWN — it does not cause a block-hit, does not alter attack or
 * block timing, and sends nothing to the server.
 *
 * <p>No Forge events: the ASM reads {@link #enabled} and the toggle below live.
 *
 * <p><b>The other 1.7 differences (2026-10-26)</b>, each derived from what 1.8.9 itself does differently, never from
 * guessed numbers. 1.8 moved held-item placement into the item model JSONs ({@code display.firstperson /
 * thirdperson}), tuned so ordinary tools sit where 1.7's hard-coded transform put them; the items below are where
 * that translation changed the result. All of it is drawing only.
 * <ul>
 *   <li><b>Rod</b> (fishing rod, carrot on a stick): 1.7 turned these 180 degrees about Y <i>before</i> the item
 *       transform; the 1.8 models fold the turn into their rotation, i.e. <i>after</i> the model translation
 *       (firstperson {@code [0,45,25]} vs the tools' {@code [0,-135,25]}, same translation {@code [0,4,2]}).
 *       Turning first equals translating by (0, 0, -4/16) before the 1.8 transform; in third person the same
 *       algebra on {@code [180,90,-35] / [0,0,-3.5]} vs the 1.7 turn-and-drop gives (0, +0.75/16, 0).</li>
 *   <li><b>Bow</b>: both versions stretch the bow by {@code 1 + 0.2 * pull} on Z; 1.7 did it inside the 2D item's
 *       own frame ({@code rotate(-335 Z), rotate(-50 Y), translate(0, .5, 0)}, scale, and back), 1.8 along the hand
 *       frame's Z. Restored by wrapping the scale in that frame.</li>
 *   <li><b>Third-person block</b>: 1.8 added a -30 degree yaw to the blocking arm and dropped 1.7's item turn
 *       ({@code translate(.05, 0, -.1), rotate(-50 Y), rotate(-10 X), rotate(-60 Z)}); both are swapped back.</li>
 *   <li><b>Red armour</b>: 1.8's armour layer opts out of the damage tint ({@code colorsWhenDamaged false}); 1.7
 *       drew armour as part of the tinted model.</li>
 *   <li>Eating and drinking need nothing more: 1.8's {@code applyConsuming} is 1.7's eat transform term for term
 *       (bob, the f^27 lift, the 90/10/30 turn); the only difference is the frozen swing, which
 *       {@code Swing while using} already restores.</li>
 * </ul>
 */
public final class OldAnimationsModule extends Module {

    private static OldAnimationsModule instance;

    /**
     * Whether the weapon keeps swinging while an item is being used (the 1.7 look).
     * A toggle rather than hard-wired so the "restore" is opt-in and auditable —
     * with it off the module is a no-op and vanilla draws untouched.
     */
    private final Setting swingWhileUsing = add(Setting.bool("Swing while using", true));
    private final Setting rod     = add(Setting.bool("1.7 rod", true));
    private final Setting bow     = add(Setting.bool("1.7 bow", true));
    private final Setting block3p = add(Setting.bool("1.7 third-person block", true));
    private final Setting armour  = add(Setting.bool("Red armour on hit", true));

    public OldAnimationsModule() {
        super("OldAnimations", "Combat");
        CombatHooks.bindOldAnimations(this);
        instance = this;
    }

    /** @return true when the held item should keep swinging during item use. */
    public boolean swingWhileUsing() { return swingWhileUsing.boolValue; }

    private static boolean on(OldAnimationsModule m) { return m != null && m.enabled; }

    public static boolean oldRod()     { OldAnimationsModule m = instance; return on(m) && m.rod.boolValue; }
    public static boolean oldBow()     { OldAnimationsModule m = instance; return on(m) && m.bow.boolValue; }
    public static boolean oldBlock3p() { OldAnimationsModule m = instance; return on(m) && m.block3p.boolValue; }
    public static boolean redArmour()  { OldAnimationsModule m = instance; return on(m) && m.armour.boolValue; }
}
