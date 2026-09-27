package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.ContainerBodyBlit;
import dev.s1mp1e.glass.render.ContainerGlass;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;
import java.util.Set;

/**
 * System 2 (container glass) — frosted panel + slot lattice + quick-craft drag
 * highlight + hover pill. The 1.17.1 Fabric port of {@code GlassContainerHandler}
 * (itself LiquidGlass26's {@code GlassPanels} + {@code ContainerScreensGlassMixin}).
 *
 * <h3>Injection point — the Forge {@code BackgroundDrawnEvent} equivalent</h3>
 * The Forge handler drew from {@code GuiScreenEvent.BackgroundDrawnEvent}: after
 * the dark world-dim gradient, before {@code GuiContainer} paints its GUI texture,
 * so the panel ends up UNDER the container texture (invisible while the screen is
 * open for vanilla screens — the Forge port accepts this; the only unobstructed
 * view of the panel is the close ghost).
 *
 * <p>As in 1.16.5, {@code HandledScreen.render} does NOT call {@code renderBackground}
 * itself; its very first {@code INVOKE} is the abstract
 * {@code drawBackground(MatrixStack, float, int, int)}, and the dark dim gradient
 * lives INSIDE each concrete {@code drawBackground}. The behaviour-matching seam is
 * thus {@code shift = BEFORE} the {@code drawBackground} {@code INVOKE}: the glass
 * draws before the whole background (dim + texture), so the texture covers it during
 * the open state exactly as in Forge, and the visible panel remains the close ghost.
 * We inject into {@code render} (yarn {@code method_25394}, an override physically
 * declared on {@code HandledScreen}; descriptor
 * {@code (Lnet/minecraft/client/util/math/MatrixStack;IIF)V}) at the {@code INVOKE}
 * of {@code drawBackground} ({@code method_2389}, descriptor
 * {@code (Lnet/minecraft/client/util/math/MatrixStack;FII)V}; verified against yarn
 * 1.17.1+build.65 — both unchanged from 1.16.5). (We cannot inject
 * {@code drawBackground} directly: it is abstract on {@code HandledScreen}.)
 *
 * <p>At the {@code drawBackground} instruction no {@code translate(x, y)} is active
 * yet, so drawing at the absolute {@code this.x / this.y} panel origin is correct —
 * as in the Forge port. The vanilla container texture then paints over the glass (we
 * do not cancel it), so the panel is fully visible only during the close ghost.
 *
 * <h3>Field / method mapping (Forge reflection -> yarn shadows), verified 1.17.1</h3>
 * <ul>
 *   <li>{@code guiLeft/guiTop/xSize/ySize} -> {@code this.x} ({@code field_2776}) /
 *       {@code y} ({@code field_2800}) / {@code backgroundWidth} ({@code field_2792}) /
 *       {@code backgroundHeight} ({@code field_2779})</li>
 *   <li>{@code inventorySlots.inventorySlots} -> {@code this.handler.slots}
 *       ({@code ScreenHandler.field_7761})</li>
 *   <li>{@code Slot.xPos/yPos} -> {@code Slot.x/y} ({@code field_7873/field_7872})</li>
 *   <li><b>Signature delta vs 1.16.5:</b> {@code Slot.isEnabled()} — yarn RENAMED
 *       {@code method_7682} {@code doDrawHoveringEffect -> isEnabled} at 1.17
 *       (the method id is unchanged). The 1.16.5 line called
 *       {@code s.doDrawHoveringEffect()}; here it is {@code s.isEnabled()}.</li>
 *   <li>{@code dragSplitting} -> {@code this.cursorDragging} ({@code field_2794});
 *       {@code dragSplittingSlots} -> {@code this.cursorDragSlots}
 *       ({@code field_2793})</li>
 * </ul>
 * All constants, spring rigs and easings are byte-for-byte the 26.2 values.
 *
 * <p><b>1.17.1 status: FULLY FUNCTIONAL.</b> Draws through {@link GlassRenderer}
 * (panel / lattice batch / glass — all core-legal, already ported) and
 * {@link PanelGhost} (core-legal). Nothing here is stubbed.
 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenGlassMixin {

    // ---- yarn shadows ------------------------------------------------------
    @Shadow protected int x;
    @Shadow protected int y;
    @Shadow protected int backgroundWidth;
    @Shadow protected int backgroundHeight;
    @Shadow protected ScreenHandler handler;
    @Shadow protected Set<Slot> cursorDragSlots;
    @Shadow protected boolean cursorDragging;
    // Own abstract method of HandledScreen (declared here, not inherited) -> @Shadow
    // resolves. Used for the no-glass fallback and the per-screen delegations.
    @Shadow protected abstract void drawBackground(MatrixStack matrices, float delta, int mouseX, int mouseY);

    // ---- per-screen-instance state (fresh with each opened container) ------
    // The panel + lattice + drag + hover body now lives in ContainerGlass (byte-for-byte the previous in-mixin code),
    // shared with the list screens that must run their own drawBackground (creative / stonecutter / loom / merchant).
    @Unique private final ContainerGlass.State s1mp1e$glass = new ContainerGlass.State();

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;"
                            + "drawBackground(Lnet/minecraft/client/util/math/MatrixStack;FII)V"))
    private void s1mp1e$glassPanel(HandledScreen self, MatrixStack matrices,
                                   float delta, int mouseX, int mouseY) {
        // Replace the vanilla container PNG with glass (26.2 design). render() already ran
        // renderBackground() (the dim) before this drawBackground call for most screens, so the
        // dim survives; only the opaque body texture is replaced by glass (see below). (An
        // earlier @Inject shift=BEFORE drew glass then let the PNG paint over it -> the panel
        // was invisible while open; the @Redirect fixes that, matching 1.16.5.)

        // Creative: run its OWN drawBackground so CreativeGlassMixin can @Redirect the
        // ordinal-0 item-panel blit inside it (fused tab sheet) and the scrollbar knob.
        // Stonecutter / loom / merchant (the KNOWN BLOCKER fix, as 26.2 / 1.19.2 / 1.21.1): their recipe list, pattern
        // grid, scroller, dim (stonecutter / loom call renderBackground INSIDE drawBackground) and the merchant's
        // out-of-stock marker are all drawn inside their own drawBackground, so swallowing it here erased them. Route
        // them out of the swallow; their dedicated mixins (Stonecutter/Loom/MerchantScrollGlassMixin) @Redirect only the
        // body blit -> ContainerGlass (the same panel + lattice + hover as here) and the scroller -> the glass slider.
        if ((Object) this instanceof net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen
                || (Object) this instanceof net.minecraft.client.gui.screen.ingame.StonecutterScreen
                || (Object) this instanceof net.minecraft.client.gui.screen.ingame.LoomScreen
                || (Object) this instanceof net.minecraft.client.gui.screen.ingame.MerchantScreen) {
            this.drawBackground(matrices, delta, mouseX, mouseY);
            return;
        }
        // Glass off: draw the vanilla container PNG unchanged.
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            this.drawBackground(matrices, delta, mouseX, mouseY);
            return;
        }

        int gl = this.x, gt = this.y, xs = this.backgroundWidth, ys = this.backgroundHeight;

        // Run the screen's OWN drawBackground with only its body-PNG blit replaced by the glass (26.2's
        // ContainerScreensGlassMixin contract): ContainerBodyBlit opens a one-call window in which the first full-width
        // body strip at (gl,gt) draws the ContainerGlass panel + lattice + drag + hover (exactly where the PNG went, so
        // any dim a screen draws inside drawBackground — cartography — stays underneath) and every body strip is dropped.
        // Everything else drawBackground paints stays vanilla on top: furnace flame/arrow, brewing progress, enchanting
        // book + options, anvil/grindstone field + error X, cartography maps, horse slot art, the survival player model
        // (so the old manual drawEntity redraw is gone). The previous path swallowed the whole call and lost all that.
        // Backdrop (R4): ContainerGlass reuses the frame-primary grabNow the in-world HUD took this frame (refreshed
        // every frame, never stale) and only grabs if nothing was captured yet — unchanged from before.
        List<Slot> slots = this.handler.slots;
        ContainerBodyBlit.begin(s1mp1e$glass, gl, gt, xs, ys, slots, this.cursorDragSlots, this.cursorDragging,
                mouseX, mouseY,
                (Object) this instanceof net.minecraft.client.gui.screen.ingame.AbstractFurnaceScreen);
        try {
            this.drawBackground(matrices, delta, mouseX, mouseY);
        } finally {
            ContainerBodyBlit.end();
        }
    }

    // Suppress vanilla's hovered-slot white highlight — the glass hover pill replaces
    // it. 1.17.1 refactored the highlight out of a raw fillGradient into the STATIC
    // HandledScreen.drawSlotHighlight(MatrixStack,int,int,int) (verified: render's
    // invokestatic ebn.a:(Ldql;III)V at offset 185, 0x80FFFFFF inside). @Redirect that
    // static INVOKE (no receiver param) and no-op it.
    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;"
                            + "drawSlotHighlight(Lnet/minecraft/client/util/math/MatrixStack;III)V"))
    private void s1mp1e$suppressSlotHighlight(MatrixStack matrices, int x, int y, int z) {
        // no-op: the glass hover pill is the highlight now (ContainerGlass, on every container incl. creative).
    }
}
