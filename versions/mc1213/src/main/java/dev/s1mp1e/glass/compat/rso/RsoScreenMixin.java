package dev.s1mp1e.glass.compat.rso;

import com.google.common.collect.ImmutableList;
import dev.s1mp1e.glass.compat.SodiumGlass;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import me.flashyreese.mods.reeses_sodium_options.client.gui.SodiumVideoOptionsScreen;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.gui.options.Option;
import net.caffeinemc.mods.sodium.client.gui.options.OptionGroup;
import net.caffeinemc.mods.sodium.client.gui.options.OptionPage;
import net.caffeinemc.mods.sodium.client.gui.options.control.ControlElement;
import net.caffeinemc.mods.sodium.client.gui.prompt.ScreenPrompt;
import net.caffeinemc.mods.sodium.client.gui.widgets.FlatButtonWidget;
import net.caffeinemc.mods.sodium.client.util.Dim2i;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reese's Sodium Options 1.8's Video Settings screen, placed and drawn by {@link SodiumGlass} exactly like Sodium's own
 * screen ({@code SodiumScreenMixin}): the glass sidebar of pages, the options in glass cards, Undo / Apply / Done and the
 * donate row. RSO 1.8 forked Sodium's screen — the same pages, the same {@code FlatButtonWidget}s and the same private
 * {@code updateControls} / {@code applyChanges} / {@code undoChanges} / donation methods — and only adds its frames
 * (tab rail, scrolling page, search field) around Sodium's option elements, so the whole screen is taken over the same
 * way: {@code init} (where everything goes; RSO's {@code rebuildUI} is {@code clearAndInit}) and {@code render}.
 * Sodium's option elements stay the screen's children, so every input path is Sodium's.
 *
 * <p>RSO's search stays: a vanilla text field (S1mp1e's glass box and typing animation) in the top row filters every
 * page's options by name into a results page that is not in the sidebar list (no tab selected), grouped by the page
 * each hit comes from; picking a tab clears it. Typing re-places the rows directly (not through {@code clearAndInit},
 * which would blur the box after each key). The last page is remembered across openings in RSO's own static.
 *
 * <p>{@code init}, {@code render} and {@code keyPressed} are inherited from {@code Screen}, so the production jar names
 * them by intermediary and the dev runtime by yarn; RSO is not in the mappings, so both names are listed and
 * {@code remap} stays off. Applied only with Sodium and RSO installed ({@code RsoMixinPlugin}).
 */
@Mixin(value = SodiumVideoOptionsScreen.class, remap = false)
public abstract class RsoScreenMixin extends Screen implements SodiumGlass.Host {

    protected RsoScreenMixin(Text title) {
        super(title);
    }

    @Shadow @Final private List<OptionPage> pages;
    @Shadow private FlatButtonWidget applyButton;
    @Shadow private FlatButtonWidget closeButton;
    @Shadow private FlatButtonWidget undoButton;
    @Shadow private FlatButtonWidget donateButton;
    @Shadow private FlatButtonWidget hideDonateButton;
    @Shadow private ScreenPrompt prompt;
    @Shadow @Final private static AtomicReference<Text> tabFrameSelectedTab;

    @Shadow private void updateControls() { throw new AssertionError(); }

    @Shadow private void applyChanges() { throw new AssertionError(); }

    @Shadow private void undoChanges() { throw new AssertionError(); }

    @Shadow private void openDonationPage() { throw new AssertionError(); }

    @Shadow private void hideDonationButton() { throw new AssertionError(); }

    @Shadow private void setDonationButtonVisibility(boolean value) { throw new AssertionError(); }

    @Unique private SodiumGlass s1mp1e$glass;
    @Unique private List<FlatButtonWidget> s1mp1e$tabs;
    @Unique private final List<ControlElement<?>> s1mp1e$controls = new ArrayList<>();
    @Unique private OptionPage s1mp1e$page;                  // the selected page
    @Unique private TextFieldWidget s1mp1e$search;           // one box for the screen's life: text and focus survive re-placing
    @Unique private String s1mp1e$query = "";
    @Unique private OptionPage s1mp1e$results;               // the hits as a page, replaced only when the hits change
    @Unique private List<Option<?>> s1mp1e$hits = List.of();

    @Unique
    private SodiumGlass s1mp1e$glass() {
        if (this.s1mp1e$glass == null) this.s1mp1e$glass = new SodiumGlass();
        return this.s1mp1e$glass;
    }

    @Inject(method = {"init", "method_25426"}, at = @At("HEAD"), cancellable = true)
    private void s1mp1e$init(CallbackInfo ci) {
        if (!SodiumGlass.usable()) return;                       // no glass programs: RSO's own screen, untouched
        ci.cancel();
        this.s1mp1e$place();
        if (this.prompt != null) this.prompt.init();
    }

    @Unique
    private void s1mp1e$place() {
        this.s1mp1e$controls.clear();
        this.clearChildren();
        OptionPage page = this.s1mp1e$shown();
        SodiumGlass g = s1mp1e$glass();
        g.layout(this.width, this.height, page);

        this.undoButton = new FlatButtonWidget(g.undoDim(), Text.translatable("sodium.options.buttons.undo"), this::undoChanges);
        this.applyButton = new FlatButtonWidget(g.applyDim(), Text.translatable("sodium.options.buttons.apply"), this::applyChanges);
        this.closeButton = new FlatButtonWidget(g.closeDim(), Text.translatable("gui.done"), this::close);
        Text donate = Text.translatable("sodium.options.buttons.donate");
        int donateW = this.textRenderer.getWidth(donate);
        this.donateButton = new FlatButtonWidget(g.donateDim(donateW), donate, this::openDonationPage);
        this.hideDonateButton = new FlatButtonWidget(g.hideDonateDim(), Text.literal("x"), this::hideDonationButton);
        if (SodiumClientMod.options().notifications.hasClearedDonationButton) this.setDonationButtonVisibility(false);
        this.addDrawableChild(this.undoButton);
        this.addDrawableChild(this.applyButton);
        this.addDrawableChild(this.closeButton);
        this.addDrawableChild(this.donateButton);
        this.addDrawableChild(this.hideDonateButton);

        Dim2i sd = g.searchDim(donateW, ((SodiumGlass.FlatBtn) this.donateButton).s1mp1e$visible());
        if (this.s1mp1e$search == null) {
            Text hint = Text.translatable("rso.search_bar_empty");
            this.s1mp1e$search = new TextFieldWidget(this.textRenderer, sd.x(), sd.y(), sd.width(), sd.height(), hint);
            this.s1mp1e$search.setPlaceholder(hint);
            this.s1mp1e$search.setMaxLength(64);
            this.s1mp1e$search.setChangedListener(this::s1mp1e$onSearch);
        } else {
            this.s1mp1e$search.setX(sd.x());
            this.s1mp1e$search.setY(sd.y());
            this.s1mp1e$search.setWidth(sd.width());
        }
        this.addDrawableChild(this.s1mp1e$search);

        List<FlatButtonWidget> tabs = new ArrayList<>();
        for (int i = 0; i < this.pages.size(); i++) {
            OptionPage p = this.pages.get(i);
            FlatButtonWidget tab = new FlatButtonWidget(g.tabDim(i), p.getName(), () -> this.s1mp1e$select(p));
            tab.setSelected(page == p);
            tabs.add(tab);
            this.addDrawableChild(tab);
        }
        this.s1mp1e$tabs = tabs;

        int gi = 0;
        for (OptionGroup group : page.getGroups()) {
            if (group.getOptions().isEmpty()) continue;
            int ri = 0;
            for (Option<?> option : group.getOptions()) {
                Dim2i dim = g.rowDim(gi, ri++);
                if (dim == null) continue;                       // scrolled out of the viewport
                ControlElement<?> element = option.getControl().createElement(dim);
                this.addDrawableChild(element);
                this.s1mp1e$controls.add(element);
            }
            gi++;
        }
    }

    /** The selected page: first opening RSO's remembered tab, else the first page. */
    @Unique
    private OptionPage s1mp1e$selected() {
        if (this.s1mp1e$page == null) {
            Text last = tabFrameSelectedTab.get();
            if (last != null) {
                for (OptionPage p : this.pages) if (p.getName().getString().equals(last.getString())) this.s1mp1e$page = p;
            }
            if (this.s1mp1e$page == null) {
                if (this.pages.isEmpty()) throw new IllegalStateException("No pages are available?!");
                this.s1mp1e$page = this.pages.get(0);
            }
        }
        return this.s1mp1e$page;
    }

    /** What the content shows: the hits while there is a search, else the selected page. */
    @Unique
    private OptionPage s1mp1e$shown() {
        return this.s1mp1e$query.isEmpty() || this.s1mp1e$results == null ? this.s1mp1e$selected() : this.s1mp1e$results;
    }

    @Unique
    private void s1mp1e$select(OptionPage page) {
        this.s1mp1e$page = page;
        tabFrameSelectedTab.set(page.getName());
        if (this.s1mp1e$search != null && !this.s1mp1e$search.getText().isEmpty()) {
            this.s1mp1e$search.setText("");                     // → s1mp1e$onSearch(""), which re-places
            return;
        }
        this.s1mp1e$place();
    }

    @Unique
    private void s1mp1e$onSearch(String text) {
        String q = text.trim().toLowerCase(Locale.ROOT);
        if (q.equals(this.s1mp1e$query)) return;
        this.s1mp1e$query = q;
        if (!q.isEmpty()) {
            List<Option<?>> hits = new ArrayList<>();
            ImmutableList.Builder<OptionGroup> groups = ImmutableList.builder();
            for (OptionPage p : this.pages) {
                OptionGroup.Builder group = null;
                for (Option<?> o : p.getOptions()) {
                    if (!o.getName().getString().toLowerCase(Locale.ROOT).contains(q)) continue;
                    if (group == null) group = OptionGroup.createBuilder();
                    group.add(o);
                    hits.add(o);
                }
                if (group != null) groups.add(group.build());   // one card per page the hits come from
            }
            if (this.s1mp1e$results == null || !hits.equals(this.s1mp1e$hits)) {
                this.s1mp1e$hits = hits;
                this.s1mp1e$results = new OptionPage(Text.translatable("rso.search_bar_empty"), groups.build());
            }
        }
        this.s1mp1e$place();
    }

    @Inject(method = {"render", "method_25394"}, at = @At("HEAD"), cancellable = true)
    private void s1mp1e$draw(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!SodiumGlass.usable() || this.s1mp1e$tabs == null) return;
        ci.cancel();
        SodiumGlass g = s1mp1e$glass();
        if (g.tickScroll(g.frame())) this.s1mp1e$place();         // the scroll moved a whole pixel: re-place the rows
        this.updateControls();
        boolean blocked = this.prompt != null;
        int mx = blocked ? -1 : mouseX, my = blocked ? -1 : mouseY;
        this.renderBackground(ctx, mx, my, delta);
        g.draw(ctx, this, mx, my);
        if (this.s1mp1e$search != null) this.s1mp1e$search.render(ctx, mx, my, delta);
        if (blocked) this.prompt.render(ctx, mouseX, mouseY, delta);
    }

    /** RSO's Shift+P (vanilla video settings) knows only its own search field: in ours a capital P must type. */
    @Inject(method = {"keyPressed", "method_25404"}, at = @At("HEAD"), cancellable = true)
    private void s1mp1e$searchKeys(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (this.prompt == null && keyCode == GLFW.GLFW_KEY_P && this.s1mp1e$search != null && this.s1mp1e$search.isFocused()) {
            cir.setReturnValue(this.s1mp1e$search.keyPressed(keyCode, scanCode, modifiers));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (this.prompt == null && this.s1mp1e$glass != null && this.s1mp1e$glass.wheel(mouseX, mouseY, verticalAmount)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override public List<OptionPage> s1mp1e$pages() { return this.pages; }

    @Override public OptionPage s1mp1e$page() { return this.s1mp1e$shown(); }

    @Override public List<ControlElement<?>> s1mp1e$controls() { return this.s1mp1e$controls; }

    @Override public List<FlatButtonWidget> s1mp1e$tabs() { return this.s1mp1e$tabs; }

    @Override public FlatButtonWidget s1mp1e$undo() { return this.undoButton; }

    @Override public FlatButtonWidget s1mp1e$apply() { return this.applyButton; }

    @Override public FlatButtonWidget s1mp1e$close() { return this.closeButton; }

    @Override public FlatButtonWidget s1mp1e$donate() { return this.donateButton; }

    @Override public FlatButtonWidget s1mp1e$hideDonate() { return this.hideDonateButton; }

    @Override public TextFieldWidget s1mp1e$search() { return this.s1mp1e$search; }

    @Override
    public Text s1mp1e$emptyText() {
        String lang = MinecraftClient.getInstance().getLanguageManager().getLanguage();
        return Text.literal(lang != null && lang.startsWith("zh") ? "沒有符合的選項" : "No matching options");
    }
}
