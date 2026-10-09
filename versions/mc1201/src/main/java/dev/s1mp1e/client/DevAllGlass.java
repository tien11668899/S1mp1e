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
import net.minecraft.client.gui.screen.option.TelemetryInfoScreen;
import net.minecraft.client.gui.screen.report.AbuseReportReasonScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.report.AbuseReportReason;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;

/**
 * DevShot {@code S1MP1E_SHOT_MODE=allglass} (1.20.1): the all-glass elements that only appear in a particular game state,
 * shot one after another in the dev world so each has screenshot evidence ({@code agNN-name.png}): XP bar (survival),
 * F3, command suggestions ({@code /gi}) and usage ({@code /xp add @a }), subtitles, the F3+F4 switcher (held open by
 * {@link #holdSwitcher}), anvil rename field + enchanting rows (client-side handlers with stand-in items), the
 * advancement tab band (all advancements granted → five tabs), the horse jump bar, the spectator hotbar, the telemetry
 * text area, the report-reason description box and the create-world header/footer hairlines + segmented tabs.
 * Dev only — inert without {@code S1MP1E_SHOT}; commands run on the integrated server's thread.
 */
public final class DevAllGlass {
    private DevAllGlass() {}

    /** Read by {@code GameModeSelectionGlassMixin}: keep the F3+F4 switcher open while it is being shot. */
    public static volatile boolean holdSwitcher;

    private static final String[] NAMES = {
        "ag01-xp", "ag02-f3", "ag03-suggest", "ag04-usage", "ag05-subs", "ag06-f3f4", "ag07-anvil", "ag08-enchant",
        "ag09-adv", "ag10-jump", "ag11-spec", "ag12-telemetry", "ag13-report", "ag14-createworld",
    };
    /** Frame on which each stage is captured (its setup runs on frame 1). */
    private static final int[] CAP = { 45, 25, 40, 40, 30, 30, 35, 35, 85, 50, 22, 30, 30, 170 };

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
                try { srv.getCommandManager().executeWithPrefix(srv.getCommandSource(), c); }
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
            case 0 -> { mc.setScreen(null); mode(mc, GameMode.SURVIVAL); cmd(mc, "xp add @a 30 levels"); cmd(mc, "xp add @a 60 points"); }
            case 1 -> mc.options.debugEnabled = true;
            case 2 -> mc.setScreen(new ChatScreen("/"));    // + typed 'g' below (gamemode / gamerule / give …): vanilla
                                                             // only opens the popup once the text differs from the
                                                             // screen's initial text
            case 3 -> mc.setScreen(new ChatScreen("/xp add @a "));
            case 4 -> { prevSubs = mc.options.getShowSubtitles().getValue(); mc.options.getShowSubtitles().setValue(true); }
            case 5 -> { holdSwitcher = true; mc.setScreen(new GameModeSelectionScreen()); }
            case 6 -> {
                AnvilScreenHandler h = new AnvilScreenHandler(101, inv);
                h.getSlot(0).setStackNoCallbacks(new ItemStack(Items.DIAMOND_SWORD));
                mc.setScreen(new AnvilScreen(h, inv, Text.translatable("container.repair")));
            }
            case 7 -> {
                EnchantmentScreenHandler h = new EnchantmentScreenHandler(102, inv);
                h.getSlot(0).setStackNoCallbacks(new ItemStack(Items.DIAMOND_SWORD));
                h.getSlot(1).setStackNoCallbacks(new ItemStack(Items.LAPIS_LAZULI, 10));
                h.enchantmentPower[0] = 1;
                h.enchantmentPower[1] = 15;
                h.enchantmentPower[2] = 0;        // third row: unavailable (faint scrim) for contrast
                mc.setScreen(new EnchantmentScreen(h, inv, Text.translatable("container.enchant")));
            }
            case 8 -> cmd(mc, "advancement grant @a everything");
            case 9 -> cmd(mc, "execute as @p at @s run summon horse ~ ~ ~ {Tame:1b,SaddleItem:{id:\"minecraft:saddle\",Count:1b}}");
            case 10 -> { mode(mc, GameMode.SPECTATOR); }
            case 11 -> mc.setScreen(new TelemetryInfoScreen(parent, mc.options));
            case 12 -> mc.setScreen(new AbuseReportReasonScreen(parent, AbuseReportReason.HATE_SPEECH, r -> {}));
            case 13 -> CreateWorldScreen.create(mc, parent);
            default -> { }
        }
    }

    private static void during(MinecraftClient mc, int s) {
        try { mc.inGameHud.getChatHud().clear(false); } catch (Throwable ignored) {}
        switch (s) {
            case 2 -> {
                if (frames == 6 && mc.currentScreen != null) mc.currentScreen.charTyped('g', 0);
            }
            case 4 -> {
                if (frames == 3) mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_CHICKEN_AMBIENT, 1f));
                if (frames == 8) mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_COW_AMBIENT, 1f));
                if (frames == 13) mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_CHEST_OPEN, 1f));
            }
            case 8 -> {
                if (frames == 40) mc.setScreen(new AdvancementsScreen(mc.getNetworkHandler().getAdvancementHandler()));
            }
            case 9 -> {
                if (frames == 12) cmd(mc, "ride @p mount @e[type=horse,limit=1,sort=nearest]");
                if (frames >= 30) {
                    try {
                        var f = ClientPlayerEntity.class.getDeclaredField("mountJumpStrength");   // dev (named) runtime
                        f.setAccessible(true);
                        f.setFloat(mc.player, 0.65f);
                    } catch (Throwable ignored) {}
                }
            }
            case 10 -> {
                // first call opens the menu, the second selects slot 0 (without using it) so the lifted pill shows
                if (frames == 12 || frames == 16) mc.inGameHud.getSpectatorHud().selectSlot(0);
            }
            default -> { }
        }
    }

    private static void teardown(MinecraftClient mc, int s) {
        switch (s) {
            case 1 -> mc.options.debugEnabled = false;
            case 4 -> mc.options.getShowSubtitles().setValue(prevSubs);
            case 5 -> { mc.setScreen(null); holdSwitcher = false; }
            case 9 -> { cmd(mc, "ride @p dismount"); cmd(mc, "kill @e[type=horse]"); }
            case 10 -> mode(mc, GameMode.CREATIVE);
            default -> { if (mc.currentScreen != null) mc.setScreen(null); }
        }
    }
}
