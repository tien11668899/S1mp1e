package dev.s1mp1e.client.module;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.asm.CombatHooks;

/**
 * Restores 1.7-style swing-while-using (purely visual).
 *
 * <p>1.12.2's per-hand {@code ItemRenderer.renderItemInFirstPerson} applies the swing
 * transform ({@code transformFirstPerson}) only when the hand is NOT using an item; in the
 * eat/drink/bow/block branch it is skipped, so the held item freezes in its use pose even
 * though the swing progress keeps running. With this module on,
 * {@link CombatHooks#swingWhileUsing} re-applies vanilla's own swing math on top of the use
 * pose in exactly those frames (main hand, live swing, main hand in use) — the same design as
 * the 1.20.1 {@code HeldItemSwingMixin}. That only changes how the held item is DRAWN; it does
 * not cause a block-hit, does not alter attack or block timing, and sends nothing to the
 * server.
 *
 * <p>No Forge events: the ASM hook reads {@link #enabled} and the toggle below live.
 */
public final class OldAnimationsModule extends Module {

    /**
     * Whether the weapon keeps swinging while an item is being used (the 1.7 look).
     * A toggle rather than hard-wired so the "restore" is opt-in and auditable —
     * with it off the module is a no-op and vanilla draws untouched.
     */
    private final Setting swingWhileUsing = add(Setting.bool("Swing while using", true));

    public OldAnimationsModule() {
        super("OldAnimations", "Combat");
        CombatHooks.bindOldAnimations(this);
    }

    /** @return true when the held item should keep swinging during item use. */
    public boolean swingWhileUsing() { return swingWhileUsing.boolValue; }
}
