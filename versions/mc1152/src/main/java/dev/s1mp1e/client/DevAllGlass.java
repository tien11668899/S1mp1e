package dev.s1mp1e.client;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayDeque;
import java.util.List;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.advancement.AdvancementsScreen;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.client.gui.screen.ingame.EnchantingScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.widget.LockButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.container.AnvilContainer;
import net.minecraft.container.EnchantingTableContainer;
import net.minecraft.entity.passive.HorseEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;
import net.minecraft.world.GameMode;

/**
 * DevShot {@code S1MP1E_SHOT_MODE=allglass} (1.15.2 port): the all-glass elements that only appear in a particular game
 * state, shot one after another in the dev world ({@code agNN-name.png}): XP bar (#17), F3 (#15), command suggestions
 * (#13) and usage (#14), subtitles (#16), anvil rename field + enchanting rows (#12), the advancement tab band (#23), the
 * horse jump bar (#17), the spectator hotbar (#20), create-world (#3 glass edit box) and the difficulty-lock button (#21),
 * plus a bordered text field test screen (#3, focused + unfocused). 1.15.2 has no F3+F4 game-mode switcher (1.16+), so
 * ag06 is not shot. Dev only — inert without {@code S1MP1E_SHOT}. Java 8; 1.15.2 idioms: {@code openScreen},
 * {@code player.inventory}, {@code AnvilContainer}/{@code EnchantingTableContainer}, {@code render(IIF)}.
 */
public final class DevAllGlass {
    private DevAllGlass() {}

    private static final String[] NAMES = {
        "ag01-xp", "ag02-f3", "ag03-suggest", "ag04-usage", "ag05-subs", "ag07-anvil", "ag08-enchant",
        "ag09-adv", "ag10-jump", "ag11-spec", "ag13-createworld", "ag15-lockbtn", "ag16-editbox",
    };
    private static final int[] CAP = { 45, 25, 40, 40, 30, 35, 35, 85, 50, 22, 120, 40, 40 };

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

    private static String name(int s) { return NAMES[s]; }

    private static void setup(MinecraftClient mc, int s) throws Exception {
        ClientPlayerEntity p = mc.player;
        PlayerInventory inv = p.inventory;
        GameMenuScreen parent = new GameMenuScreen(true);
        String n = name(s);
        if ("ag01-xp".equals(n)) { mc.openScreen(null); mode(mc, GameMode.SURVIVAL); cmd(mc, "xp add @a 30 levels"); cmd(mc, "xp add @a 60 points"); }
        else if ("ag02-f3".equals(n)) mc.options.debugEnabled = true;
        else if ("ag03-suggest".equals(n)) mc.openScreen(new ChatScreen("/"));        // + typed 'g' below opens the popup
        else if ("ag04-usage".equals(n)) mc.openScreen(new ChatScreen("/xp add @a "));
        else if ("ag05-subs".equals(n)) { prevSubs = mc.options.showSubtitles; mc.options.showSubtitles = true; }
        else if ("ag07-anvil".equals(n)) {
            AnvilContainer h = new AnvilContainer(101, inv);
            h.getSlot(0).setStack(new ItemStack(Items.DIAMOND_SWORD));
            mc.openScreen(new AnvilScreen(h, inv, new TranslatableText("container.repair")));
        } else if ("ag08-enchant".equals(n)) {
            EnchantingTableContainer h = new EnchantingTableContainer(102, inv);
            h.getSlot(0).setStack(new ItemStack(Items.DIAMOND_SWORD));
            h.getSlot(1).setStack(new ItemStack(Items.LAPIS_LAZULI, 10));
            h.enchantmentPower[0] = 1;
            h.enchantmentPower[1] = 15;
            h.enchantmentPower[2] = 0;        // third row: unavailable (faint scrim) for contrast
            mc.openScreen(new EnchantingScreen(h, inv, new TranslatableText("container.enchant")));
        } else if ("ag09-adv".equals(n)) cmd(mc, "advancement grant @a everything");
        else if ("ag10-jump".equals(n)) cmd(mc, "execute as @p at @s run summon horse ~ ~ ~ {Tame:1b,SaddleItem:{id:\"minecraft:saddle\",Count:1b}}");
        else if ("ag11-spec".equals(n)) mode(mc, GameMode.SPECTATOR);
        else if ("ag13-createworld".equals(n)) mc.openScreen(new CreateWorldScreen(parent));
        else if ("ag15-lockbtn".equals(n)) {
            // #21 LockButtonGlassMixin path (vanilla renderButton, outside the settings shell): locked + unlocked
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
                public void render(int mx, int my, float d) {
                    this.renderBackground();
                    super.render(mx, my, d);
                }
            });
        } else if ("ag16-editbox".equals(n)) {
            // #3 bordered single-line fields outside the recipe book / world select: one focused, one not
            mc.openScreen(new Screen(new LiteralText("fields")) {
                private TextFieldWidget a, b;
                protected void init() {
                    this.a = new TextFieldWidget(this.font, this.width / 2 - 100, this.height / 2 - 30, 200, 20, "a");
                    this.a.setText("S1mp1e 液態玻璃 (focused)");
                    this.b = new TextFieldWidget(this.font, this.width / 2 - 100, this.height / 2 + 4, 200, 20, "b");
                    this.b.setText("unfocused field");
                    this.children.add(this.a);
                    this.children.add(this.b);
                    this.a.changeFocus(true);
                    this.setFocused(this.a);
                }
                public void render(int mx, int my, float d) {
                    this.renderBackground();
                    this.a.render(mx, my, d);
                    this.b.render(mx, my, d);
                    super.render(mx, my, d);
                }
            });
        }
    }

    private static void during(final MinecraftClient mc, int s) {
        try { mc.inGameHud.getChatHud().clear(false); } catch (Throwable ignored) {}
        String n = name(s);
        if ("ag03-suggest".equals(n)) {
            if (frames == 6 && mc.currentScreen != null) mc.currentScreen.charTyped('g', 0);
        } else if ("ag05-subs".equals(n)) {
            if (frames == 3) mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_CHICKEN_AMBIENT, 1f));
            if (frames == 8) mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_COW_AMBIENT, 1f));
            if (frames == 13) mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_CHEST_OPEN, 1f));
        } else if ("ag09-adv".equals(n)) {
            if (frames == 40) mc.openScreen(new AdvancementsScreen(mc.getNetworkHandler().getAdvancementHandler()));
        } else if ("ag10-jump".equals(n)) {
            if (frames == 12) {   // mount on the server thread directly
                final MinecraftServer srv = mc.getServer();
                if (srv != null) srv.execute(new Runnable() {
                    public void run() {
                        try {
                            ServerPlayerEntity sp = srv.getPlayerManager().getPlayerList().get(0);
                            List<HorseEntity> horses = sp.getServerWorld().getEntities(HorseEntity.class,
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
                    // 1.15.2 mount-jump strength is the private float field_3922 (yarn leaves it unnamed); dev + prod name.
                    java.lang.reflect.Field f = ClientPlayerEntity.class.getDeclaredField("field_3922");
                    f.setAccessible(true);
                    f.setFloat(mc.player, 0.65f);
                } catch (Throwable ignored) {}
            }
        } else if ("ag11-spec".equals(n)) {
            if (frames == 12 || frames == 16) {
                try { mc.inGameHud.getSpectatorHud().selectSlot(0); } catch (Throwable ignored) {}
            }
        } else if ("ag13-createworld".equals(n)) {
            if (frames == 30) DevMenus.prep(mc);
        }
    }

    private static void teardown(MinecraftClient mc, int s) {
        String n = name(s);
        if ("ag02-f3".equals(n)) mc.options.debugEnabled = false;
        else if ("ag05-subs".equals(n)) mc.options.showSubtitles = prevSubs;
        else if ("ag10-jump".equals(n)) { try { if (mc.player != null) mc.player.stopRiding(); } catch (Throwable ignored) {} cmd(mc, "kill @e[type=horse]"); }
        else if ("ag11-spec".equals(n)) mode(mc, GameMode.CREATIVE);
        else { if (mc.currentScreen != null) mc.openScreen(null); }
    }
}
