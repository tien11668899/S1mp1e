package dev.s1mp1e.client.module;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;

/**
 * Restores 1.7-style swing-while-using (purely visual): keeps the main-hand swing running
 * while an item is in use (bow/eat/drink/block), which vanilla suppresses by skipping the
 * swing transform in the use branch of {@code ItemInHandRenderer.submitArmWithItem}.
 * {@code HeldItemSwingMixin} re-applies the vanilla attack-swing rotation in exactly those
 * frames when {@link #active()} is true. First-person render only — never changes what the
 * player actually does.
 */
public final class OldAnimationsModule extends Module {
    private static volatile OldAnimationsModule instance;

    public final Setting swingWhileUsing = add(Setting.bool("Swing while using", true));

    public OldAnimationsModule() { super("OldAnimations", "Combat"); instance = this; }

    public boolean swingWhileUsing() { return swingWhileUsing.boolValue; }

    /** Hot-path gate for {@code HeldItemSwingMixin}: true when the restore is live. */
    public static boolean active() {
        OldAnimationsModule m = instance;
        return m != null && m.enabled && m.swingWhileUsing.boolValue;
    }
}
