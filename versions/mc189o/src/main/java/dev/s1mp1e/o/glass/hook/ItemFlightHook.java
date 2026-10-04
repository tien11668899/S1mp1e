package dev.s1mp1e.o.glass.hook;

import net.minecraft.client.Minecraft;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.gui.screen.inventory.menu.InventoryMenuScreen;
import net.minecraft.client.gui.screen.inventory.menu.CreativeInventoryScreen;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.platform.Lighting;
import net.minecraft.client.render.entity.ItemRenderer;
import net.minecraft.inventory.slot.InventorySlot;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Items fly between slots instead of teleporting — whenever a stack moves from one slot to another WITHOUT riding the
 * cursor (PORT_DELTA_ITEM_FLIGHT): shift-click quick-move, number-key swaps, and moves made for the player (Item
 * Scroller's drag/scroll moves, the recipe book filling the grid on 1.12+, a trade being laid out, a result
 * shift-crafted into the inventory). A stack picked up and put down by hand appears as in vanilla. 1.8.9 Forge coremod
 * port of mc1122's {@code ItemFlightHook} (itself the coremod port of mc1144's {@code ItemFlightMixin} /
 * {@code ItemFlights}): no click hooks — a per-frame slot diff.
 *
 * <p><b>1.8.9 adaptation.</b> There is no {@code ItemStack.isEmpty()}/{@code getCount()}: an empty slot holds
 * {@code null} and the count is the public {@code stackSize} field. InventorySlot positions are {@code xDisplayPosition} /
 * {@code yDisplayPosition} (renamed to {@code xPos}/{@code yPos} on 1.12.2). The item render calls take no player
 * argument ({@code renderItemAndEffectIntoGUI(stack,x,y)}), and the player/font come from {@code mc.player} /
 * {@code mc.textRenderer}. The GL idiom mirrors the verified {@code GlassCreativeGlide} overlay in this line.
 *
 * <p><b>Seams (spliced by {@code S1mp1eTransformer.patchContainer}, javap-verified on 1.8.9's InventoryMenuScreen):</b>
 * <ul>
 *   <li>{@code InventoryMenuScreen.render} (render) HEAD &rarr; {@link #observe}: compare every slot with the
 *       previous frame; pair a slot that gained an item with another that lost the same item in THAT frame and spawn a
 *       flight. Picking a stack up only takes and putting it down only adds, in separate frames, so a hand-carried move
 *       never pairs.</li>
 *   <li>{@code InventoryMenuScreen.renderSlot} (renderSlot) HEAD &rarr; {@link #hideSlot}: a landing slot that was empty (or
 *       held something else) stays hidden until its flight arrives (by time, so the landing frame already shows it).</li>
 *   <li>Before the {@code drawGuiContainerForegroundLayer} (renderLabels) INVOKE in drawScreen &rarr; {@link #draw}:
 *       that point is inside the {@code pushMatrix / translate(guiLeft, guiTop)} the slot loop uses — container-local
 *       space — and right after vanilla's {@code Lighting.turnOff()}. GUI item lights are
 *       switched on for the flights and vanilla's pre-foreground state (lights off) is put back afterwards.</li>
 * </ul>
 * Timing / arc / lift / duration are byte-for-byte the 26.2 values. The creative inventory (its own grid glide) is
 * left alone. EntityRenderer thread only.
 */
public final class ItemFlightHook {

    private ItemFlightHook() {}

    private static final float DURATION_S = 0.18F;
    private static final float ARC = 6.0F;
    private static final float LIFT = 0.12F;
    private static final float FLIGHT_Z = 150.0F;

    /** Dev only (DevShot): slow-motion factor and a log line per spawned flight. */
    public static float timeScale = 1.0F;
    public static boolean debugLog;

    private static final class Flight {
        ItemStack stack;
        float x0, y0, x1, y1;
        long startNs;
        InventorySlot target;
        boolean hideTarget;
    }

    private static final class State {
        final ArrayList<Flight> flights = new ArrayList<Flight>();
        ItemStack[] prev;
    }

    private static final Map<InventoryMenuScreen, State> STATES = new WeakHashMap<InventoryMenuScreen, State>();

    private static boolean enabled(InventoryMenuScreen s) {
        return s != null && !(s instanceof CreativeInventoryScreen) && s.menu != null;
    }

    private static State state(InventoryMenuScreen s) {
        State st = STATES.get(s);
        if (st == null) { st = new State(); STATES.put(s, st); }
        return st;
    }

    /** 1.8.9: empty == null (or a zeroed stack). */
    private static boolean empty(ItemStack s) { return s == null || s.size <= 0; }

    /** Same item type + NBT (ignores count), null-safe. */
    private static boolean combine(ItemStack a, ItemStack b) {
        if (a == null || b == null) return false;
        return ItemStack.matchesItem(a, b) && ItemStack.matchesNbt(a, b);
    }

    private static boolean same(ItemStack a, ItemStack b) {
        if (empty(a) || empty(b)) return empty(a) && empty(b);
        return a.size == b.size && combine(a, b);
    }

    @SuppressWarnings("unchecked")
    private static List<InventorySlot> slotsOf(InventoryMenuScreen screen) {
        return screen.menu.slots;
    }

    /** drawScreen HEAD: whatever moved from one slot to another since the last frame flies. */
    public static void observe(InventoryMenuScreen screen) {
        try {
            if (!enabled(screen)) return;
            State st = state(screen);
            List<InventorySlot> slots = slotsOf(screen);
            int n = slots.size();
            ItemStack[] before = st.prev;
            if (before != null && before.length == n) {
                boolean changed = false;
                for (int i = 0; i < n && !changed; i++) changed = !same(before[i], slots.get(i).getItem());
                if (!changed) return;
            }
            ItemStack[] now = new ItemStack[n];
            for (int i = 0; i < n; i++) {
                ItemStack s = slots.get(i).getItem();
                now[i] = s == null ? null : s.copy();
            }
            st.prev = now;
            if (before == null || before.length != n) return;   // first frame / another slot set: baseline only
            int[] lost = new int[n];
            for (int i = 0; i < n; i++) {
                ItemStack b = before[i], a = slots.get(i).getItem();
                if (empty(b)) continue;
                if (empty(a) || !combine(a, b)) lost[i] = b.size;
                else if (a.size < b.size) lost[i] = b.size - a.size;
            }
            for (int j = 0; j < n; j++) {
                InventorySlot target = slots.get(j);
                ItemStack a = target.getItem(), b = before[j];
                if (empty(a)) continue;
                boolean fresh = empty(b) || !combine(a, b);
                int gain = fresh ? a.size : a.size - b.size;
                if (gain <= 0) continue;
                int src = -1;
                for (int i = 0; src < 0 && i < n; i++) {
                    if (i != j && lost[i] > 0 && combine(before[i], a)) src = i;
                }
                if (src < 0) continue;
                ItemStack flying = a.copy();
                flying.size = gain;
                InventorySlot from = slots.get(src);
                lost[src] = Math.max(0, lost[src] - gain);
                spawn(st, flying, from.x, from.y,
                        target.x, target.y, target, fresh);
            }
        } catch (Throwable t) {
            // cosmetic: never disturb the container
        }
    }

    private static void spawn(State st, ItemStack stack, float x0, float y0, float x1, float y1, InventorySlot target,
                              boolean hideTarget) {
        Flight f = new Flight();
        f.stack = stack;
        f.x0 = x0; f.y0 = y0; f.x1 = x1; f.y1 = y1;
        f.startNs = System.nanoTime();
        f.target = target;
        f.hideTarget = hideTarget;
        st.flights.add(f);
        if (debugLog) System.out.printf("[ItemFlights] spawn %s (%.0f,%.0f)->(%.0f,%.0f) hide=%s ns=%d%n",
                stack, x0, y0, x1, y1, hideTarget, f.startNs);
        if (st.flights.size() > 64) st.flights.remove(0);
    }

    /** drawSlot HEAD: true = skip vanilla's draw of this slot (its item is still in flight toward it). */
    public static boolean hideSlot(InventoryMenuScreen screen, InventorySlot slot) {
        State st = STATES.get(screen);
        if (st == null || st.flights.isEmpty()) return false;
        long now = System.nanoTime();
        for (Flight f : st.flights) {
            if (f.hideTarget && f.target == slot && (now - f.startNs) / 1.0e9F * timeScale / DURATION_S < 1F) return true;
        }
        return false;
    }

    /** Before drawGuiContainerForegroundLayer: draw every flight (container-local space) and retire the landed ones. */
    public static void draw(InventoryMenuScreen screen) {
        State st = STATES.get(screen);
        if (st == null || st.flights.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ItemRenderer ri = mc.getItemRenderer();
        TextRenderer fr = mc.textRenderer;
        float prevZ = ri.zOffset;
        long now = System.nanoTime();
        Lighting.turnOnGui();
        GlStateManager.enableDepthTest();
        try {
            for (int k = st.flights.size() - 1; k >= 0; k--) {
                Flight f = st.flights.get(k);
                float t = (now - f.startNs) / 1.0e9F * timeScale / DURATION_S;
                if (t >= 1F) { st.flights.remove(k); continue; }
                float e = 1F - (1F - t) * (1F - t) * (1F - t);            // ease-out cubic
                float bump = (float) Math.sin(Math.PI * t);
                float x = f.x0 + (f.x1 - f.x0) * e;
                float y = f.y0 + (f.y1 - f.y0) * e - ARC * bump;
                float s = 1F + LIFT * bump;
                GlStateManager.pushMatrix();
                try {
                    GlStateManager.translatef(x + 8F - 8F * s, y + 8F - 8F * s, 0F);
                    GlStateManager.scalef(s, s, 1F);
                    ri.zOffset = FLIGHT_Z;
                    ri.renderGuiItem(f.stack, 0, 0);
                    ri.renderGuiItemDecorations(fr, f.stack, 0, 0, null);
                } finally {
                    GlStateManager.popMatrix();
                }
            }
        } catch (Throwable t) {
            st.flights.clear();
        } finally {
            ri.zOffset = prevZ;
            Lighting.turnOff();   // what vanilla has right before the foreground layer
            GlStateManager.color4f(1f, 1f, 1f, 1f);
        }
    }
}
