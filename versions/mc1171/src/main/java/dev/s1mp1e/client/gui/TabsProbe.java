package dev.s1mp1e.client.gui;

/**
 * Dev-only duck interface on the creative screen: hands {@code DevShotVerify} the per-screen fused-tab animator so the
 * tabs sweep can log the selection / hover pill motion (slide, cross-fade) next to its frame bursts. No behaviour.
 */
public interface TabsProbe {
    dev.s1mp1e.glass.render.GlassTabs s1mp1e$probeTabs();
}
