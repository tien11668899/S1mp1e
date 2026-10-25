package dev.s1mp1e.client.module;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;

/**
 * Restores 1.7-style swing-while-using (purely visual): keeps the main-hand swing running
 * while an item is in use (bow/eat/drink/block), which 1.21.1 suppresses by skipping
 * {@code applySwingOffset}. {@code HeldItemSwingMixin} re-applies the vanilla swing in
 * exactly those frames when {@link #active()} is true.
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
