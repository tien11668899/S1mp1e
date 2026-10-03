package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.GlassGlideHost;
import dev.s1mp1e.glass.render.ContainerBodyBlit;
import dev.s1mp1e.glass.render.ContainerGlass;
import dev.s1mp1e.glass.render.GlassBackgroundHost;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.inventory.slot.Slot;
import net.minecraft.screen.ScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * System 2 (screenHandler glass) — frosted panel + slot lattice + quick-craft drag highlight + hover pill, plus the shared
 * sub-pixel grid glide (D) for a {@link GlassGlideHost}. The 1.13.2 Fabric port of {@code GlassContainerHandler}
 * (itself LiquidGlass26's {@code GlassPanels} + {@code ContainerScreensGlassMixin}). The panel/lattice/hover body was
 * lifted into {@link ContainerGlass} so the screens that must run their OWN {@code drawBackground} (creative,
 * stonecutter, loom — their tabs / recipe / pattern lists live inside it) draw the SAME glass from a redirect instead of
 * the generic swallow erasing their whole background (the KNOWN BLOCKER that erased the stonecutter / loom lists).
 *
 * <h3>Injection point</h3>
 * {@code HandledScreen.render}'s very first {@code INVOKE} is the abstract {@code drawBackground(MatrixStack,FII)}, and
 * the dark dim gradient lives INSIDE each concrete {@code drawBackground}. So {@code shift = BEFORE} the
 * {@code drawBackground} {@code INVOKE} (a {@code @Redirect} swallowing it) is the behaviour-matching seam. At that
 * instruction no {@code translate(x,y)} is active yet (render's {@code pushMatrix}/{@code translatef} run later), so
 * drawing at the absolute {@code this.x / this.y} panel origin is correct.
 *
 * <p>Nothing is swallowed any more. The creative / stonecutter / loom screens run their own {@code drawBackground} with
 * their per-screen mixin redirecting the body blit to glass (keeping the recipe / pattern list drawn, glassing the
 * scroller and gliding the content). Every other screenHandler also runs its own {@code drawBackground}, inside a
 * {@link ContainerBodyBlit} window that replaces ONLY the body-PNG blit with {@link ContainerGlass} (26.2's
 * {@code ContainerScreensGlassMixin} contract), so everything else the screen paints stays vanilla on top.
 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenGlassMixin implements GlassBackgroundHost {

    // ---- yarn shadows ------------------------------------------------------
    @Shadow protected int x;
    @Shadow protected int y;
    @Shadow protected int backgroundWidth;
    @Shadow protected int backgroundHeight;
    @Shadow protected ScreenHandler screenHandler;
    @Shadow protected java.util.Set<Slot> cursorDragSlots;
    @Shadow protected boolean isCursorDragging;
    @Shadow protected abstract void drawBackground(float delta, int mouseX, int mouseY);
    @Shadow private void drawSlot(Slot slot) { throw new AssertionError(); }
    @Shadow private boolean method_18681(Slot slot, double pointX, double pointY) { throw new AssertionError(); }

    // ---- per-screen-instance screenHandler-glass state (fresh with each opened screenHandler) ------
    private final ContainerGlass.State s1mp1e$glass = new ContainerGlass.State();

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;drawBackground(FII)V"))
    private void s1mp1e$glassPanel(HandledScreen self, float delta,
                                   int mouseX, int mouseY) {
        this.s1mp1e$glassBackground(delta, mouseX, mouseY);
    }

    /**
     * The glassed {@code drawBackground}: also called (via {@link GlassBackgroundHost}) by the screens whose own
     * {@code render} invokes {@code drawBackground} directly — the narrow recipe-book branch of the inventory / crafting
     * / furnace screens — so that background is glass too instead of the vanilla PNG.
     */
    @Override
    public void s1mp1e$glassBackground(float delta, int mouseX, int mouseY) {
        Object self = this;
        // Creative / stonecutter / loom run their OWN drawBackground so their per-screen mixin can @Redirect the body
        // blit to glass (fused tabs on creative; the recipe / pattern list stays drawn), turn the scroller into the
        // glass scrollbar and glide the grid. Their body redirect draws the panel over the vanilla dim.
        if (self instanceof net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen) {
            this.drawBackground(delta, mouseX, mouseY);
            return;
        }
        // Glass off: draw the vanilla container PNG unchanged.
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            this.drawBackground(delta, mouseX, mouseY);
            return;
        }

        int gl = this.x, gt = this.y, xs = this.backgroundWidth, ys = this.backgroundHeight;
        // Run the screen's OWN drawBackground with only its body-PNG blit replaced by the glass (26.2's
        // ContainerScreensGlassMixin contract, the mc1171 ContainerBodyBlit port): the first full-width body strip at
        // (gl,gt) draws the ContainerGlass panel + lattice + drag + hover exactly where the PNG went and every body strip
        // is dropped; everything else drawBackground paints stays vanilla on top — furnace flame/arrow, brewing
        // progress, enchanting book + options, anvil/grindstone field + error X, cartography maps, merchant out-of-stock
        // X, horse slot art + preview, the survival player model (so the old manual drawEntity redraw is gone). The
        // previous path swallowed the whole call and lost all of that. R4: ContainerGlass keeps its unconditional
        // frame-primary grabNow() (the body blit is the first draw of drawBackground -> world + dim only).
        ContainerBodyBlit.begin(s1mp1e$glass, gl, gt, xs, ys, this.screenHandler.slots, this.cursorDragSlots,
                this.isCursorDragging, mouseX, mouseY,
                self instanceof net.minecraft.client.gui.screen.ingame.FurnaceScreen);
        try {
            this.drawBackground(delta, mouseX, mouseY);
        } finally {
            ContainerBodyBlit.end();
        }
    }

    // Suppress vanilla's white slot-hover box (the "two highlights"): our glass pill replaces it. The highlight is the
    // sole fillGradient(MatrixStack,IIIIII) INVOKE in render, owner HandledScreen (javac emits the receiver's class).
    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;fillGradient(IIIIII)V"))
    private void s1mp1e$noVanillaHighlight(HandledScreen self, int x1, int y1,
                                           int x2, int y2, int c1, int c2) {
        // swallow — the glass hover pill is the only highlight
    }

    // ---- (D) sub-pixel grid glide, shared for any GlassGlideHost (only the creative screen; no-op otherwise) --------

    /** Skip vanilla's own drawing of a scrolling grid slot while the host draws that content itself (glide overlay). */
    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;drawSlot(Lnet/minecraft/inventory/slot/Slot;)V"))
    private void s1mp1e$glideSlot(HandledScreen self, Slot slot) {
        if (self instanceof GlassGlideHost && ((GlassGlideHost) self).s1mp1e$gliding()
                && ((GlassGlideHost) self).s1mp1e$isGlideSlot(slot)) {
            return;   // suppressed: the host's eased overlay draws this content
        }
        this.drawSlot(slot);   // self == this; call the shadow
    }

    /** Null the hovered grid slot during a glide so no mismatched highlight / tooltip is drawn (render-only). */
    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;method_18681(Lnet/minecraft/inventory/slot/Slot;DD)Z"))
    private boolean s1mp1e$glideHover(HandledScreen self, Slot slot, double px, double py) {
        if (self instanceof GlassGlideHost && ((GlassGlideHost) self).s1mp1e$gliding()
                && ((GlassGlideHost) self).s1mp1e$isGlideSlot(slot)) {
            return false;
        }
        return this.method_18681(slot, px, py);   // self == this; call the shadow
    }

    /** Draw the host's eased grid overlay inside the same (x,y,0)-translated matrix vanilla drew the slots in. */
    @Inject(method = "render",
            at = @At(value = "INVOKE", shift = At.Shift.BEFORE,
                     target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;drawForeground(II)V"))
    private void s1mp1e$glideOverlay(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if ((Object) this instanceof GlassGlideHost && ((GlassGlideHost) (Object) this).s1mp1e$gliding()) {
            ((GlassGlideHost) (Object) this).s1mp1e$drawGlideOverlay();
        }
    }

    /** Drop the list screens' vanilla scrollbar-drag flag when the button is released (held glass lens ends). The
     *  stonecutter / loom / merchant screens never clear it themselves, so the glass thumb would keep tracking the
     *  cursor after release without this. */
    @Inject(method = "mouseReleased", at = @At("HEAD"))
    private void s1mp1e$endScrollDrag(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof dev.s1mp1e.client.gui.ScrollDragOwner) {
            ((dev.s1mp1e.client.gui.ScrollDragOwner) (Object) this).s1mp1e$endScrollDrag();
        }
    }
}
