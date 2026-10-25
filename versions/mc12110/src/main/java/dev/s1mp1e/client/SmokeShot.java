package dev.s1mp1e.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.s1mp1e.client.gui.S1mp1eConfigScreen;
import java.io.File;
import java.util.function.Function;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Dev smoke check for a new version line (1.21.6 onwards): with env {@code S1MP1E_SHOT=<dir>} the client drives
 * itself — title screen, the S1mp1e settings, vanilla Options (the settings shell), the language list, the world list
 * (rows cascading in, a selection gliding to the next row; read-only), a create-world cycle button rolling (in slow
 * motion; nothing is created), the loading screen of a fresh flat {@code devshot} world with a small loadout (armour,
 * items, effects for the HUD modules), the HUD, the inventory, the S1mp1e settings in the world, the creative
 * inventory, an open chat with typed and selected text (never sent), block
 * menus with long lists (stonecutter, loom, a trader) mid-glide, a sign and a book being typed, a name tag — one PNG per
 * step into {@code <dir>} and quits. A compact stand-in for 26.x's DevShot, whose 5.7k lines are not ported; inert
 * without the env var. A 180 s watchdog quits regardless. Driven once per frame from {@code SmokeShotMixin}
 * (Minecraft.runTick TAIL). Delete the {@code devshot} world from the saves afterwards.
 */
public final class SmokeShot {
    private SmokeShot() {}

    private static boolean resolved;
    private static File outDir;
    private static int phase, frames;
    private static long startMs, phaseMs;
    private static boolean worldTried, setupDone, loadingShot;
    private static long loadingSinceMs;

    private static final long WATCHDOG_MS = 180_000L;

    public static void frame(Minecraft mc) {
        if (!resolved) {
            resolved = true;
            String dir = System.getenv("S1MP1E_SHOT");
            if (dir != null && !dir.isBlank()) {
                outDir = new File(dir);
                //noinspection ResultOfMethodCallIgnored
                outDir.mkdirs();
                startMs = System.currentTimeMillis();
                log("active, writing to " + outDir.getAbsolutePath());
            }
        }
        if (outDir == null) return;
        if (System.currentTimeMillis() - startMs > WATCHDOG_MS && phase < 90) {
            log("watchdog: quitting at phase " + phase);
            next(90);
        }
        try {
            step(mc);
        } catch (Throwable t) {
            log("step " + phase + " failed: " + t);
            t.printStackTrace();
            if (phase < 90) {
                next(90);
            } else {   // the quit step itself failed: stop once, never retry it every frame
                next(99);
                mc.stop();
            }
        }
    }

    private static void step(Minecraft mc) {
        frames++;
        switch (phase) {
            case 0 -> {   // wait for the title screen, past the loading overlay
                if (mc.getOverlay() == null && mc.screen instanceof TitleScreen) next(1);
                else if (frames % 300 == 0) {
                    log("waiting: overlay=" + name(mc.getOverlay()) + " screen=" + name(mc.screen) + " frame " + frames);
                    if (frames == 600) capture(mc, "stuck.png");
                    // some other screen (a mod's notice, a warning) in front of the title: go past it
                    if (frames >= 900 && mc.getOverlay() == null && !(mc.screen instanceof TitleScreen)) mc.setScreen(new TitleScreen());
                }
            }
            // waits are wall-clock: the title fades its widgets in over a second, screens animate in
            case 1 -> { if (waited(2500)) { capture(mc, "title.png"); mc.setScreen(new S1mp1eConfigScreen()); next(2); } }
            case 2 -> { if (waited(800)) { capture(mc, "config.png"); mc.setScreen(new OptionsScreen(new TitleScreen(), mc.options)); next(3); } }
            case 3 -> {
                if (waited(800)) {
                    capture(mc, "options.png");
                    mc.setScreen(new LanguageSelectScreen(new TitleScreen(), mc.options, mc.getLanguageManager()));   // a list with a selected row
                    next(31);
                }
            }
            case 31 -> {
                if (waited(800)) {
                    capture(mc, "language.png");
                    mc.setScreen(new SelectWorldScreen(new TitleScreen()));   // async list: entries cascade in
                    next(32);
                }
            }
            case 32 -> { if (waited(1500)) { selectWorld(mc, 0); next(33); } }
            case 33 -> { if (waited(700)) { selectWorld(mc, 1); next(34); } }   // the box glides to the next row
            case 34 -> {   // openFresh blocks on a nested tick loop (data packs): switch phase first, it re-enters this method
                if (waited(700)) { capture(mc, "worlds.png"); next(35); CreateWorldScreen.openFresh(mc, () -> mc.setScreen(new TitleScreen())); }
            }
            case 35 -> {   // a cycle button rolls its value (nothing is created: the screen is left for the title)
                if (waited(1500)) {
                    CycleButton<?> b = find(mc.screen, CycleButton.class);
                    com.seagull.liquidglass.client.render.TypingAnim.timeScale = 0.15F;   // slow motion: the roll is mid-way in the shot
                    if (b != null) b.onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0)); else log("create world: no cycle button");
                    next(36);
                }
            }
            case 36 -> {   // ~70 ms into the slowed roll; then back to normal speed so it settles
                if (waited(450)) {
                    capture(mc, "cycle-roll.png");
                    com.seagull.liquidglass.client.render.TypingAnim.timeScale = 1.0F;
                    next(37);
                }
            }
            case 37 -> { if (waited(800)) { capture(mc, "cycle.png"); mc.setScreen(new TitleScreen()); next(4); } }
            case 4 -> { if (waited(300)) { next(5); createWorld(mc); } }   // the world loads in a nested tick loop (phase 5)
            case 5 -> {   // wait for the world (the loading screen is shot once on the way)
                if (mc.screen instanceof LevelLoadingScreen && !loadingShot) {   // past the 0.22 s dissolve from the title
                    long now = System.currentTimeMillis();
                    if (loadingSinceMs == 0L) loadingSinceMs = now;
                    else if (now - loadingSinceMs >= 300L) { loadingShot = true; capture(mc, "loading.png"); }
                }
                if (mc.level != null && mc.player != null && mc.screen == null) next(6);
                else if (waited(60_000)) { log("world never loaded"); next(90); }
            }
            case 6 -> {
                if (!setupDone) { setupDone = true; setup(mc); }
                if (waited(2500)) { capture(mc, "world.png"); mc.setScreen(new InventoryScreen(mc.player)); next(7); }
            }
            case 7 -> { if (waited(800)) { capture(mc, "inventory.png"); mc.setScreen(new S1mp1eConfigScreen()); next(8); } }
            case 8 -> { if (waited(800)) { capture(mc, "config-world.png"); mc.setScreen(null); next(9); } }
            case 9 -> { if (waited(600)) { capture(mc, "hud.png"); creative(mc); next(10); } }
            case 10 -> {   // creative inventory: the glass tabs and the gliding item grid
                if (waited(1000) && mc.player != null) {
                    mc.setScreen(new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(), false));
                    next(11);
                }
            }
            case 11 -> { if (waited(800)) { capture(mc, "creative.png"); mc.setScreen(new ChatScreen("hello S1mp1e", false)); next(12); } }
            case 12 -> {
                if (waited(800)) {
                    capture(mc, "chat.png");
                    EditBox box = find(mc.screen, EditBox.class);   // select all: the gliding selection highlight
                    if (box != null) { box.moveCursorToEnd(false); box.setHighlightPos(0); }
                    next(13);
                }
            }
            case 13 -> { if (waited(500)) { capture(mc, "chat-select.png"); mc.setScreen(null); next(40); } }   // never sent
            // block menus with a list longer than one page: the glass scroller, then a wheel step mid-glide
            case 40 -> { if (waited(400)) { openBlockMenu(mc, Blocks.STONECUTTER, new ItemStack(Items.COBBLED_DEEPSLATE, 64), ItemStack.EMPTY); next(41); } }
            case 41 -> { if (waited(1200)) { capture(mc, "stonecutter.png"); wheel(mc); next(42); } }
            case 42 -> { if (waited(90)) { capture(mc, "stonecutter-glide.png"); next(43); } }
            case 43 -> { if (waited(600)) { capture(mc, "stonecutter-scrolled.png"); close(mc); next(44); } }
            case 44 -> { if (waited(400)) { openBlockMenu(mc, Blocks.LOOM, new ItemStack(Items.WHITE_BANNER), new ItemStack(Items.RED_DYE, 16)); next(45); } }
            case 45 -> { if (waited(1200)) { capture(mc, "loom.png"); wheel(mc); next(46); } }
            case 46 -> { if (waited(90)) { capture(mc, "loom-glide.png"); next(47); } }
            case 47 -> { if (waited(600)) { capture(mc, "loom-scrolled.png"); close(mc); next(48); } }
            case 48 -> { if (waited(400)) { openTrader(mc); next(49); } }
            case 49 -> { if (waited(1200)) { capture(mc, "merchant.png"); wheel(mc); next(50); } }
            case 50 -> { if (waited(90)) { capture(mc, "merchant-glide.png"); next(51); } }
            case 51 -> { if (waited(600)) { capture(mc, "merchant-scrolled.png"); close(mc); next(52); } }
            // typing: a sign line and a book page, glyphs mid-entrance then settled
            case 52 -> { if (waited(400)) { openSign(mc); next(53); } }
            case 53 -> { if (waited(800) && type(mc, "S1mp1e sign")) next(54); }
            case 54 -> { if (waited(120)) { capture(mc, "sign-typing.png"); next(55); } }
            case 55 -> { if (waited(700)) { capture(mc, "sign.png"); if (mc.screen != null) mc.screen.onClose(); next(56); } }
            case 56 -> {
                if (waited(400) && mc.player != null) {
                    mc.setScreen(new BookEditScreen(mc.player, new ItemStack(Items.WRITABLE_BOOK), InteractionHand.MAIN_HAND,
                            WritableBookContent.EMPTY));   // closed with setScreen(null): nothing is saved
                    next(57);
                }
            }
            case 57 -> { if (waited(800) && type(mc, "The quick brown fox jumps over the lazy dog, twice.")) next(58); }
            case 58 -> { if (waited(120)) { capture(mc, "book-typing.png"); next(59); } }
            case 59 -> { if (waited(700)) { capture(mc, "book.png"); mc.setScreen(null); nameTag(mc); next(60); } }
            case 60 -> { if (waited(1200)) { capture(mc, "nametag.png"); next(90); } }
            case 90 -> { if (waited(500)) { log("done, quitting."); mc.stop(); next(99); } }   // let the async writes land
            default -> { }
        }
    }

    private static void next(int p) {
        phase = p;
        frames = 0;
        phaseMs = System.currentTimeMillis();
    }

    private static boolean waited(long ms) {
        return System.currentTimeMillis() - phaseMs >= ms;
    }

    /** Select the n-th world row of the open world list (read-only: nothing is loaded or changed). */
    private static void selectWorld(Minecraft mc, int n) {
        if (mc.screen == null) return;
        for (GuiEventListener c : mc.screen.children()) {
            if (!(c instanceof WorldSelectionList list)) continue;
            int i = 0;
            for (WorldSelectionList.Entry e : list.children()) {
                if (e instanceof WorldSelectionList.WorldListEntry && i++ == n) { list.setSelected(e); return; }
            }
        }
        log("world list: no row " + n);
    }

    /** First widget of {@code type} in the screen's widget tree. */
    private static <T> T find(ContainerEventHandler parent, Class<T> type) {
        if (parent == null) return null;
        for (GuiEventListener c : parent.children()) {
            if (type.isInstance(c)) return type.cast(c);
            if (c instanceof ContainerEventHandler h) {
                T t = find(h, type);
                if (t != null) return t;
            }
        }
        return null;
    }

    /** Runs on the integrated server thread with the local player (the devshot world only). */
    private static void onServer(Minecraft mc, java.util.function.BiConsumer<ServerLevel, ServerPlayer> action) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        java.util.UUID id = mc.player.getUUID();
        server.execute(() -> {
            try {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                if (p != null) action.accept(server.overworld(), p);
            } catch (Throwable t) {
                log("server step failed: " + t);
            }
        });
    }

    /** Places {@code block} two blocks east of the player, opens its menu and fills the first two slots. */
    private static void openBlockMenu(Minecraft mc, Block block, ItemStack slot0, ItemStack slot1) {
        onServer(mc, (level, p) -> {
            BlockPos pos = p.blockPosition().east(2);
            level.setBlockAndUpdate(pos, block.defaultBlockState());
            p.openMenu(level.getBlockState(pos).getMenuProvider(level, pos));
            p.containerMenu.getSlot(0).set(slot0);
            if (!slot1.isEmpty()) p.containerMenu.getSlot(1).set(slot1);
            p.containerMenu.broadcastChanges();
        });
    }

    /** A wandering trader with ten offers (more than the seven rows), trading with the player. */
    private static void openTrader(Minecraft mc) {
        onServer(mc, (level, p) -> {
            WanderingTrader trader = new WanderingTrader(EntityType.WANDERING_TRADER, level);
            trader.setPos(p.getX() - 2, p.getY(), p.getZ());
            trader.setNoAi(true);
            level.addFreshEntity(trader);
            MerchantOffers offers = trader.getOffers();
            offers.clear();
            Item[] wares = {Items.DIAMOND, Items.EMERALD, Items.GOLD_INGOT, Items.IRON_INGOT, Items.LAPIS_LAZULI,
                    Items.REDSTONE, Items.QUARTZ, Items.AMETHYST_SHARD, Items.COPPER_INGOT, Items.NETHERITE_SCRAP};
            for (int i = 0; i < wares.length; i++) {
                offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, i + 1), new ItemStack(wares[i]), 12, 1, 0.05F));
            }
            trader.setTradingPlayer(p);
            trader.openTradingScreen(p, trader.getDisplayName(), 1);
        });
    }

    /** An oak sign two blocks south of the player, opened for editing (front text). */
    private static void openSign(Minecraft mc) {
        onServer(mc, (level, p) -> {
            BlockPos pos = p.blockPosition().south(2);
            level.setBlockAndUpdate(pos, Blocks.OAK_SIGN.defaultBlockState());
            if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
                sign.setAllowedPlayerEditor(p.getUUID());
                p.openTextEdit(sign, true);
            }
        });
    }

    /** An armour stand with a visible custom name four blocks in front of the player (who faces south). */
    private static void nameTag(Minecraft mc) {
        onServer(mc, (level, p) -> {
            ArmorStand stand = new ArmorStand(level, p.getX(), p.getY(), p.getZ() + 4);
            stand.setCustomName(Component.literal("S1mp1e"));
            stand.setCustomNameVisible(true);
            level.addFreshEntity(stand);
        });
    }

    /** Types {@code text} into the open screen; false while no screen is open yet. */
    private static boolean type(Minecraft mc, String text) {
        if (mc.screen == null) return false;
        for (char c : text.toCharArray()) mc.screen.charTyped(new CharacterEvent(c, 0));
        return true;
    }

    /** One wheel step down over the screen centre (the list scrolls one row and glides). */
    private static void wheel(Minecraft mc) {
        if (mc.screen == null) return;
        mc.screen.mouseScrolled(mc.screen.width / 2.0, mc.screen.height / 2.0, 0.0, -1.0);
    }

    private static void close(Minecraft mc) {
        if (mc.player != null) mc.player.closeContainer();
    }

    /** Creative mode on the integrated server, for the creative inventory shot. */
    private static void creative(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        java.util.UUID id = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) p.setGameMode(GameType.CREATIVE);
        });
    }

    private static void createWorld(Minecraft mc) {
        if (worldTried) return;
        worldTried = true;
        try {
            deleteRecursively(new File(new File(mc.gameDirectory, "saves"), "devshot"));
        } catch (Throwable t) {
            log("delete previous devshot save: " + t);
        }
        LevelSettings settings = new LevelSettings("devshot", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                new GameRules(FeatureFlags.DEFAULT_FLAGS), WorldDataConfiguration.DEFAULT);
        WorldOptions opts = new WorldOptions(12345L, false, false);
        Function<HolderLookup.Provider, WorldDimensions> dims = provider ->
                provider.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions();
        mc.createWorldOpenFlows().createFreshLevel("devshot", settings, opts, dims, new TitleScreen());
    }

    /** Noon, clear sky, survival with armour / items / effects so the HUD modules have something to show. */
    private static void setup(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        server.execute(() -> {
            try {
                ServerLevel level = server.overworld();
                level.setDayTime(6000L);
                level.setWeatherParameters(1_000_000, 0, false, false);
                ServerPlayer p = server.getPlayerList().getPlayer(mc.player.getUUID());
                if (p == null) return;
                p.setGameMode(GameType.SURVIVAL);
                p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
                p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
                p.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
                p.getInventory().setItem(1, new ItemStack(Items.COOKED_BEEF, 32));
                p.getInventory().setItem(2, new ItemStack(Items.STONE, 64));
                p.addEffect(new MobEffectInstance(MobEffects.SPEED, 1200));
                p.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 3600));
                p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 600));
                p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, -1));
            } catch (Throwable t) {
                log("world setup: " + t);
            }
        });
    }

    private static void capture(Minecraft mc, String name) {
        try {
            final File out = new File(outDir, name);
            Screenshot.takeScreenshot(mc.getMainRenderTarget(), image -> {
                try (NativeImage img = image) {
                    img.writeToFile(out);
                    log("wrote " + out.getName() + " (" + img.getWidth() + "x" + img.getHeight() + ")");
                } catch (Throwable t) {
                    log("write " + name + " failed: " + t);
                }
            });
        } catch (Throwable t) {
            log("capture " + name + " failed: " + t);
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static String name(Object o) {
        return o == null ? "none" : o.getClass().getName();
    }

    private static void log(String s) {
        System.out.println("[S1mp1e][SmokeShot] " + s);
    }
}
