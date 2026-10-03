package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.gui.widget.EntryListWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entries arriving later (the world list after its load, LAN servers, a refilled list …) cascade in — each rises 6 px
 * and fades in on a critically damped spring, 30 ms apart; entries already there on the list's first frame are left
 * alone. The entry half of {@code ListMotionMixin}: on 1.13.2 only {@code EntryListWidget} (GuiListExtended) has entry
 * objects. {@code renderBackground()} (empty here, called once at the start of every {@code ListWidget.render}) is the
 * per-frame point where the entries are stamped; an entry's draw is the one {@code Entry.method_6700(IIIIZF)} call in
 * {@code method_1055}.
 */
@Mixin(EntryListWidget.class)
public abstract class EntryListMotionMixin {
    /** Critically damped spring rate of an arriving entry (~0.3 s settle) and how far it rises (26.2 values). */
    @Unique private static final float S1MP1E_ENTER_W = 18.0f;
    @Unique private static final float S1MP1E_ENTER_RISE = 6.0f;
    @Unique private final java.util.IdentityHashMap<Object, Long> s1mp1e$born = new java.util.IdentityHashMap<Object, Long>();
    @Unique private boolean s1mp1e$primed;

    @Shadow public abstract java.util.List<?> method_18423();

    /**
     * Stamp entries the first time they are seen. Those present on the list's very first frame count as settled; an
     * entry that turns up later gets a birth time, staggered 30 ms per entry within one batch.
     */
    @Inject(method = "renderBackground", at = @At("HEAD"))
    private void s1mp1e$trackEntries(CallbackInfo ci) {
        java.util.List<?> ch = method_18423();
        if (!s1mp1e$primed) {
            for (Object e : ch) s1mp1e$born.put(e, Long.MIN_VALUE);
            s1mp1e$primed = true;
            return;
        }
        long now = System.nanoTime();
        int k = 0;
        for (Object e : ch) {
            if (!s1mp1e$born.containsKey(e)) { s1mp1e$born.put(e, now + Math.min(k * 30_000_000L, 240_000_000L)); k++; }
        }
        if (s1mp1e$born.size() > ch.size() + 32) {           // forget removed entries
            java.util.IdentityHashMap<Object, Long> keep = new java.util.IdentityHashMap<Object, Long>();
            for (Object e : ch) keep.put(e, s1mp1e$born.get(e));
            s1mp1e$born.clear();
            s1mp1e$born.putAll(keep);
        }
    }

    /** A newly arrived entry: draw it risen-from-below and faded ({@link dev.s1mp1e.client.gui.GuiAlpha}), easing in. */
    @WrapOperation(method = "method_1055",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/widget/EntryListWidget$Entry;method_6700(IIIIZF)V"))
    private void s1mp1e$entryEnter(EntryListWidget.Entry<?> entry, int entryWidth, int entryHeight, int mouseX, int mouseY,
                                   boolean hovered, float delta, Operation<Void> op) {
        Long born = s1mp1e$born.isEmpty() ? null : s1mp1e$born.get(entry);
        if (born == null || born == Long.MIN_VALUE) {
            op.call(entry, entryWidth, entryHeight, mouseX, mouseY, hovered, delta);
            return;
        }
        float t = (System.nanoTime() - born) / 1.0e9f;
        float p = t <= 0f ? 0f : 1f - (1f + S1MP1E_ENTER_W * t) * (float) Math.exp(-S1MP1E_ENTER_W * t);
        if (p >= 0.998f) {
            s1mp1e$born.put(entry, Long.MIN_VALUE);
            op.call(entry, entryWidth, entryHeight, mouseX, mouseY, hovered, delta);
            return;
        }
        float inv = 1f - p;
        dev.s1mp1e.client.gui.GuiAlpha.push(1f - inv * inv);
        GlStateManager.pushMatrix();
        GlStateManager.translate(0f, S1MP1E_ENTER_RISE * inv, 0f);
        try {
            op.call(entry, entryWidth, entryHeight, mouseX, mouseY, hovered, delta);
        } finally {
            dev.s1mp1e.client.gui.GuiAlpha.pop();
            GlStateManager.popMatrix();
        }
    }
}
