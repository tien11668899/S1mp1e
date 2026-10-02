package dev.s1mp1e.glass.compat.sodium;

import java.util.ArrayList;
import java.util.List;

import dev.s1mp1e.glass.compat.SodiumGlass;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI;
import me.jellysquid.mods.sodium.client.gui.options.Option;
import me.jellysquid.mods.sodium.client.gui.options.OptionGroup;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import me.jellysquid.mods.sodium.client.gui.options.control.ControlElement;
import me.jellysquid.mods.sodium.client.gui.widgets.FlatButtonWidget;
import me.jellysquid.mods.sodium.client.util.Dim2i;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium's Video Settings screen, placed and drawn like the 26.2 settings screen — see {@link SodiumGlass}.
 *
 * <p>Two replacements: {@code rebuildGUI} (where every widget goes) and {@code render} (everything that is painted).
 * The widgets stay Sodium's and stay the screen's children, so input and Apply / Undo are untouched. Buttons are
 * added before the rows: a row partly scrolled under the bottom bar must not swallow a button click.
 *
 * <p>Sodium 0.4.4 (the 1.19.2 build, package {@code me.jellysquid.mods.sodium}) has the same screen structure as the
 * 0.5 / 0.6 ones the newer lines target — the same fields, {@code rebuildGUI} (which here calls
 * {@code rebuildGUIPages} + {@code rebuildGUIOptions}: both are skipped with it), {@code FlatButtonWidget}, the
 * {@code Dim2i} record — verified with javap on the jar. Differences: no donation prompt, the "donation button
 * dismissed" flag is {@code notifications.hideDonationButton}, the widgets render through a {@code MatrixStack}, and
 * the screen has no {@code mouseScrolled} of its own, so the override below is new.
 *
 * <p>{@code render} is inherited from {@code Screen}, so it carries the intermediary name in a production jar and the
 * yarn name in the dev runtime; Sodium is not in the mappings, so both names are listed and {@code remap} stays off.
 * The class extends {@code Screen} so the {@code Screen} calls and the {@code mouseScrolled} override are remapped
 * with the rest of the jar.
 */
@Mixin(value = SodiumOptionsGUI.class, remap = false)
public abstract class SodiumScreenMixin extends Screen implements SodiumGlass.Host {

    protected SodiumScreenMixin(Text title) {
        super(title);
    }

    @Shadow @Final private List<OptionPage> pages;
    @Shadow @Final private List<ControlElement<?>> controls;
    @Shadow private OptionPage currentPage;
    @Shadow private FlatButtonWidget applyButton;
    @Shadow private FlatButtonWidget closeButton;
    @Shadow private FlatButtonWidget undoButton;
    @Shadow private FlatButtonWidget donateButton;
    @Shadow private FlatButtonWidget hideDonateButton;

    @Shadow public abstract void setPage(OptionPage page);

    @Shadow private void rebuildGUI() { throw new AssertionError(); }

    @Shadow private void updateControls() { throw new AssertionError(); }

    @Shadow private void applyChanges() { throw new AssertionError(); }

    @Shadow private void undoChanges() { throw new AssertionError(); }

    @Shadow private void openDonationPage() { throw new AssertionError(); }

    @Shadow private void hideDonationButton() { throw new AssertionError(); }

    @Shadow private void setDonationButtonVisibility(boolean value) { throw new AssertionError(); }

    @Unique private SodiumGlass s1mp1e$glass;
    @Unique private List<FlatButtonWidget> s1mp1e$tabs;

    @Unique
    private SodiumGlass s1mp1e$glass() {
        if (this.s1mp1e$glass == null) this.s1mp1e$glass = new SodiumGlass();
        return this.s1mp1e$glass;
    }

    @Inject(method = "rebuildGUI", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$place(CallbackInfo ci) {
        if (!SodiumGlass.usable()) return;                       // no glass programs: Sodium's own screen, untouched
        ci.cancel();
        this.controls.clear();
        this.clearChildren();
        if (this.currentPage == null) {
            if (this.pages.isEmpty()) throw new IllegalStateException("No pages are available?!");
            this.currentPage = this.pages.get(0);
        }
        SodiumGlass g = s1mp1e$glass();
        g.layout(this.width, this.height, this.currentPage);

        this.undoButton = new FlatButtonWidget(g.undoDim(), Text.translatable("sodium.options.buttons.undo"), this::undoChanges);
        this.applyButton = new FlatButtonWidget(g.applyDim(), Text.translatable("sodium.options.buttons.apply"), this::applyChanges);
        this.closeButton = new FlatButtonWidget(g.closeDim(), Text.translatable("gui.done"), this::close);
        Text donate = Text.translatable("sodium.options.buttons.donate");
        this.donateButton = new FlatButtonWidget(g.donateDim(this.textRenderer.getWidth(donate)), donate, this::openDonationPage);
        this.hideDonateButton = new FlatButtonWidget(g.hideDonateDim(), Text.literal("x"), this::hideDonationButton);
        if (SodiumClientMod.options().notifications.hideDonationButton) this.setDonationButtonVisibility(false);
        this.addDrawableChild(this.undoButton);
        this.addDrawableChild(this.applyButton);
        this.addDrawableChild(this.closeButton);
        this.addDrawableChild(this.donateButton);
        this.addDrawableChild(this.hideDonateButton);

        List<FlatButtonWidget> tabs = new ArrayList<>();
        for (int i = 0; i < this.pages.size(); i++) {
            OptionPage page = this.pages.get(i);
            FlatButtonWidget tab = new FlatButtonWidget(g.tabDim(i), page.getName(), () -> this.setPage(page));
            tab.setSelected(this.currentPage == page);
            tabs.add(tab);
            this.addDrawableChild(tab);
        }
        this.s1mp1e$tabs = tabs;

        int gi = 0;
        for (OptionGroup group : this.currentPage.getGroups()) {
            if (group.getOptions().isEmpty()) continue;
            int ri = 0;
            for (Option<?> option : group.getOptions()) {
                Dim2i dim = g.rowDim(gi, ri++);
                if (dim == null) continue;                       // scrolled out of the viewport
                ControlElement<?> element = option.getControl().createElement(dim);
                this.addDrawableChild(element);
                this.controls.add(element);
            }
            gi++;
        }
    }

    @Inject(method = {"render", "method_25394"}, at = @At("HEAD"), cancellable = true)
    private void s1mp1e$draw(MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!SodiumGlass.usable() || this.s1mp1e$tabs == null) return;
        ci.cancel();
        SodiumGlass g = s1mp1e$glass();
        if (g.tickScroll(g.frame())) this.rebuildGUI();          // the scroll moved a whole pixel: re-place the rows
        this.updateControls();
        this.renderBackground(matrices);   // 1.19.2: no blur — the dimmed world (or the menu background)
        g.draw(matrices, this, mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {   // 1.19.2: one wheel axis
        if (this.s1mp1e$glass != null && this.s1mp1e$glass.wheel(mouseX, mouseY, amount)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override public List<OptionPage> s1mp1e$pages() { return this.pages; }

    @Override public OptionPage s1mp1e$page() { return this.currentPage; }

    @Override public List<ControlElement<?>> s1mp1e$controls() { return this.controls; }

    @Override public List<FlatButtonWidget> s1mp1e$tabs() { return this.s1mp1e$tabs; }

    @Override public FlatButtonWidget s1mp1e$undo() { return this.undoButton; }

    @Override public FlatButtonWidget s1mp1e$apply() { return this.applyButton; }

    @Override public FlatButtonWidget s1mp1e$close() { return this.closeButton; }

    @Override public FlatButtonWidget s1mp1e$donate() { return this.donateButton; }

    @Override public FlatButtonWidget s1mp1e$hideDonate() { return this.hideDonateButton; }
}
