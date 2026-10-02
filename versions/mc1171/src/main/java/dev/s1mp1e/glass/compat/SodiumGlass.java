package dev.s1mp1e.glass.compat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.client.gui.Motion;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.GlassCorners;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.GuiFlush;
import dev.s1mp1e.glass.render.SceneCapture;
import me.jellysquid.mods.sodium.client.gui.options.Option;
import me.jellysquid.mods.sodium.client.gui.options.OptionGroup;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpact;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import me.jellysquid.mods.sodium.client.gui.options.control.Control;
import me.jellysquid.mods.sodium.client.gui.options.control.ControlElement;
import me.jellysquid.mods.sodium.client.gui.options.control.CyclingControl;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import me.jellysquid.mods.sodium.client.gui.widgets.FlatButtonWidget;
import me.jellysquid.mods.sodium.client.util.Dim2i;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

/**
 * Sodium's own Video Settings screen ({@code SodiumOptionsGUI}, Sodium 0.4.4 on 1.17.1) laid out and drawn like the 26.2 settings
 * screen (the glass restyle of Reese's Sodium Options, {@code RsoGlass}): a glass sidebar of pages with a sliding
 * selection capsule, the options in grouped glass cards with iOS switches / glass sliders, the title + donate row on
 * top and Undo / Apply / Done bottom right — all on the 8 px grid.
 *
 * <p>Sodium 0.4 / 0.5 / 0.6 has neither a sidebar nor cards (a tab row and one 240 px column of flat rows), so
 * restyling its widgets in place cannot reach that layout. Instead {@code SodiumScreenMixin} replaces
 * {@code rebuildGUI} (the placement of every widget) and {@code render} (all drawing) and this class does both.
 * Sodium's widgets stay the screen's children at our positions, so <b>every input path is Sodium's own</b> — clicks,
 * slider drags, Apply / Undo, the donation button; only where they sit and what is painted changes.
 *
 * <p><b>Sodium 0.4.4</b> (javap-read): its widgets draw through a {@code MatrixStack} and have no keyboard focus yet
 * (so no focus ring here), {@code FlatButtonWidget} has no label getter (exposed by the mixin), a slider element has
 * no "held" flag (tracked by the mixin from its {@code mouseClicked} + the mouse button state), the value formatter
 * returns a {@code String}, and there is no donation prompt.
 *
 * <p>Sodium has no scrolling. A page taller than the viewport scrolls here by re-placing the rows (the widgets'
 * rectangles are immutable, and their hit-tests use them): {@link #tickScroll} reports when the eased offset moved by
 * a whole pixel and the mixin rebuilds. Rows outside the viewport are not created, so nothing invisible is clickable.
 *
 * <p>One instance per screen. Animation state that must survive a rebuild is keyed by the {@link Option} (they live as
 * long as the page list). Render thread only. Loaded only when Sodium is (see {@link SodiumMixinPlugin}).
 */
public final class SodiumGlass {

    // ---- what the screen mixin exposes ----

    public interface Host {
        List<OptionPage> s1mp1e$pages();

        OptionPage s1mp1e$page();

        List<ControlElement<?>> s1mp1e$controls();

        /** The page buttons, in page order. */
        List<FlatButtonWidget> s1mp1e$tabs();

        FlatButtonWidget s1mp1e$undo();

        FlatButtonWidget s1mp1e$apply();

        FlatButtonWidget s1mp1e$close();

        FlatButtonWidget s1mp1e$donate();

        FlatButtonWidget s1mp1e$hideDonate();
    }

    /** {@code FlatButtonWidget}'s private state. */
    public interface FlatBtn {
        boolean s1mp1e$enabled();

        boolean s1mp1e$visible();

        Dim2i s1mp1e$dim();

        Text s1mp1e$label();
    }

    /** The integer slider element: is its thumb being dragged? */
    public interface SliderRow {
        boolean s1mp1e$held();
    }

    /** {@code SliderControl}'s private range + value formatter. */
    public interface SliderInfo {
        int s1mp1e$min();

        int s1mp1e$max();

        Text s1mp1e$format(int value);
    }

    /** {@code CyclingControl}'s private value names (indexed by the enum ordinal). */
    public interface CycleInfo {
        Text[] s1mp1e$names();
    }

    // ---- the 26.2 settings-screen grid ----

    /** Outer side margin, top / bottom margin, the one gap between any two pieces, row pitch, button size. */
    public static final int M = 16, TOP = 19, GAP = 8, ROW_H = 18, BTN_H = 20, BTN_W = 65;
    private static final int HEADER_H = 20, SIDE_PAD = 4;
    private static final float R = GlassCorners.HOTBAR_RADIUS + 2.0F;
    private static final int OFF_TRACK = 0x78788A, ON_TRACK = 0x34C759, BLUE = 0x0A84FF;
    private static final int TEXT = 0xFFFFFF, TEXT_DIM = 0xAEAEB2, TEXT_OFF = 0x8E8E93;

    private int width, height;
    private float sideX0, sideX1, bodyY0, bodyY1, contX0, contX1;
    private final List<float[]> cards = new ArrayList<>();     // {y0, y1} in content space (unscrolled, from bodyY0)
    private float contentH, maxScroll;
    private float scroll, scrollTarget;
    private int builtScroll;
    private OptionPage builtPage;
    private long pageNs;                                       // when the page last changed (0 = no cascade)
    private final Motion.Clock clock = new Motion.Clock();
    private final Motion.Spring tabSlide = new Motion.Spring(Motion.TRAVEL_S, 0.0F);
    private boolean tabPlaced;
    private Option<?> tipOption;
    private long tipNs;
    private final Fade tipFade = new Fade(0.0F, 120.0F);
    private Option<?> tipShown;

    private final WeakHashMap<Object, Fade> hovers = new WeakHashMap<>();
    private final WeakHashMap<Object, Fade> reveals = new WeakHashMap<>();
    private final WeakHashMap<Object, SwitchAnim> switches = new WeakHashMap<>();
    private final WeakHashMap<Object, Motion.Spring> knobs = new WeakHashMap<>();

    public static boolean usable() {
        return GlassProgram.ensureReady() && GlassProgram.usable() && GlassProgram.roundUsable();
    }

    // ============================================================================================================
    //  layout
    // ============================================================================================================

    /** Recompute the grid for this window and page. Called at the start of every rebuild. */
    public void layout(int w, int h, OptionPage page) {
        this.width = w;
        this.height = h;
        float sideW = Math.max(96.0F, Math.min(148.0F, w * 0.24F));
        sideX0 = M;
        sideX1 = M + sideW;
        bodyY0 = TOP + BTN_H + GAP;
        bodyY1 = h - TOP - BTN_H - GAP;
        contX0 = sideX1 + GAP;
        contX1 = w - M;

        cards.clear();
        float y = 0.0F;
        for (OptionGroup g : page.getGroups()) {
            int n = g.getOptions().size();
            if (n == 0) continue;
            cards.add(new float[]{y, y + n * ROW_H});
            y += n * ROW_H + GAP;
        }
        contentH = Math.max(0.0F, y - GAP);
        maxScroll = Math.max(0.0F, contentH - (bodyY1 - bodyY0));

        if (page != builtPage) {                               // a different page: back to the top, rows cascade in
            if (builtPage != null) pageNs = System.nanoTime();
            builtPage = page;
            scroll = scrollTarget = 0.0F;
            tipOption = null;
        }
        scrollTarget = clamp(scrollTarget, 0.0F, maxScroll);
        scroll = clamp(scroll, 0.0F, maxScroll);
        builtScroll = Math.round(scroll);
    }

    public Dim2i tabDim(int index) {
        return new Dim2i(Math.round(sideX0) + SIDE_PAD, Math.round(bodyY0) + HEADER_H + index * ROW_H,
                Math.round(sideX1 - sideX0) - 2 * SIDE_PAD, ROW_H);
    }

    /** The {@code row}-th option row of the page (counting through the groups), or null while it is scrolled out. */
    public Dim2i rowDim(int group, int rowInGroup) {
        if (group >= cards.size()) return null;
        int y = Math.round(bodyY0 + cards.get(group)[0]) + rowInGroup * ROW_H - builtScroll;
        if (y + ROW_H <= bodyY0 || y >= bodyY1) return null;
        return new Dim2i(Math.round(contX0), y, Math.round(contX1 - contX0), ROW_H);
    }

    public Dim2i closeDim() {
        return new Dim2i(width - M - BTN_W, height - TOP - BTN_H, BTN_W, BTN_H);
    }

    public Dim2i applyDim() {
        return new Dim2i(width - M - 2 * BTN_W - GAP, height - TOP - BTN_H, BTN_W, BTN_H);
    }

    public Dim2i undoDim() {
        return new Dim2i(width - M - 3 * BTN_W - 2 * GAP, height - TOP - BTN_H, BTN_W, BTN_H);
    }

    public Dim2i hideDonateDim() {
        return new Dim2i(width - M - BTN_H, TOP, BTN_H, BTN_H);
    }

    public Dim2i donateDim(int labelWidth) {
        int w = labelWidth + 24;
        return new Dim2i(width - M - BTN_H - GAP - w, TOP, w, BTN_H);
    }

    // ============================================================================================================
    //  scrolling
    // ============================================================================================================

    /** Wheel over the content: one row per notch. @return whether the wheel was over the content. */
    public boolean wheel(double mx, double my, double amount) {
        if (mx < contX0 || mx > contX1 || my < bodyY0 || my > bodyY1) return false;
        scrollTarget = clamp(scrollTarget - (float) amount * ROW_H * 2.0F, 0.0F, maxScroll);
        return true;
    }

    /** Ease the offset; true when it moved by a whole pixel, i.e. the rows must be re-placed. */
    public boolean tickScroll(float dt) {
        if (scroll != scrollTarget) {
            scroll += (scrollTarget - scroll) * (1.0F - (float) Math.exp(-dt / 0.09F));
            if (Math.abs(scrollTarget - scroll) < 0.25F) scroll = scrollTarget;
        }
        return Math.round(scroll) != builtScroll;
    }

    // ============================================================================================================
    //  drawing
    // ============================================================================================================

    public void draw(MatrixStack ctx, Host host, int mx, int my) {
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        float a = ScreenOpenFade.held() ? 1.0F : Math.max(0.001F, ScreenOpenFade.value(host));
        float dt = Math.min(0.05F, lastDt);

        GuiFlush.flush();   // land the blurred, dimmed background
        SceneCapture.grabNow();                                  // every glass piece below refracts it

        drawSidebar(ctx, tr, host, mx, my, a, dt);
        Option<?> hovered = drawContent(ctx, tr, host, mx, my, a);
        drawTopRow(ctx, tr, host, mx, my, a);
        drawButton(ctx, tr, host.s1mp1e$undo(), mx, my, a);
        drawButton(ctx, tr, host.s1mp1e$apply(), mx, my, a);
        drawButton(ctx, tr, host.s1mp1e$close(), mx, my, a);
        drawTooltip(ctx, tr, host, hovered, a);
    }

    private float lastDt = 1.0F / 60.0F;

    /** Call once per frame before {@link #tickScroll} / {@link #draw}. */
    public float frame() {
        lastDt = clock.tick();
        return lastDt;
    }

    // ---- sidebar: mod header + the page list with a sliding selection capsule ----

    private void drawSidebar(MatrixStack ctx, TextRenderer tr, Host host, int mx, int my, float a, float dt) {
        card(sideX0, bodyY0, sideX1, bodyY1, a, 0.16F);

        float ix = sideX0 + SIDE_PAD, iy = bodyY0 + 3.0F;
        Identifier icon = SodiumIcon.get();
        float nameX = ix + 6.0F;
        if (icon != null) {
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderTexture(0, icon);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, a);
            DrawableHelper.drawTexture(ctx, Math.round(ix), Math.round(iy), 14, 14, 0.0F, 0.0F, SodiumIcon.SIZE,
                    SodiumIcon.SIZE, SodiumIcon.SIZE, SodiumIcon.SIZE);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            nameX = ix + 18.0F;
        }
        // two lines beside the icon, as on the 26.2 screen: the mod's name, its version dimmer underneath
        text(ctx, tr, "Sodium", nameX, bodyY0 + 1.0F, TEXT, a);
        text(ctx, tr, SodiumIcon.version(), nameX, bodyY0 + 11.0F, TEXT_DIM, a);

        List<FlatButtonWidget> tabs = host.s1mp1e$tabs();
        int sel = Math.max(0, host.s1mp1e$pages().indexOf(host.s1mp1e$page()));
        if (!tabPlaced) {
            tabSlide.snap(sel);
            tabPlaced = true;
        }
        tabSlide.retarget(sel).update(dt);
        if (!tabs.isEmpty()) {
            Dim2i d0 = ((FlatBtn) tabs.get(0)).s1mp1e$dim();
            float y = d0.y() + tabSlide.x * ROW_H;
            GlassWidgets.capsule(ctx, d0.x() + 0.5F, y + 1.5F, d0.getLimitX() - 0.5F, y + ROW_H - 1.5F,
                    1.0F, 0.6F, a, true);
        }
        for (int i = 0; i < tabs.size(); i++) {
            FlatButtonWidget b = tabs.get(i);
            Dim2i d = ((FlatBtn) b).s1mp1e$dim();
            boolean over = d.containsCursor(mx, my);
            float near = Math.max(0.0F, 1.0F - Math.abs(tabSlide.x - i));
            float h = hover(hovers, b, over && i != sel);
            if (h > 0.01F) {
                GlassWidgets.fillRound(ctx, d.x() + 0.5F, d.y() + 1.5F, d.getLimitX() - 0.5F, d.getLimitY() - 1.5F,
                        (Math.round(h * a * 0x1C) << 24) | 0xFFFFFF, (ROW_H - 3) / 2.0F);
            }
            text(ctx, tr, ((FlatBtn) b).s1mp1e$label(), d.x() + 6.0F, d.getCenterY() - 4.0F,
                    HudGlass.lerpArgb(0xFF000000 | 0xD8D8DE, 0xFF000000 | TEXT, near) & 0xFFFFFF, a);
        }
    }

    // ---- content: the grouped cards and their rows ----

    private Option<?> drawContent(MatrixStack ctx, TextRenderer tr, Host host, int mx, int my, float a) {
        boolean clip = maxScroll > 0.5F;
        if (clip) {
            GuiFlush.flush();
            // the glass shadow reaches a little past a card, so only the scrolling axis is clipped tight
            dev.s1mp1e.client.gui.GuiScissor.enable(Math.round(contX0) - 14, Math.round(bodyY0), Math.round(contX1) + 14, Math.round(bodyY1));
        }
        float pageIn = pageNs == 0L ? 1.0F : enter((System.nanoTime() - pageNs) / 1.0e9F, 0.05F);
        float off = bodyY0 - scroll;
        for (float[] c : cards) {
            float y0 = off + c[0], y1 = off + c[1];
            if (y1 < bodyY0 - 16 || y0 > bodyY1 + 16) continue;
            card(contX0, y0, contX1, y1, a * pageIn, 0.30F);
            int div = (Math.round(a * pageIn * 0.10F * 255.0F) << 24) | 0xFFFFFF;
            for (float y = y0 + ROW_H; y < y1 - 1.0F; y += ROW_H) {
                GlassWidgets.fill(ctx, contX0 + 7.0F, y - 0.5F, contX1 - 7.0F, y + 0.5F, div);
            }
        }

        boolean inView = mx >= contX0 && mx <= contX1 && my >= bodyY0 && my <= bodyY1;
        float sub = builtScroll - scroll;                       // sub-pixel remainder: rows sit on whole pixels
        Option<?> hovered = null;
        List<ControlElement<?>> rows = host.s1mp1e$controls();
        for (ControlElement<?> e : rows) {
            Option<?> o = e.getOption();
            Dim2i d = e.getDimensions();
            boolean over = inView && d.containsCursor(mx, my);
            if (over) hovered = o;
            int index = indexOf(host.s1mp1e$page(), o);
            float in = pageNs == 0L ? 1.0F : enter((System.nanoTime() - pageNs) / 1.0e9F - 0.024F * index, 0.045F);
            if (in <= 0.004F) continue;
            drawRow(ctx, tr, e, o, d, over, a * in, sub + 8.0F * (1.0F - in));
        }
        if (pageNs != 0L && (System.nanoTime() - pageNs) / 1.0e9F > 0.024F * 40 + 0.6F) pageNs = 0L;
        if (clip) {
            GuiFlush.flush();
            dev.s1mp1e.client.gui.GuiScissor.disable();
        }
        return hovered;
    }

    private void drawRow(MatrixStack ctx, TextRenderer tr, ControlElement<?> e, Option<?> o, Dim2i d,
                         boolean over, float a, float dy) {
        boolean avail = o.isAvailable();
        float x0 = d.x(), x1 = d.getLimitX(), y0 = d.y() + dy, cy = y0 + ROW_H / 2.0F;
        boolean held = e instanceof SliderRow s && s.s1mp1e$held();
        float h = hover(hovers, o, avail && (over || held));
        if (h > 0.01F) {
            GlassWidgets.fillRound(ctx, x0 + 3.0F, y0 + 2.0F, x1 - 3.0F, y0 + ROW_H - 2.0F,
                    (Math.round(h * a * 0x20) << 24) | 0xFFFFFF, R - 2.0F);
        }

        // label — Sodium's conventions: changed = italic with a star, unavailable = grey strike-through
        Text name;
        if (!avail) name = o.getName().copy().formatted(Formatting.STRIKETHROUGH);
        else if (o.hasChanged()) name = new net.minecraft.text.LiteralText(o.getName().getString() + " *").formatted(Formatting.ITALIC);
        else name = o.getName();
        text(ctx, tr, name, x0 + 7.0F, cy - 4.0F, avail ? TEXT : TEXT_OFF, a);

        Control<?> c = o.getControl();
        float right = x1 - 6.0F;
        if (c instanceof TickBoxControl) {
            drawSwitch(ctx, o, avail && Boolean.TRUE.equals(o.getValue()), right, cy, avail ? a : a * 0.4F);
        } else if (c instanceof SliderControl && c instanceof SliderInfo si) {
            int v = (Integer) o.getValue();
            Text label = si.s1mp1e$format(v);
            int lw = tr.getWidth(label);
            // Sodium shows the slider only on the row in use; here it slides out and the value moves aside for it
            float show = reveal(o, avail && (over || held));
            float sx0 = right - 90.0F, sx1 = right;             // = Sodium's sliderBounds (its hit-test)
            float tx = lerp(right - lw, sx0 - 6.0F - lw, show);
            text(ctx, tr, label, tx, cy - 4.0F, avail ? TEXT : TEXT_OFF, a);
            if (show > 0.02F) {
                int range = Math.max(1, si.s1mp1e$max() - si.s1mp1e$min());
                float f = clamp((v - si.s1mp1e$min()) / (float) range, 0.0F, 1.0F);
                drawSlider(ctx, o, sx0, sx1, cy, f, held, a * show);
            }
        } else {
            Text value = null;
            if (c instanceof CyclingControl && c instanceof CycleInfo ci && o.getValue() instanceof Enum<?> en) {
                Text[] names = ci.s1mp1e$names();
                if (en.ordinal() < names.length) value = names[en.ordinal()];
            }
            if (value == null) value = new net.minecraft.text.LiteralText(String.valueOf(o.getValue()));
            text(ctx, tr, value, right - tr.getWidth(value), cy - 4.0F, avail ? TEXT : TEXT_OFF, a);
        }
    }

    // ---- top row: title left, donate + dismiss right ----

    private void drawTopRow(MatrixStack ctx, TextRenderer tr, Host host, int mx, int my, float a) {
        text(ctx, tr, new net.minecraft.text.TranslatableText("options.videoTitle"), M + 6.0F, TOP + BTN_H / 2.0F - 4.0F, TEXT, a);
        drawButton(ctx, tr, host.s1mp1e$donate(), mx, my, a);
        drawButton(ctx, tr, host.s1mp1e$hideDonate(), mx, my, a);
    }

    private void drawButton(MatrixStack ctx, TextRenderer tr, FlatButtonWidget b, int mx, int my, float a) {
        if (b == null) return;
        FlatBtn f = (FlatBtn) b;
        if (!f.s1mp1e$visible()) return;
        Dim2i d = f.s1mp1e$dim();
        boolean enabled = f.s1mp1e$enabled();
        float h = hover(hovers, b, enabled && d.containsCursor(mx, my));
        GlassWidgets.capsule(ctx, d.x(), d.y(), d.getLimitX(), d.getLimitY(),
                GlassCorners.hotbarCornerFrac(d.width(), d.height()), 0.5F * h + (enabled ? 0.05F : 0.0F), a, enabled);
        Text label = f.s1mp1e$label();
        text(ctx, tr, label, d.getCenterX() - tr.getWidth(label) / 2.0F, d.getCenterY() - 4.0F, TEXT,
                enabled ? a : a * 0.45F);
    }

    // ---- the hovered option's description: a glass card under (or above) its row, on top of everything ----

    private void drawTooltip(MatrixStack ctx, TextRenderer tr, Host host, Option<?> hovered, float a) {
        long now = System.nanoTime();
        if (hovered != tipOption) {
            tipOption = hovered;
            tipNs = now;
        }
        boolean want = tipOption != null && (now - tipNs) / 1.0e9F > 0.45F;
        if (want) tipShown = tipOption;
        tipFade.to(want ? 1.0F : 0.0F);
        float ta = tipFade.value() * a;
        if (ta < 0.01F || tipShown == null) return;

        Dim2i row = null;
        for (ControlElement<?> e : host.s1mp1e$controls()) if (e.getOption() == tipShown) row = e.getDimensions();
        if (row == null) return;

        float x0 = contX0 + 3.0F, x1 = contX1 - 3.0F;
        int wrap = Math.round(x1 - x0) - 14;
        List<OrderedText> lines = new ArrayList<>(tr.wrapLines(tipShown.getTooltip(), wrap));
        int bodyLines = lines.size();
        OptionImpact impact = tipShown.getImpact();
        if (impact != null) {
            lines.addAll(tr.wrapLines(new net.minecraft.text.TranslatableText("sodium.options.performance_impact_string",
                    impact.getLocalizedName()), wrap));
        }
        if (lines.isEmpty()) return;
        float hgt = lines.size() * 11.0F + 9.0F;
        float y0 = row.getLimitY() + 1.0F;
        if (y0 + hgt > height - TOP - BTN_H - 2.0F) y0 = row.y() - 1.0F - hgt;   // no room below: sit above the row

        GuiFlush.flush();   // the rows' text first; the card goes over it
        SceneCapture.grabNow();
        GlassRenderer.glass(x0, y0, x1, y0 + hgt, GlassRenderer.PAD_PANEL,
                GlassCorners.cornerFrac(x1 - x0, hgt, R), 0.0F, ta, GlassRenderer.FROST_PANEL);
        GlassRenderer.roundRect(x0, y0, x1, y0 + hgt, Math.min(R, hgt / 2.0F),
                (Math.round(ta * 0.80F * 255.0F) << 24) | 0x1C1C1E);
        for (int i = 0; i < lines.size(); i++) {
            drawOrdered(ctx, tr, lines.get(i), x0 + 7.0F, y0 + 5.0F + i * 11.0F, i < bodyLines ? TEXT : TEXT_DIM, ta);
        }
    }

    // ============================================================================================================
    //  pieces
    // ============================================================================================================

    /** A refractive glass card with the hotbar-family corner and a readability scrim. */
    private static void card(float x0, float y0, float x1, float y1, float alpha, float scrim) {
        if (alpha < 0.004F) return;
        float w = x1 - x0, h = y1 - y0, r = Math.min(R, Math.min(w, h) / 2.0F);
        if (SceneCapture.hasBackdrop()) {
            GlassRenderer.glass(x0, y0, x1, y1, GlassRenderer.PAD_PANEL, GlassCorners.cornerFrac(w, h, r), 0.0F,
                    alpha, GlassRenderer.FROST_PANEL);
            GlassRenderer.roundRect(x0, y0, x1, y1, r, (Math.round(alpha * scrim * 255.0F) << 24) | 0x1C1C1E);
        } else {
            GlassRenderer.roundRect(x0, y0, x1, y1, r, (Math.round(alpha * 0.58F * 255.0F) << 24) | 0x1C1C1E);
        }
    }

    private static final class SwitchAnim {
        final Motion.Spring travel = new Motion.Spring(Motion.TRAVEL_S, 0.0F);
        final Motion.Spring lift = new Motion.Spring(0.085F, 0.0F);
        final Motion.Clock clock = new Motion.Clock();
        boolean placed, lifted;
    }

    /** The iOS glass switch of the config screen (same springs), right-aligned at {@code right}. */
    private void drawSwitch(MatrixStack ctx, Object key, boolean value, float right, float cy, float alpha) {
        SwitchAnim s = switches.computeIfAbsent(key, k -> new SwitchAnim());
        float dt = s.clock.tick();
        float want = value ? 1.0F : 0.0F;
        if (!s.placed) {
            s.travel.snap(want);
            s.placed = true;
        }
        if (s.travel.target != want) {
            s.travel.retarget(want);
            s.lifted = true;
            s.lift.tune(0.085F, 0.0F).retarget(1.0F);
        }
        s.travel.update(dt);
        if (s.lifted && Math.abs(s.travel.target - s.travel.x) < 0.08F) {
            s.lifted = false;
            s.lift.tune(Motion.MORPH_OUT_S, 0.0F).retarget(0.0F);
        }
        s.lift.update(dt);

        int a = Math.round(alpha * 255.0F);
        float h = 12.0F, w = 26.0F;
        float x1 = right, x0 = x1 - w, y0 = cy - h / 2.0F, y1 = cy + h / 2.0F, r = h / 2.0F;
        float pos = Motion.clamp01(s.travel.x), morph = Motion.clamp01(s.lift.x);
        GlassWidgets.fillRound(ctx, x0, y0, x1, y1, ((int) (a * 0.55F) << 24) | OFF_TRACK, r);
        if (pos > 0.003F) GlassWidgets.fillRound(ctx, x0, y0, x1, y1, ((int) (a * pos) << 24) | ON_TRACK, r);
        float khh = 0.85F * h / 2.0F, khw = khh * 1.55F;
        float tx0 = x0 + 2.0F + khw, tx1 = x1 - 2.0F - khw;
        int band = HudGlass.lerpArgb(0x8C000000 | OFF_TRACK, 0xFF000000 | ON_TRACK, pos);
        GlassWidgets.knobLens(ctx, tx0 + (tx1 - tx0) * pos, cy, khw, khh, morph, 1.55F, 1.65F, 0.90F, x0, x1, r,
                Float.NaN, band, band, alpha);
    }

    /** The glass slider of the config screen: white track, blue fill, a knob that becomes a lens while held. */
    private void drawSlider(MatrixStack ctx, Object key, float x0, float x1, float cy, float frac, boolean held,
                            float alpha) {
        Motion.Spring lift = knobs.computeIfAbsent(key, k -> new Motion.Spring(Motion.MORPH_IN_S, 0.0F));
        lift.retarget(held ? 1.0F : 0.0F).update(Math.min(0.05F, lastDt));
        float th = 4.0F, kx = x0 + (x1 - x0) * frac;
        int a = Math.round(alpha * 255.0F);
        GlassWidgets.fillRound(ctx, x0, cy - th / 2.0F, x1, cy + th / 2.0F, ((int) (a * 0.30F) << 24) | 0xFFFFFF, th / 2.0F);
        GlassWidgets.fillRound(ctx, x0, cy - th / 2.0F, Math.max(x0 + th, kx), cy + th / 2.0F, (a << 24) | BLUE, th / 2.0F);
        GlassWidgets.knobLens(ctx, clamp(kx, x0 + 3.0F, x1 - 3.0F), cy, 6.5F, 4.0F, Motion.clamp01(lift.x), 1.5F, 1.6F, 0.9F,
                x0, x1, th / 2.0F, kx, 0xFF000000 | BLUE, 0x4DFFFFFF, alpha);
    }

    private static float hover(WeakHashMap<Object, Fade> map, Object key, boolean over) {
        Fade f = map.get(key);
        if (f == null) {
            f = new Fade(0.0F, 110.0F);
            map.put(key, f);
        }
        f.to(over ? 1.0F : 0.0F);
        return f.value();
    }

    private float reveal(Object key, boolean on) {
        Fade f = reveals.get(key);
        if (f == null) {
            f = new Fade(0.0F, 160.0F);
            reveals.put(key, f);
        }
        f.to(on ? 1.0F : 0.0F);
        float v = f.value();
        return v * v * (3.0F - 2.0F * v);
    }

    private static int indexOf(OptionPage page, Option<?> o) {
        int i = page.getOptions().indexOf(o);
        return Math.max(0, i);
    }

    /** Critically damped step response: 0 before {@code t = 0}, then a soft rise to 1 without overshoot. */
    private static float enter(float t, float tau) {
        if (t <= 0.0F) return 0.0F;
        float k = t / tau;
        return 1.0F - (1.0F + k) * (float) Math.exp(-k);
    }

    private static void text(MatrixStack ctx, TextRenderer tr, String s, float x, float y, int rgb, float alpha) {
        int a = Math.round(clamp(alpha, 0.0F, 1.0F) * 255.0F);
        if (a < 8) return;
        tr.draw(ctx, s, (float) Math.round(x), (float) Math.round(y), (a << 24) | (rgb & 0xFFFFFF));
    }

    private static void text(MatrixStack ctx, TextRenderer tr, Text s, float x, float y, int rgb, float alpha) {
        int a = Math.round(clamp(alpha, 0.0F, 1.0F) * 255.0F);
        if (a < 8) return;
        tr.draw(ctx, s, (float) Math.round(x), (float) Math.round(y), (a << 24) | (rgb & 0xFFFFFF));
    }

    private static void drawOrdered(MatrixStack ctx, TextRenderer tr, OrderedText s, float x, float y, int rgb,
                                    float alpha) {
        int a = Math.round(clamp(alpha, 0.0F, 1.0F) * 255.0F);
        if (a < 8) return;
        tr.draw(ctx, s, (float) Math.round(x), (float) Math.round(y), (a << 24) | (rgb & 0xFFFFFF));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ============================================================================================================
    //  the mod's own icon + version for the sidebar header
    // ============================================================================================================

    /** Sodium's icon (the {@code icon} of its fabric.mod.json), box-filtered down once to a small GUI texture. */
    private static final class SodiumIcon {
        static final int SIZE = 56;                              // 14 GUI px at GUI scale 4, a clean 2x at scale 2
        private static final Identifier ID = new Identifier("s1mp1e", "dyn/sodium_icon");
        private static boolean tried;
        private static boolean ok;
        private static String version;

        static Identifier get() {
            if (!tried) {
                tried = true;
                try {
                    Path p = FabricLoader.getInstance().getModContainer("sodium").flatMap(c ->
                            c.getMetadata().getIconPath(64).flatMap(c::findPath)).orElse(null);
                    if (p != null) {
                        try (InputStream in = Files.newInputStream(p); NativeImage src = NativeImage.read(in)) {
                            NativeImage dst = new NativeImage(SIZE, SIZE, true);
                            int sw = src.getWidth(), sh = src.getHeight();
                            for (int y = 0; y < SIZE; y++) {
                                for (int x = 0; x < SIZE; x++) {
                                    int ax0 = x * sw / SIZE, ax1 = Math.max(ax0 + 1, (x + 1) * sw / SIZE);
                                    int ay0 = y * sh / SIZE, ay1 = Math.max(ay0 + 1, (y + 1) * sh / SIZE);
                                    long r = 0, g = 0, b = 0, al = 0;
                                    int n = 0;
                                    for (int yy = ay0; yy < ay1; yy++) {
                                        for (int xx = ax0; xx < ax1; xx++) {
                                            int c = src.getColor(xx, yy);          // ABGR
                                            int ca = c >>> 24 & 0xFF;
                                            al += ca;
                                            r += (long) (c & 0xFF) * ca;
                                            g += (long) (c >>> 8 & 0xFF) * ca;
                                            b += (long) (c >>> 16 & 0xFF) * ca;
                                            n++;
                                        }
                                    }
                                    int oa = (int) (al / n);
                                    int or = al == 0 ? 0 : (int) (r / al), og = al == 0 ? 0 : (int) (g / al);
                                    int ob = al == 0 ? 0 : (int) (b / al);
                                    dst.setColor(x, y, oa << 24 | ob << 16 | og << 8 | or);
                                }
                            }
                            NativeImageBackedTexture tex = new NativeImageBackedTexture(dst);
                            tex.setFilter(true, false);
                            MinecraftClient.getInstance().getTextureManager().registerTexture(ID, tex);
                            ok = true;
                        }
                    }
                } catch (Throwable t) {
                    System.out.println("[S1mp1e] Sodium icon unavailable: " + t);
                }
            }
            return ok ? ID : null;
        }

        static String version() {
            if (version == null) {
                String v = "";
                try {
                    v = FabricLoader.getInstance().getModContainer("sodium")
                            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
                    int plus = v.indexOf('+');
                    if (plus > 0) v = v.substring(0, plus);
                } catch (Throwable ignored) {
                }
                version = v;
            }
            return version;
        }
    }
}
