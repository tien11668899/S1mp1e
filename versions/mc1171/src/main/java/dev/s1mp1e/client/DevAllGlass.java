package dev.s1mp1e.client;

import java.io.File;
import java.io.FileWriter;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.GameModeSelectionScreen;
import net.minecraft.client.gui.screen.advancement.AdvancementsScreen;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.client.gui.screen.ingame.EnchantmentScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;
import net.minecraft.world.GameMode;

/**
 * DevShot {@code S1MP1E_SHOT_MODE=allglass} (1.18.2 port of the 1.20.1 tool): the all-glass elements that only appear in
 * a particular game state, shot one after another in the dev world ({@code agNN-name.png}): XP bar, F3, command
 * suggestions ({@code /g…}) and usage ({@code /xp add @a }), subtitles, the F3+F4 switcher (held open by
 * {@link #holdSwitcher}), anvil rename field + enchanting rows, the advancement tab band, the horse jump bar, the
 * spectator hotbar, create-world and the difficulty-lock button. (Chat-report, telemetry screens do not exist in
 * 1.18.2, so there is no report-reason stage.) Dev only — inert without {@code S1MP1E_SHOT}.
 */
public final class DevAllGlass {
    private DevAllGlass() {}

    /** Read by {@code GameModeSelectionGlassMixin}: keep the F3+F4 switcher open while it is being shot. */
    public static volatile boolean holdSwitcher;

    private static final String[] NAMES = {
        "ag01-xp", "ag02-f3", "ag03-suggest", "ag04-usage", "ag05-subs", "ag06-f3f4", "ag07-anvil", "ag08-enchant",
        "ag09-adv", "ag10-jump", "ag11-spec", "ag13-createworld", "ag14-lock", "ag15-lockbtn",
    };
    private static final int[] CAP = { 45, 25, 40, 40, 30, 30, 35, 35, 85, 50, 22, 170, 40, 30 };

    private static int stage, frames;
    private static boolean prevSubs;

    private static File dir() {
        String d = System.getenv("S1MP1E_SHOT");
        return d == null ? new File(".") : new File(d.trim());
    }

    private static void log(String line) {
        try (FileWriter w = new FileWriter(new File(dir(), "ag-log.txt"), true)) {
            w.write(line + System.lineSeparator());
        } catch (Throwable ignored) {}
        System.out.println("[S1mp1e][AllGlass] " + line);
    }

    private static void cmd(MinecraftClient mc, String c) {
        try {
            var srv = mc.getServer();
            if (srv == null) { log("no server for: " + c); return; }
            srv.execute(() -> {
                try { srv.getCommandManager().execute(srv.getCommandSource(), c); }
                catch (Throwable t) { log("cmd failed " + c + ": " + t); }
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
        var inv = p.getInventory();
        var parent = new GameMenuScreen(true);
        switch (s) {
            case 0: { mc.setScreen(null); mode(mc, GameMode.SURVIVAL); cmd(mc, "xp add @a 30 levels"); cmd(mc, "xp add @a 60 points"); break; }
            case 1: mc.options.debugEnabled = true; break;
            case 2: mc.setScreen(new ChatScreen("/")); break;        // + typed 'g' below opens the popup
            case 3: mc.setScreen(new ChatScreen("/xp add @a ")); break;
            case 4: { prevSubs = mc.options.showSubtitles; mc.options.showSubtitles = true; break; }
            case 5: { holdSwitcher = true; mc.setScreen(new GameModeSelectionScreen()); break; }
            case 6: {
                AnvilScreenHandler h = new AnvilScreenHandler(101, inv);
                h.getSlot(0).setStack(new ItemStack(Items.DIAMOND_SWORD));
                mc.setScreen(new AnvilScreen(h, inv, new TranslatableText("container.repair")));
                break;
            }
            case 7: {
                EnchantmentScreenHandler h = new EnchantmentScreenHandler(102, inv);
                h.getSlot(0).setStack(new ItemStack(Items.DIAMOND_SWORD));
                h.getSlot(1).setStack(new ItemStack(Items.LAPIS_LAZULI, 10));
                h.enchantmentPower[0] = 1;
                h.enchantmentPower[1] = 15;
                h.enchantmentPower[2] = 0;        // third row: unavailable (faint scrim) for contrast
                mc.setScreen(new EnchantmentScreen(h, inv, new TranslatableText("container.enchant")));
                break;
            }
            case 8: cmd(mc, "advancement grant @a everything"); break;
            case 9: cmd(mc, "execute as @p at @s run summon horse ~ ~ ~ {Tame:1b,SaddleItem:{id:\"minecraft:saddle\",Count:1b}}"); break;
            case 10: mode(mc, GameMode.SPECTATOR); break;
            case 11: mc.setScreen(CreateWorldScreen.create(parent)); break; // 1.18.2: create() RETURNS the screen
            case 12: mc.setScreen(new net.minecraft.client.gui.screen.option.OptionsScreen(parent, mc.options)); break;
            case 13:   // #21 LockButtonGlassMixin path (vanilla renderButton, outside the settings shell): locked + unlocked
                mc.setScreen(new net.minecraft.client.gui.screen.Screen(new LiteralText("lock")) {
                    @Override protected void init() {
                        var locked = new net.minecraft.client.gui.widget.LockButtonWidget(this.width / 2 - 30, this.height / 2 - 10, b -> {});
                        locked.setLocked(true);
                        this.addDrawableChild(locked);
                        this.addDrawableChild(new net.minecraft.client.gui.widget.LockButtonWidget(this.width / 2 + 10, this.height / 2 - 10, b -> {}));
                    }
                    @Override public void render(net.minecraft.client.util.math.MatrixStack m, int mx, int my, float d) {
                        this.renderBackground(m);
                        super.render(m, mx, my, d);
                    }
                });
                break;
            default: break;
        }
    }

    private static void during(MinecraftClient mc, int s) {
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
                if (frames == 40) mc.setScreen(new AdvancementsScreen(mc.getNetworkHandler().getAdvancementHandler()));
                break;
            case 9:
                if (frames == 12) {   // 1.18.2 has no /ride (1.19.4+): mount on the server thread directly
                    var srv = mc.getServer();
                    if (srv != null) srv.execute(() -> {
                        try {
                            var sp = srv.getPlayerManager().getPlayerList().get(0);
                            var horses = sp.getServerWorld().getEntitiesByClass(net.minecraft.entity.passive.HorseEntity.class,
                                    sp.getBoundingBox().expand(8.0), e -> true);
                            if (!horses.isEmpty()) sp.startRiding(horses.get(0), true);
                            else log("no horse to mount");
                        } catch (Throwable t) { log("mount failed: " + t); }
                    });
                }
                if (frames >= 30) {
                    try {
                        var f = ClientPlayerEntity.class.getDeclaredField("mountJumpStrength");   // dev (named) runtime
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
            case 12:
                // #21 amber state: flip ONLY the widget's visual lock flag (setLocked sends no packet; the world's
                // difficulty is never actually locked). The page is discarded on teardown.
                if (frames == 15 && mc.currentScreen != null) {
                    java.util.ArrayDeque<Object> q = new java.util.ArrayDeque<Object>(mc.currentScreen.children());
                    while (!q.isEmpty()) {
                        Object c = q.poll();
                        if (c instanceof net.minecraft.client.gui.widget.LockButtonWidget lb) lb.setLocked(true);
                        if (c instanceof net.minecraft.client.gui.ParentElement pe) q.addAll(pe.children());
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
            case 5: { mc.setScreen(null); holdSwitcher = false; break; }
            case 9: { try { if (mc.player != null) mc.player.dismountVehicle(); } catch (Throwable ignored) {} cmd(mc, "kill @e[type=horse]"); break; }
            case 10: mode(mc, GameMode.CREATIVE); break;
            default: { if (mc.currentScreen != null) mc.setScreen(null); break; }
        }
    }
}
