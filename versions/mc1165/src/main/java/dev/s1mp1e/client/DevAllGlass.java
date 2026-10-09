package dev.s1mp1e.client;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayDeque;
import java.util.List;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.GameModeSelectionScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.advancement.AdvancementsScreen;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.client.gui.screen.ingame.EnchantmentScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.widget.LockButtonWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.passive.HorseEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;
import net.minecraft.world.GameMode;

/**
 * DevShot {@code S1MP1E_SHOT_MODE=allglass} (1.16.5 port): the all-glass elements that only appear in a particular game
 * state, shot one after another in the dev world ({@code agNN-name.png}): XP bar (#17), F3 (#15), command suggestions
 * (#13) and usage (#14), subtitles (#16), the F3+F4 switcher (#19, held open by {@link #holdSwitcher}), anvil rename
 * field + enchanting rows (#12), the advancement tab band (#23), the horse jump bar (#17), the spectator hotbar (#20),
 * create-world and the difficulty-lock button (#21). Dev only — inert without {@code S1MP1E_SHOT}. Java 8 (the build
 * targets {@code --release 8}), 1.16.5 idioms: {@code openScreen}, {@code player.inventory}, {@code getServerWorld}.
 */
public final class DevAllGlass {
    private DevAllGlass() {}

    /** Read by {@code GameModeSelectionGlassMixin}: keep the F3+F4 switcher open while it is being shot. */
    public static volatile boolean holdSwitcher;

    private static final String[] NAMES = {
        "ag01-xp", "ag02-f3", "ag03-suggest", "ag04-usage", "ag05-subs", "ag06-f3f4", "ag07-anvil", "ag08-enchant",
        "ag09-adv", "ag10-jump", "ag11-spec", "ag13-createworld", "ag15-lockbtn",
    };
    private static final int[] CAP = { 45, 25, 40, 40, 30, 30, 35, 35, 85, 50, 22, 170, 40 };

    private static int stage, frames;
    private static boolean prevSubs;

    private static File dir() {
        String d = System.getenv("S1MP1E_SHOT");
        return d == null ? new File(".") : new File(d.trim());
    }

    private static void log(String line) {
        try {
            FileWriter w = new FileWriter(new File(dir(), "ag-log.txt"), true);
            try { w.write(line + System.lineSeparator()); } finally { w.close(); }
        } catch (Throwable ignored) {}
        System.out.println("[S1mp1e][AllGlass] " + line);
    }

    private static void cmd(final MinecraftClient mc, final String c) {
        try {
            final MinecraftServer srv = mc.getServer();
            if (srv == null) { log("no server for: " + c); return; }
            srv.execute(new Runnable() {
                public void run() {
                    try { srv.getCommandManager().execute(srv.getCommandSource(), c); }
                    catch (Throwable t) { log("cmd failed " + c + ": " + t); }
                }
            });
        } catch (Throwable t) {
            log("cmd failed " + c + ": " + t);
        }
    }

    private static void mode(MinecraftClient mc, GameMode m) {
        cmd(mc, "gamemode " + m.getName() + " @a");
        try { if (mc.interactionManager != null) mc.interactionManager.setGameMode(m); } catch (Throwable t) { log("client gamemode: " + t); }
    }

    /** One frame; true once every stage has been shot. */
    static boolean step(MinecraftClient mc) {
        if (stage >= NAMES.length) {
            log("DONE " + NAMES.length);
            return true;
        }
        if (mc.player == null) { log("LOST-WORLD at " + NAMES[stage]); return true; }
        try { mc.getToastManager().clear(); } catch (Throwable ignored) {}
        frames++;
        try {
            if (frames == 1) setup(mc, stage);
            during(mc, stage);
            if (frames == CAP[stage]) {
                DevShot.capture(mc, NAMES[stage] + ".png");
                String shown = mc.currentScreen == null ? "hud" : mc.currentScreen.getClass().getSimpleName();
                log(NAMES[stage] + " OK shown=" + shown);
            } else if (frames >= CAP[stage] + 3) {
                teardown(mc, stage);
                frames = 0;
                stage++;
            }
        } catch (Throwable t) {
            log(NAMES[stage] + " FAIL " + t);
            try { teardown(mc, stage); } catch (Throwable ignored) {}
            frames = 0;
            stage++;
        }
        return false;
    }

    private static void setup(MinecraftClient mc, int s) throws Exception {
        ClientPlayerEntity p = mc.player;
        PlayerInventory inv = p.inventory;
        GameMenuScreen parent = new GameMenuScreen(true);
        switch (s) {
            case 0: { mc.openScreen(null); mode(mc, GameMode.SURVIVAL); cmd(mc, "xp add @a 30 levels"); cmd(mc, "xp add @a 60 points"); break; }
            case 1: mc.options.debugEnabled = true; break;
            case 2: mc.openScreen(new ChatScreen("/")); break;        // + typed 'g' below opens the popup
            case 3: mc.openScreen(new ChatScreen("/xp add @a ")); break;
            case 4: { prevSubs = mc.options.showSubtitles; mc.options.showSubtitles = true; break; }
            case 5: { holdSwitcher = true; mc.openScreen(new GameModeSelectionScreen()); break; }
            case 6: {
                AnvilScreenHandler h = new AnvilScreenHandler(101, inv);
                h.getSlot(0).setStack(new ItemStack(Items.DIAMOND_SWORD));
                mc.openScreen(new AnvilScreen(h, inv, new TranslatableText("container.repair")));
                break;
            }
            case 7: {
                EnchantmentScreenHandler h = new EnchantmentScreenHandler(102, inv);
                h.getSlot(0).setStack(new ItemStack(Items.DIAMOND_SWORD));
                h.getSlot(1).setStack(new ItemStack(Items.LAPIS_LAZULI, 10));
                h.enchantmentPower[0] = 1;
                h.enchantmentPower[1] = 15;
                h.enchantmentPower[2] = 0;        // third row: unavailable (faint scrim) for contrast
                mc.openScreen(new EnchantmentScreen(h, inv, new TranslatableText("container.enchant")));
                break;
            }
            case 8: cmd(mc, "advancement grant @a everything"); break;
            case 9: cmd(mc, "execute as @p at @s run summon horse ~ ~ ~ {Tame:1b,SaddleItem:{id:\"minecraft:saddle\",Count:1b}}"); break;
            case 10: mode(mc, GameMode.SPECTATOR); break;
            case 11: mc.openScreen(CreateWorldScreen.create(parent)); break;
            case 12:   // #21 LockButtonGlassMixin path (vanilla renderButton, outside the settings shell): locked + unlocked
                mc.openScreen(new Screen(new LiteralText("lock")) {
                    protected void init() {
                        LockButtonWidget locked = new LockButtonWidget(this.width / 2 - 30, this.height / 2 - 10, new net.minecraft.client.gui.widget.ButtonWidget.PressAction() {
                            public void onPress(net.minecraft.client.gui.widget.ButtonWidget b) {}
                        });
                        locked.setLocked(true);
                        this.addButton(locked);
                        this.addButton(new LockButtonWidget(this.width / 2 + 10, this.height / 2 - 10, new net.minecraft.client.gui.widget.ButtonWidget.PressAction() {
                            public void onPress(net.minecraft.client.gui.widget.ButtonWidget b) {}
                        }));
                    }
                    public void render(MatrixStack m, int mx, int my, float d) {
                        this.renderBackground(m);
                        super.render(m, mx, my, d);
                    }
                });
                break;
            default: break;
        }
    }

    private static void during(final MinecraftClient mc, int s) {
        try { mc.inGameHud.getChatHud().clear(false); } catch (Throwable ignored) {}
        switch (s) {
            case 2:
                if (frames == 6 && mc.currentScreen != null) mc.currentScreen.charTyped('g', 0);
                break;
            case 4:
                if (frames == 3) mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_CHICKEN_AMBIENT, 1f));
                if (frames == 8) mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_COW_AMBIENT, 1f));
                if (frames == 13) mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_CHEST_OPEN, 1f));
                break;
            case 8:
                if (frames == 40) mc.openScreen(new AdvancementsScreen(mc.getNetworkHandler().getAdvancementHandler()));
                break;
            case 9:
                if (frames == 12) {   // 1.16.5 has no /ride: mount on the server thread directly
                    final MinecraftServer srv = mc.getServer();
                    if (srv != null) srv.execute(new Runnable() {
                        public void run() {
                            try {
                                ServerPlayerEntity sp = srv.getPlayerManager().getPlayerList().get(0);
                                List<HorseEntity> horses = sp.getServerWorld().getEntitiesByClass(HorseEntity.class,
                                        sp.getBoundingBox().expand(8.0), new java.util.function.Predicate<HorseEntity>() {
                                            public boolean test(HorseEntity e) { return true; }
                                        });
                                if (!horses.isEmpty()) sp.startRiding(horses.get(0), true);
                                else log("no horse to mount");
                            } catch (Throwable t) { log("mount failed: " + t); }
                        }
                    });
                }
                if (frames >= 30) {
                    try {
                        // 1.16.5 mount-jump strength is the private float field_3922 (getter method_3151); dev + prod name.
                        java.lang.reflect.Field f = ClientPlayerEntity.class.getDeclaredField("field_3922");
                        f.setAccessible(true);
                        f.setFloat(mc.player, 0.65f);
                    } catch (Throwable ignored) {}
                }
                break;
            case 10:
                if (frames == 12 || frames == 16) {
                    try { mc.inGameHud.getSpectatorHud().selectSlot(0); } catch (Throwable ignored) {}
                }
                break;
            case 11:
                // #21 amber state: flip ONLY the widget's visual lock flag (setLocked sends no packet). Discarded on teardown.
                if (frames == 15 && mc.currentScreen != null) {
                    ArrayDeque<Object> q = new ArrayDeque<Object>(mc.currentScreen.children());
                    while (!q.isEmpty()) {
                        Object c = q.poll();
                        if (c instanceof LockButtonWidget) ((LockButtonWidget) c).setLocked(true);
                        if (c instanceof ParentElement) q.addAll(((ParentElement) c).children());
                    }
                }
                break;
            default:
                break;
        }
    }

    private static void teardown(MinecraftClient mc, int s) {
        switch (s) {
            case 1: mc.options.debugEnabled = false; break;
            case 4: mc.options.showSubtitles = prevSubs; break;
            case 5: { mc.openScreen(null); holdSwitcher = false; break; }
            case 9: { try { if (mc.player != null) mc.player.stopRiding(); } catch (Throwable ignored) {} cmd(mc, "kill @e[type=horse]"); break; }
            case 10: mode(mc, GameMode.CREATIVE); break;
            default: { if (mc.currentScreen != null) mc.openScreen(null); break; }
        }
    }
}
