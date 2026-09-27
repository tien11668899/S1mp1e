package dev.s1mp1e.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * DevShot mode "combat" (generated from the S1mp1e combat-port template): verifies the 26.2 combat trio on this
 * version - LowFire on / off, the AttackRing charging (circle / rounded square / crosshair wrap, clockwise and
 * counter-clockwise + rainbow), the ready ring and a ready shape that wraps the crosshair, and HitMarker on a hit, a REAL
 * falling crit and a kill with the "+" shape. Self-contained scene runner; only {@link DevShot#capture} is borrowed.
 */
final class CombatShot {
    private CombatShot() {}

    private interface Scene { boolean run(MinecraftClient c, int fr, double ms); }

    private static final List<Scene> scenes = new ArrayList<Scene>();
    private static boolean built;
    private static int si, fr;
    private static long t0;
    private static boolean attacked;
    private static double attackMs;

    /** One frame; true once every scene has run. */
    static boolean step(MinecraftClient c) {
        if (!built) { built = true; build(); si = 0; fr = 0; t0 = System.nanoTime(); say("combat: " + scenes.size() + " scenes"); }
        if (si >= scenes.size()) return true;
        fr++;
        double ms = (System.nanoTime() - t0) / 1.0e6;
        boolean done;
        try { done = scenes.get(si).run(c, fr, ms); } catch (Throwable t) { say("scene " + si + " failed: " + t); done = true; }
        if (done) { si++; fr = 0; t0 = System.nanoTime(); }
        return si >= scenes.size();
    }

    // ---- scene builders ------------------------------------------------------------------------------------------
    private interface Act { void run(MinecraftClient c) throws Throwable; }

    private static void add(Scene s) { scenes.add(s); }
    private static Scene action(final Act a) {
        return (c, f, ms) -> { try { a.run(c); } catch (Throwable t) { say("action failed: " + t); } return true; };
    }
    private static Scene waitMs(final double w) { return (c, f, ms) -> ms >= w && f >= 3; }
    private static Scene shot(final String name, final Act setup, final double wait, final Act each) {
        return (c, f, ms) -> {
            if (f == 1 && setup != null) { try { setup.run(c); } catch (Throwable t) { say("setup " + name + ": " + t); } }
            if (f >= 3 && each != null) { try { each.run(c); } catch (Throwable ignored) {} }
            if (ms >= wait && f >= 12) { DevShot.capture(c, name + ".png"); say("shot " + name); return true; }
            return false;
        };
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------
    private static void say(String s) { System.out.println("[S1mp1e][COMBAT] " + s); }

    private static void cmd(MinecraftClient c, final String command) {
        final MinecraftServer server = c.getServer();
        if (server == null) return;
        server.execute(() -> {
            try { server.getCommandManager().execute(server.getCommandSource().withSilent(), command); } catch (Throwable t) { say("command " + command + ": " + t); }
        });
    }

    private static ServerPlayerEntity sp(MinecraftClient c) {
        MinecraftServer s = c.getServer();
        return s == null || s.getPlayerManager().getPlayerList().isEmpty() ? null : s.getPlayerManager().getPlayerList().get(0);
    }

    private static void pitch(MinecraftClient c, float p) { if (c.player != null) { c.player.pitch = p; } }

    private static PigEntity pig(MinecraftClient c) {
        if (c.world == null || c.player == null) return null;
        PigEntity best = null;
        double bd = 1e9;
        for (Entity e : c.world.getEntities()) {
            if (e instanceof PigEntity && e.isAlive()) {
                double d = e.squaredDistanceTo(c.player);
                if (d < bd) { bd = d; best = (PigEntity) e; }
            }
        }
        return bd < 36 ? best : null;
    }

    private static void hit(MinecraftClient c) {
        PigEntity p = pig(c);
        if (p != null && c.interactionManager != null) c.interactionManager.attackEntity(c.player, p);
        say("attack " + (p != null ? "pig#" + p.getEntityId() + " hp=" + p.getHealth() : "NO PIG"));
    }

    private static void swing(MinecraftClient c) {
        if (c.player == null) return;
        c.player.resetLastAttackedTicks();
        c.player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
    }

    private static dev.s1mp1e.client.module.AttackRingModule ring() {
        return (dev.s1mp1e.client.module.AttackRingModule) ModuleManager.byName("AttackRing");
    }
    private static dev.s1mp1e.client.module.HitMarkerModule marker() {
        return (dev.s1mp1e.client.module.HitMarkerModule) ModuleManager.byName("HitMarker");
    }
    private static void lowFire(boolean on) { Module m = ModuleManager.byName("LowFire"); if (m != null) m.enabled = on; }

    private static final String PIG = "execute as @p at @p rotated ~ 0 run summon minecraft:pig ^ ^ ^2.2 {NoAI:1b,Silent:1b,";
    private static final String BIG_PIG = PIG + "Health:100f,Attributes:[{Name:\"generic.max_health\",Base:100d}]}";

    // ---- the scenes ------------------------------------------------------------------------------------------------
    private static void build() {
        add(action(c -> {
            if (c.currentScreen != null) c.openScreen(null);
            cmd(c, "gamemode survival @a");
            cmd(c, "effect give @a minecraft:fire_resistance 600 0 true");
            cmd(c, "kill @e[type=minecraft:pig]");
            // clear an arena (a random world can put trees / slopes right where the pig spawns -> it suffocates, and a
            // hit landing in its hurt-invulnerability sends no hurt status, so no marker)
            cmd(c, "execute as @p at @p run fill ~-5 ~ ~-5 ~5 ~5 ~5 minecraft:air");
            cmd(c, "execute as @p at @p run fill ~-5 ~-1 ~-5 ~5 ~-1 ~5 minecraft:grass_block");
            if (c.player != null) { c.player.inventory.selectedSlot = 0; }
            pitch(c, 0f);
        }));
        add(waitMs(800));
        // LowFire: burning, module on vs off
        add(action(c -> { final ServerPlayerEntity p = sp(c); if (p != null) c.getServer().execute(() -> { p.setFireTicks(600); }); }));
        add(shot("combat-fire-on", c -> lowFire(true), 700, null));
        add(shot("combat-fire-off", c -> lowFire(false), 300, null));
        add(action(c -> { lowFire(true); final ServerPlayerEntity p = sp(c); if (p != null) c.getServer().execute(p::extinguish); }));
        add(waitMs(600));
        // AttackRing charging (circle), then ready on a living target
        add(action(CombatShot::swing));
        add(shot("combat-ring-a", null, 120, null));
        add(shot("combat-ring-b", null, 150, null));
        add(action(c -> cmd(c, BIG_PIG)));
        add(shot("combat-ring-ready", c -> pitch(c, 28f), 1000, c -> pitch(c, 28f)));
        // HitMarker: a confirmed hit
        add((c, f, ms) -> {
            if (f == 1) { hit(c); return false; }
            if (ms >= 110 && f >= 4) { DevShot.capture(c, "combat-hit.png"); say("shot combat-hit"); return true; }
            return false;
        });
        add(waitMs(1000));
        // a REAL crit: up 1.5 blocks, attack on the way down (server: fallDistance > 0, not on ground)
        add(action(c -> cmd(c, "execute as @p at @p run tp @s ~ ~1.5 ~")));
        add((c, f, ms) -> {
            if (f == 1) { attacked = false; return false; }
            if (!attacked && c.player != null && f > 3 && c.player.getVelocity().y < -0.08 && !c.player.isOnGround()) {
                hit(c); attacked = true; attackMs = ms; return false;
            }
            if (attacked && ms - attackMs >= 110) { DevShot.capture(c, "combat-crit.png"); say("shot combat-crit"); return true; }
            return ms > 3000;
        });
        add(waitMs(900));
        // counter-clockwise + rainbow while charging
        add(action(c -> { if (ring() != null) { ring().clockwise.boolValue = false; ring().chroma.boolValue = true; } swing(c); }));
        add(shot("combat-ring-ccw-chroma", null, 300, null));
        add(action(c -> { if (ring() != null) { ring().clockwise.boolValue = true; ring().chroma.boolValue = false; } }));
        // shapes while charging
        for (final String sh : new String[] {"Square", "Wrap"}) {
            add(action(c -> { if (ring() != null) ring().shape.modeValue = sh; swing(c); }));
            add(shot("combat-ring-" + sh.toLowerCase(), null, 330, null));
        }
        // ready shape = Wrap at a tighter ready size
        add(action(c -> { if (ring() != null) { ring().shape.modeValue = "Circle"; ring().readyShape.modeValue = "Wrap"; ring().readyRadius.doubleValue = 9.0; } }));
        add(shot("combat-ring-ready-wrap", c -> pitch(c, 28f), 1100, c -> pitch(c, 28f)));
        add(action(c -> { if (ring() != null) { ring().readyShape.modeValue = "Same"; ring().readyRadius.doubleValue = 0.0; } }));
        // Fit crosshair: the S1mp1e crosshair (big, thick, rotated 45) -> the wrap follows it; a Circle -> a round wrap
        for (final String xs : new String[] {"Cross", "Circle"}) {
            add(action(c -> {
                dev.s1mp1e.client.module.CrosshairModule ch =
                        (dev.s1mp1e.client.module.CrosshairModule) ModuleManager.byName("Crosshair");
                if (ch != null) {
                    ch.enabled = true; ch.shape.modeValue = xs;
                    ch.size.intValue = 7; ch.gap.intValue = 3; ch.thick.intValue = 2;
                    ch.rotation.intValue = "Cross".equals(xs) ? 45 : 0;
                }
                if (ring() != null) ring().shape.modeValue = "Wrap";
                swing(c);
            }));
            add(shot("combat-fit-" + xs.toLowerCase(), null, 330, null));
        }
        add(action(c -> {
            dev.s1mp1e.client.module.CrosshairModule ch =
                    (dev.s1mp1e.client.module.CrosshairModule) ModuleManager.byName("Crosshair");
            if (ch != null) {
                ch.enabled = false; ch.shape.modeValue = "Cross";
                ch.size.intValue = 4; ch.gap.intValue = 2; ch.thick.intValue = 1; ch.rotation.intValue = 0;
            }
            if (ring() != null) ring().shape.modeValue = "Circle";
        }));
        // "+" shape and a KILL (a 1-HP pig) -> kill colour
        add(action(c -> {
            if (marker() != null) marker().shape.modeValue = "Cross";
            cmd(c, "kill @e[type=minecraft:pig]");
            cmd(c, PIG + "Health:1f}");
        }));
        add(waitMs(1100));
        add((c, f, ms) -> {
            if (f == 1) { pitch(c, 28f); hit(c); return false; }
            if (ms >= 110 && f >= 4) { DevShot.capture(c, "combat-kill-cross.png"); say("shot combat-kill-cross"); return true; }
            return false;
        });
        add(action(c -> { if (marker() != null) marker().shape.modeValue = "X"; cmd(c, "kill @e[type=minecraft:pig]"); pitch(c, 0f); }));
        add(waitMs(300));
    }
}
