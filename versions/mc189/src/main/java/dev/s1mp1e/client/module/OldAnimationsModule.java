package dev.s1mp1e.client.module;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.asm.CombatHooks;

/**
 * Restores 1.7-style first-person hand animation.
 *
 * <p><b>Honest scope note.</b> The first-person swing math is byte-for-byte
 * identical between 1.7.10 and 1.8.9 — same translate, the same three rotations
 * driven by {@code sin(swing*swing*PI)} and {@code sin(sqrt(swing)*PI)}, and the
 * same 0.4 scale (verified against the 1.8.9 {@code ItemRenderer} source). So there
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
 */
public final class OldAnimationsModule extends Module {

    /**
     * Whether the weapon keeps swinging while an item is being used (the 1.7 look).
     * A toggle rather than hard-wired so the "restore" is opt-in and auditable —
     * with it off the module is a no-op and vanilla draws untouched.
     */
    private final Setting swingWhileUsing = add(Setting.bool("Swing while using", true));

    public OldAnimationsModule() {
        super("OldAnimations", "Visual");
        CombatHooks.bindOldAnimations(this);
    }

    /** @return true when the held item should keep swinging during item use. */
    public boolean swingWhileUsing() { return swingWhileUsing.boolValue; }
}
