package dev.s1mp1e.glass.asm;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Iterator;

/**
 * Two surgical rewrites that let the glass actually REPLACE vanilla chrome
 * instead of being painted under it.
 *
 * <ol>
 *   <li><b>{@code GuiButton.drawButton}</b> — a guard is spliced onto the method
 *       head: if {@link ButtonHook#draw} handled the widget (drew the capsule and
 *       its label), the method returns immediately and vanilla's widgets.png blit
 *       never runs.</li>
 *   <li><b>{@code GuiContainer.drawScreen}</b> — the single
 *       {@code INVOKEVIRTUAL drawGuiContainerBackgroundLayer} is left in place
 *       and merely bracketed with {@link ContainerHook#arm} /
 *       {@link ContainerHook#disarm}, so vanilla's virtual dispatch runs
 *       untouched while {@link BlitSuppressor} drops only the opaque panel
 *       strips. Everything drawScreen does afterwards — slots, items, tooltips —
 *       is untouched.</li>
 * </ol>
 *
 * <p>Both are name-tolerant: the MCP names are used in a dev workspace and the
 * 1.8.9 SRG names in production, and each patch is skipped (loudly) rather than
 * throwing if its target isn't found, so a mismatch degrades to "no glass"
 * instead of a crash on startup.
 */
public final class S1mp1eTransformer implements IClassTransformer {

    // ---- targets ----------------------------------------------------------

    private static final String GUI_BUTTON     = "net.minecraft.client.gui.GuiButton";
    private static final String GUI_BUTTON_EXT = "net.minecraftforge.fml.client.config.GuiButtonExt";
    private static final String GUI_CONTAINER  = "net.minecraft.client.gui.inventory.GuiContainer";

    // drawButton(Minecraft, int, int)V
    private static final String DRAW_BUTTON_MCP = "drawButton";
    private static final String DRAW_BUTTON_SRG = "func_146112_a";
    private static final String DRAW_BUTTON_DESC = "(Lnet/minecraft/client/Minecraft;II)V";

    // drawScreen(int, int, float)V
    private static final String DRAW_SCREEN_MCP = "drawScreen";
    private static final String DRAW_SCREEN_SRG = "func_73863_a";
    private static final String DRAW_SCREEN_DESC = "(IIF)V";

    // drawGuiContainerBackgroundLayer(float, int, int)V
    private static final String BG_LAYER_MCP = "drawGuiContainerBackgroundLayer";
    private static final String BG_LAYER_SRG = "func_146976_a";
    private static final String BG_LAYER_DESC = "(FII)V";

    private static final String GUI        = "net.minecraft.client.gui.Gui";
    private static final String GUI_SCREEN = "net.minecraft.client.gui.GuiScreen";
    private static final String MAIN_MENU  = "net.minecraft.client.gui.GuiMainMenu";
    private static final String GUI_SLOT   = "net.minecraft.client.gui.GuiSlot";

    // InventoryEffectRenderer.drawActivePotionEffects()V — the survival/creative
    // effect-box drawer. Suppressed at its head so the glass strip (drawn earlier,
    // at BackgroundDrawnEvent) is not painted over — and, crucially, so it is not
    // painted over the tooltip (this runs AFTER super.drawScreen, i.e. after the
    // tooltip). See GlassEffects.
    private static final String EFFECT_RENDERER = "net.minecraft.client.renderer.InventoryEffectRenderer";
    private static final String EFFECTS_MCP  = "drawActivePotionEffects";
    private static final String EFFECTS_SRG  = "func_147044_g";
    private static final String EFFECTS_DESC = "()V";
    private static final String HOOKS_EFFECTS = "dev/s1mp1e/glass/render/GlassEffects";

    // GuiSlot.drawContainerBackground(Tessellator)V — Forge-added, so it keeps its
    // MCP name in production (not SRG-renamed).
    private static final String LIST_BG_NAME = "drawContainerBackground";
    private static final String LIST_BG_DESC = "(Lnet/minecraft/client/renderer/Tessellator;)V";
    // overlayBackground(int startY, int endY, int startAlpha, int endAlpha)V
    private static final String LIST_OVL_MCP  = "overlayBackground";
    private static final String LIST_OVL_SRG  = "func_148136_c";
    private static final String LIST_OVL_DESC = "(IIII)V";

    // drawHoveringText(List,int,int,FontRenderer)V — every tooltip funnels here.
    // Forge-added overload, so it keeps its name in production (not SRG-renamed).
    private static final String TIP_NAME = "drawHoveringText";
    private static final String TIP_DESC =
        "(Ljava/util/List;IILnet/minecraft/client/gui/FontRenderer;)V";

    // renderSkybox(int,int,float)V — the title screen's panorama pass
    private static final String SKY_MCP  = "renderSkybox";
    private static final String SKY_SRG  = "func_73971_c";
    private static final String SKY_DESC = "(IIF)V";

    // drawBackground(int)V — vanilla's tiled dirt behind world-less screens
    private static final String DIRT_MCP  = "drawBackground";
    private static final String DIRT_SRG  = "func_146278_c";
    private static final String DIRT_DESC = "(I)V";

    // drawTexturedModalRect(int,int,int,int,int,int)V — the panel blit
    private static final String BLIT_MCP  = "drawTexturedModalRect";
    private static final String BLIT_SRG  = "func_73729_b";
    private static final String BLIT_DESC = "(IIIIII)V";

    // drawSlot(Slot)V + the static drawRect(int,int,int,int,int)V it uses for
    // the flat white drag-distribute square
    private static final String SLOT_MCP  = "drawSlot";
    private static final String SLOT_SRG  = "func_146977_a";
    private static final String SLOT_DESC = "(Lnet/minecraft/inventory/Slot;)V";
    private static final String RECT_MCP  = "drawRect";
    private static final String RECT_SRG  = "func_73734_a";
    private static final String RECT_DESC = "(IIIII)V";

    // drawGradientRect(int,int,int,int,int,int)V — vanilla's white slot hover
    private static final String GRAD_MCP  = "drawGradientRect";
    private static final String GRAD_SRG  = "func_73733_a";
    private static final String GRAD_DESC = "(IIIIII)V";

    private static final String HOOKS_SUPPRESS = "dev/s1mp1e/glass/asm/BlitSuppressor";
    private static final String HOOKS_HOVER    = "dev/s1mp1e/glass/asm/HoverHook";
    private static final String HOOKS_BACKDROP = "dev/s1mp1e/glass/asm/MenuBackdropHook";
    private static final String HOOKS_TOOLTIP  = "dev/s1mp1e/glass/asm/TooltipHook";
    private static final String HOOKS_BUTTON    = "dev/s1mp1e/glass/asm/ButtonHook";
    private static final String HOOKS_CONTAINER = "dev/s1mp1e/glass/asm/ContainerHook";
    private static final String HOOKS_GLIDE     = "dev/s1mp1e/glass/hook/GlassCreativeGlide";

    // mouseClicked(int,int,int)V — GuiContainer's override; the creative grid item
    // hit-test happens here (via super), so a mid-glide click snaps first (feature D).
    private static final String CLICK_MCP  = "mouseClicked";
    private static final String CLICK_SRG  = "func_73864_a";
    private static final String CLICK_DESC = "(III)V";

    // ---- Batch B (G) HUD overlay targets ----------------------------------

    // GuiNewChat.drawChat(int)V  (feature G1 — chat panel; drawRect redirect drops per-line bg)
    private static final String GUI_NEW_CHAT = "net.minecraft.client.gui.GuiNewChat";
    private static final String CHAT_MCP  = "drawChat";
    private static final String CHAT_SRG  = "func_146230_a";
    private static final String CHAT_DESC = "(I)V";

    // GuiChat.drawScreen(int,int,float)V  (feature G1 — input bar; first drawRect redirect)
    private static final String GUI_CHAT = "net.minecraft.client.gui.GuiChat";

    // GuiPlayerTabOverlay.renderPlayerlist(int,Scoreboard,ScoreObjective)V  (feature G2)
    private static final String TAB_OVERLAY = "net.minecraft.client.gui.GuiPlayerTabOverlay";
    private static final String TAB_MCP  = "renderPlayerlist";
    private static final String TAB_SRG  = "func_175249_a";
    private static final String TAB_DESC = "(ILnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/ScoreObjective;)V";

    // GuiIngameForge — boss bar (G3) head splice + record/overlay-message (G5) drawString redirect
    private static final String INGAME_FORGE = "net.minecraftforge.client.GuiIngameForge";
    // renderBossHealth()V — MC method the Forge subclass overrides (SRG in production)
    private static final String BOSS_MCP  = "renderBossHealth";
    private static final String BOSS_SRG  = "func_73828_d";
    private static final String BOSS_DESC = "()V";
    // renderRecordOverlay(int,int,float)V — a Forge-only method, so its name is stable in production
    private static final String REC_NAME  = "renderRecordOverlay";
    private static final String REC_DESC  = "(IIF)V";
    // FontRenderer.drawString(String,int,int,int)I — the overlay-message text draw
    private static final String FONT_RENDERER = "net/minecraft/client/gui/FontRenderer";
    private static final String FDRAW_MCP  = "drawString";
    private static final String FDRAW_SRG  = "func_78276_b";
    private static final String FDRAW_DESC = "(Ljava/lang/String;III)I";

    // Render.renderLivingLabel(Entity,String,double,double,double,int)V  (feature G6 — name tags)
    private static final String RENDER = "net.minecraft.client.renderer.entity.Render";
    private static final String LABEL_MCP  = "renderLivingLabel";
    private static final String LABEL_SRG  = "func_147906_a";
    private static final String LABEL_DESC = "(Lnet/minecraft/entity/Entity;Ljava/lang/String;DDDI)V";
    // WorldRenderer.color(float,float,float,float)WorldRenderer — the label plate vertices
    private static final String WORLD_RENDERER = "net/minecraft/client/renderer/WorldRenderer";
    private static final String WRCOLOR_MCP  = "color";
    private static final String WRCOLOR_SRG  = "func_181666_a";
    private static final String WRCOLOR_DESC = "(FFFF)Lnet/minecraft/client/renderer/WorldRenderer;";

    private static final String HOOKS_CHAT      = "dev/s1mp1e/glass/hook/GlassChatHud";
    private static final String HOOKS_TABLIST   = "dev/s1mp1e/glass/hook/GlassTabList";
    private static final String HOOKS_BOSSBAR   = "dev/s1mp1e/glass/hook/GlassBossBar";
    private static final String HOOKS_ACTIONBAR = "dev/s1mp1e/glass/hook/GlassActionBar";
    private static final String HOOKS_NAMETAG   = "dev/s1mp1e/glass/hook/GlassNameTag";

    // ---- coremod patch-site audit -----------------------------------------
    // Recorded as each target class is transformed (lazily, at class-load time). Read by
    // {@code dev.s1mp1e.client.DevShot} when S1MP1E_AUDIT is set, so the harness can log every
    // coremod patch site and its outcome — the Forge counterpart of a Mixin environment audit.
    // This class is a transformer exclusion, so it is loaded once by the LaunchClassLoader and the
    // statics are shared with the (also LaunchClassLoader-loaded) DevShot.
    private static final java.util.List<String> AUDIT =
            java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
    private static volatile boolean AUDIT_FAILED = false;

    public static java.util.List<String> auditSnapshot() {
        synchronized (AUDIT) { return new java.util.ArrayList<String>(AUDIT); }
    }
    public static boolean auditFailed() { return AUDIT_FAILED; }

    private static void auditOk(String site) { AUDIT.add("OK   " + site); }
    private static void auditFail(String site, String why) {
        AUDIT.add("FAIL " + site + " (" + why + ")");
        AUDIT_FAILED = true;
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return null;
        try {
            if (GUI_BUTTON.equals(transformedName)) {
                return patchButton(basicClass);
            }
            if (GUI_BUTTON_EXT.equals(transformedName)) {
                return patchButtonExt(basicClass);
            }
            if (GUI_CONTAINER.equals(transformedName)) {
                return patchContainer(basicClass);
            }
            if (GUI.equals(transformedName)) {
                return patchGui(basicClass);
            }
            if (GUI_SCREEN.equals(transformedName)) {
                return patchScreenBackground(basicClass);
            }
            if (MAIN_MENU.equals(transformedName)) {
                return patchMainMenu(basicClass);
            }
            if (GUI_SLOT.equals(transformedName)) {
                return patchGuiSlot(basicClass);
            }
            if (EFFECT_RENDERER.equals(transformedName)) {
                return patchEffects(basicClass);
            }
            if (GUI_NEW_CHAT.equals(transformedName)) {
                return patchChat(basicClass);
            }
            if (GUI_CHAT.equals(transformedName)) {
                return patchChatInput(basicClass);
            }
            if (TAB_OVERLAY.equals(transformedName)) {
                return patchTabList(basicClass);
            }
            if (INGAME_FORGE.equals(transformedName)) {
                return patchIngameForge(basicClass);
            }
            if (RENDER.equals(transformedName)) {
                return patchNameTag(basicClass);
            }
        } catch (Throwable t) {
            // Never take the game down over a failed patch — fall back to vanilla.
            System.out.println("[S1mp1e/ASM] patch of " + transformedName + " failed: " + t);
        }
        return basicClass;
    }

    // -----------------------------------------------------------------------
    // 1) GuiButton.drawButton -> early-return when our painter handled it
    // -----------------------------------------------------------------------
    private static byte[] patchButton(byte[] basic) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, DRAW_BUTTON_MCP, DRAW_BUTTON_SRG, DRAW_BUTTON_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] GuiButton.drawButton not found, skipping");
            auditFail("GuiButton.drawButton", "method not found");
            return basic;
        }

        // if (ButtonHook.draw(this, mc, mouseX, mouseY)) return;
        LabelNode passThrough = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));   // this
        pre.add(new VarInsnNode(Opcodes.ALOAD, 1));   // Minecraft
        pre.add(new VarInsnNode(Opcodes.ILOAD, 2));   // mouseX
        pre.add(new VarInsnNode(Opcodes.ILOAD, 3));   // mouseY
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_BUTTON, "draw",
                "(Lnet/minecraft/client/gui/GuiButton;Lnet/minecraft/client/Minecraft;II)Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, passThrough));
        pre.add(new InsnNode(Opcodes.RETURN));
        pre.add(passThrough);

        m.instructions.insert(pre);
        System.out.println("[S1mp1e/ASM] patched GuiButton.drawButton");
        auditOk("GuiButton.drawButton");
        return write(cn);
    }

    // -----------------------------------------------------------------------
    // 1b) GuiButtonExt.drawButton -> the same early-return splice, so FML config
    //     GUI buttons become capsules too. GuiButtonExt overrides drawButton, so
    //     the GuiButton splice never runs for it; it needs its own. Because this
    //     override shadows vanilla GuiButton.drawButton (SRG func_146112_a), the
    //     reobfuscator renames it to func_146112_a in production too, so we must
    //     try BOTH the MCP name (dev) and the SRG name (production), exactly like
    //     patchButton above — otherwise the patch silently no-ops on Feather and
    //     FML config-screen buttons stay vanilla-styled.
    // -----------------------------------------------------------------------
    private static byte[] patchButtonExt(byte[] basic) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, DRAW_BUTTON_MCP, DRAW_BUTTON_SRG, DRAW_BUTTON_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] GuiButtonExt.drawButton not found, skipping");
            auditFail("GuiButtonExt.drawButton", "method not found");
            return basic;
        }
        LabelNode passThrough = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));   // this (GuiButtonExt <: GuiButton)
        pre.add(new VarInsnNode(Opcodes.ALOAD, 1));   // Minecraft
        pre.add(new VarInsnNode(Opcodes.ILOAD, 2));   // mouseX
        pre.add(new VarInsnNode(Opcodes.ILOAD, 3));   // mouseY
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_BUTTON, "draw",
                "(Lnet/minecraft/client/gui/GuiButton;Lnet/minecraft/client/Minecraft;II)Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, passThrough));
        pre.add(new InsnNode(Opcodes.RETURN));
        pre.add(passThrough);
        m.instructions.insert(pre);
        System.out.println("[S1mp1e/ASM] patched GuiButtonExt.drawButton");
        auditOk("GuiButtonExt.drawButton");
        return write(cn);
    }

    // -----------------------------------------------------------------------
    // 2) GuiContainer.drawScreen -> retarget the background-layer call
    // -----------------------------------------------------------------------
    private static byte[] patchContainer(byte[] basic) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, DRAW_SCREEN_MCP, DRAW_SCREEN_SRG, DRAW_SCREEN_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] GuiContainer.drawScreen not found, skipping");
            auditFail("GuiContainer.drawScreen", "method not found");
            return basic;
        }

        int patched = 0;
        Iterator<AbstractInsnNode> it = m.instructions.iterator();
        while (it.hasNext()) {
            AbstractInsnNode insn = it.next();
            if (insn.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            boolean isBgLayer = (BG_LAYER_MCP.equals(call.name) || BG_LAYER_SRG.equals(call.name))
                                && BG_LAYER_DESC.equals(call.desc);
            if (!isBgLayer) continue;

            // Leave the virtual dispatch untouched: bracket it with arm/disarm so
            // BlitSuppressor drops only the panel strips while the vanilla layer
            // (player model, furnace fire, slot icons) runs normally.
            //   ... push this; INVOKESTATIC ContainerHook.arm(GuiContainer)V   <- before
            //   INVOKEVIRTUAL drawGuiContainerBackgroundLayer                   (unchanged)
            //   INVOKESTATIC ContainerHook.disarm()V                            <- after
            // arm() pushes and consumes its own operand, so the call's own
            // [GuiContainer, float, int, int] stack is left exactly as it was.
            InsnList before = new InsnList();
            before.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this (GuiContainer)
            before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_CONTAINER, "arm",
                    "(Lnet/minecraft/client/gui/inventory/GuiContainer;)V", false));
            m.instructions.insertBefore(call, before);
            m.instructions.insert(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                    HOOKS_CONTAINER, "disarm", "()V", false));
            patched++;
            break;
        }

        // Vanilla also paints a flat white square over the hovered slot
        // (drawGradientRect with 0x80FFFFFF twice). The glass hover pill stands
        // in for it, so route that call to a hook that draws nothing while the
        // glass path is live.
        int hover = 0;
        it = m.instructions.iterator();
        while (it.hasNext()) {
            AbstractInsnNode insn = it.next();
            if (insn.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            boolean isGrad = (GRAD_MCP.equals(call.name) || GRAD_SRG.equals(call.name))
                             && GRAD_DESC.equals(call.desc);
            if (!isGrad) continue;
            m.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                    HOOKS_HOVER, "slotHighlight",
                    "(Lnet/minecraft/client/gui/Gui;IIIIII)V", false));
            hover++;
        }

        // drawSlot paints a hard-cornered white rect over every slot in the
        // drag set; our rounded glass already covers that, so route it away.
        int drag = 0;
        int glide = 0;
        MethodNode slotM = find(cn, SLOT_MCP, SLOT_SRG, SLOT_DESC);
        if (slotM != null) {
            // Feature D: head-splice so the creative grid slots are drawn by the
            // sub-pixel glide overlay while it slides.
            //   if (GlassCreativeGlide.onDrawSlot(this, slot)) return;
            LabelNode passSlot = new LabelNode();
            InsnList pre = new InsnList();
            pre.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this (GuiContainer)
            pre.add(new VarInsnNode(Opcodes.ALOAD, 1)); // Slot
            pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_GLIDE, "onDrawSlot",
                    "(Lnet/minecraft/client/gui/inventory/GuiContainer;Lnet/minecraft/inventory/Slot;)Z", false));
            pre.add(new JumpInsnNode(Opcodes.IFEQ, passSlot));
            pre.add(new InsnNode(Opcodes.RETURN));
            pre.add(passSlot);
            slotM.instructions.insert(pre);
            glide++;

            Iterator<AbstractInsnNode> sit = slotM.instructions.iterator();
            while (sit.hasNext()) {
                AbstractInsnNode insn = sit.next();
                if (insn.getOpcode() != Opcodes.INVOKESTATIC) continue;
                MethodInsnNode call = (MethodInsnNode) insn;
                boolean isRect = (RECT_MCP.equals(call.name) || RECT_SRG.equals(call.name))
                                 && RECT_DESC.equals(call.desc);
                if (!isRect) continue;
                slotM.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        HOOKS_HOVER, "dragHighlight", RECT_DESC, false));
                drag++;
            }
        }
        if (drag > 0) {
            System.out.println("[S1mp1e/ASM] patched GuiContainer.drawSlot (drag rects: " + drag + ")");
        }
        if (glide > 0) {
            System.out.println("[S1mp1e/ASM] patched GuiContainer.drawSlot (glide overlay head-splice)");
        } else {
            auditFail("GuiContainer.drawSlot", "method not found (glide overlay not spliced)");
        }

        // Feature D: a mid-glide click snaps the grid to the target row first, so
        // the click acts on the item drawn under the cursor.
        //   GlassCreativeGlide.onContainerMouseClicked(this);
        int click = 0;
        MethodNode clickM = find(cn, CLICK_MCP, CLICK_SRG, CLICK_DESC);
        if (clickM != null) {
            InsnList pre = new InsnList();
            pre.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this (GuiContainer)
            pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_GLIDE, "onContainerMouseClicked",
                    "(Lnet/minecraft/client/gui/inventory/GuiContainer;)V", false));
            clickM.instructions.insert(pre);
            click++;
            System.out.println("[S1mp1e/ASM] patched GuiContainer.mouseClicked (glide snap-on-click)");
        } else {
            auditFail("GuiContainer.mouseClicked", "method not found (glide snap not spliced)");
        }

        if (patched == 0) {
            System.out.println("[S1mp1e/ASM] background-layer call not found in drawScreen");
            auditFail("GuiContainer.drawScreen", "backgroundLayer call not found");
            return basic;
        }
        System.out.println("[S1mp1e/ASM] patched GuiContainer.drawScreen (hover redirects: " + hover + ")");
        auditOk("GuiContainer.drawScreen (bgLayer, hover=" + hover + ", drag=" + drag
                + ", glideSlot=" + glide + ", clickSnap=" + click + ")");
        return write(cn);
    }

    // -----------------------------------------------------------------------
    // 3) Gui.drawTexturedModalRect -> skip exactly the container panel blit
    // -----------------------------------------------------------------------
    private static byte[] patchGui(byte[] basic) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, BLIT_MCP, BLIT_SRG, BLIT_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] Gui.drawTexturedModalRect not found, skipping");
            auditFail("Gui.drawTexturedModalRect", "method not found");
            return basic;
        }
        // if (BlitSuppressor.consume(x, y, u, v, w, h)) return;
        // drawTexturedModalRect(int x, int y, int u, int v, int w, int h) —
        // params in local slots 1..6 (slot 0 is `this`).
        LabelNode pass = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ILOAD, 1));
        pre.add(new VarInsnNode(Opcodes.ILOAD, 2));
        pre.add(new VarInsnNode(Opcodes.ILOAD, 3));
        pre.add(new VarInsnNode(Opcodes.ILOAD, 4));
        pre.add(new VarInsnNode(Opcodes.ILOAD, 5));
        pre.add(new VarInsnNode(Opcodes.ILOAD, 6));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_SUPPRESS, "consume", "(IIIIII)Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, pass));
        pre.add(new InsnNode(Opcodes.RETURN));
        pre.add(pass);
        m.instructions.insert(pre);
        System.out.println("[S1mp1e/ASM] patched Gui.drawTexturedModalRect");
        auditOk("Gui.drawTexturedModalRect");
        return write(cn);
    }

    // -----------------------------------------------------------------------
    // 4) GuiScreen.drawBackground -> blurred title frame instead of dirt
    // -----------------------------------------------------------------------
    private static byte[] patchScreenBackground(byte[] basic) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, DIRT_MCP, DIRT_SRG, DIRT_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] GuiScreen.drawBackground not found, skipping");
            auditFail("GuiScreen.drawBackground", "method not found");
            return basic;
        }
        // if (MenuBackdropHook.draw(this, tint)) return;
        LabelNode pass = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));   // this
        pre.add(new VarInsnNode(Opcodes.ILOAD, 1));   // tint
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_BACKDROP, "draw",
                "(Lnet/minecraft/client/gui/GuiScreen;I)Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, pass));
        pre.add(new InsnNode(Opcodes.RETURN));
        pre.add(pass);
        m.instructions.insert(pre);

        // Same class owns every tooltip: splice the glass tooltip onto
        // drawHoveringText's head too.
        MethodNode tip = null;
        for (MethodNode mn : cn.methods) {
            if (TIP_NAME.equals(mn.name) && TIP_DESC.equals(mn.desc)) { tip = mn; break; }
        }
        if (tip != null) {
            LabelNode tpass = new LabelNode();
            InsnList tpre = new InsnList();
            tpre.add(new VarInsnNode(Opcodes.ALOAD, 0));   // this
            tpre.add(new VarInsnNode(Opcodes.ALOAD, 1));   // List<String>
            tpre.add(new VarInsnNode(Opcodes.ILOAD, 2));   // x
            tpre.add(new VarInsnNode(Opcodes.ILOAD, 3));   // y
            tpre.add(new VarInsnNode(Opcodes.ALOAD, 4));   // FontRenderer
            tpre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_TOOLTIP, "draw",
                    "(Lnet/minecraft/client/gui/GuiScreen;Ljava/util/List;IILnet/minecraft/client/gui/FontRenderer;)Z",
                    false));
            tpre.add(new JumpInsnNode(Opcodes.IFEQ, tpass));
            tpre.add(new InsnNode(Opcodes.RETURN));
            tpre.add(tpass);
            tip.instructions.insert(tpre);
            System.out.println("[S1mp1e/ASM] patched GuiScreen.drawHoveringText");
            auditOk("GuiScreen.drawHoveringText");
        } else {
            System.out.println("[S1mp1e/ASM] drawHoveringText not found, tooltips stay vanilla");
            auditFail("GuiScreen.drawHoveringText", "method not found");
        }

        System.out.println("[S1mp1e/ASM] patched GuiScreen.drawBackground");
        auditOk("GuiScreen.drawBackground");
        return write(cn);
    }

    // -----------------------------------------------------------------------
    // 5) GuiMainMenu.drawScreen -> snapshot the panorama, and only the panorama
    // -----------------------------------------------------------------------
    private static byte[] patchMainMenu(byte[] basic) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, DRAW_SCREEN_MCP, DRAW_SCREEN_SRG, DRAW_SCREEN_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] GuiMainMenu.drawScreen not found, skipping");
            auditFail("GuiMainMenu.drawScreen", "method not found");
            return basic;
        }
        // The capture goes immediately AFTER renderSkybox returns: at that
        // instant the panorama is the only thing on screen. Capturing at the end
        // of the frame instead would bake in the logo, splash and buttons —
        // what we want blurred is the title screen's BACKGROUND, not the title
        // screen.
        int hit = 0;
        Iterator<AbstractInsnNode> it = m.instructions.iterator();
        while (it.hasNext()) {
            AbstractInsnNode insn = it.next();
            int op = insn.getOpcode();
            if (op != Opcodes.INVOKESPECIAL && op != Opcodes.INVOKEVIRTUAL) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            boolean isSky = (SKY_MCP.equals(call.name) || SKY_SRG.equals(call.name))
                            && SKY_DESC.equals(call.desc);
            if (!isSky) continue;
            m.instructions.insert(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                    HOOKS_BACKDROP, "capturePanorama", "()V", false));
            hit++;
            break;
        }
        if (hit == 0) {
            System.out.println("[S1mp1e/ASM] renderSkybox call not found in GuiMainMenu");
            auditFail("GuiMainMenu.drawScreen", "renderSkybox call not found");
            return basic;
        }
        System.out.println("[S1mp1e/ASM] patched GuiMainMenu.drawScreen (panorama capture)");
        auditOk("GuiMainMenu.drawScreen (panorama)");
        return write(cn);
    }

    // -----------------------------------------------------------------------
    // 6) GuiSlot list screens -> blurred backdrop instead of dirt
    // -----------------------------------------------------------------------
    private static byte[] patchGuiSlot(byte[] basic) {
        ClassNode cn = read(basic);
        int done = 0;

        // drawContainerBackground(Tessellator): the whole tiled dirt behind the list.
        // if (MenuBackdropHook.listBackground(this)) return;
        MethodNode bg = find(cn, LIST_BG_NAME, LIST_BG_NAME, LIST_BG_DESC);
        if (bg != null) {
            LabelNode pass = new LabelNode();
            InsnList pre = new InsnList();
            pre.add(new VarInsnNode(Opcodes.ALOAD, 0));   // this (GuiSlot)
            pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_BACKDROP, "listBackground",
                    "(Lnet/minecraft/client/gui/GuiSlot;)Z", false));
            pre.add(new JumpInsnNode(Opcodes.IFEQ, pass));
            pre.add(new InsnNode(Opcodes.RETURN));
            pre.add(pass);
            bg.instructions.insert(pre);
            done++;
            auditOk("GuiSlot.drawContainerBackground");
        } else {
            System.out.println("[S1mp1e/ASM] GuiSlot.drawContainerBackground not found, skipping");
            auditFail("GuiSlot.drawContainerBackground", "method not found");
        }

        // overlayBackground(startY,endY,...): the header/footer dirt strips.
        // if (MenuBackdropHook.listOverlay(this, startY, endY)) return;
        MethodNode ovl = find(cn, LIST_OVL_MCP, LIST_OVL_SRG, LIST_OVL_DESC);
        if (ovl != null) {
            LabelNode pass = new LabelNode();
            InsnList pre = new InsnList();
            pre.add(new VarInsnNode(Opcodes.ALOAD, 0));   // this (GuiSlot)
            pre.add(new VarInsnNode(Opcodes.ILOAD, 1));   // startY
            pre.add(new VarInsnNode(Opcodes.ILOAD, 2));   // endY
            pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_BACKDROP, "listOverlay",
                    "(Lnet/minecraft/client/gui/GuiSlot;II)Z", false));
            pre.add(new JumpInsnNode(Opcodes.IFEQ, pass));
            pre.add(new InsnNode(Opcodes.RETURN));
            pre.add(pass);
            ovl.instructions.insert(pre);
            done++;
            auditOk("GuiSlot.overlayBackground");
        } else {
            System.out.println("[S1mp1e/ASM] GuiSlot.overlayBackground not found, skipping");
            auditFail("GuiSlot.overlayBackground", "method not found");
        }

        if (done == 0) return basic;
        System.out.println("[S1mp1e/ASM] patched GuiSlot list background (sites: " + done + ")");
        return write(cn);
    }

    // -----------------------------------------------------------------------
    // 7) InventoryEffectRenderer.drawActivePotionEffects -> suppress once the
    //    glass strip has drawn (GlassEffects.consumeArmed()).
    // -----------------------------------------------------------------------
    private static byte[] patchEffects(byte[] basic) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, EFFECTS_MCP, EFFECTS_SRG, EFFECTS_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] InventoryEffectRenderer.drawActivePotionEffects not found, skipping");
            auditFail("InventoryEffectRenderer.drawActivePotionEffects", "method not found");
            return basic;
        }
        // if (GlassEffects.consumeArmed()) return;
        LabelNode pass = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_EFFECTS, "consumeArmed", "()Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, pass));
        pre.add(new InsnNode(Opcodes.RETURN));
        pre.add(pass);
        m.instructions.insert(pre);
        System.out.println("[S1mp1e/ASM] patched InventoryEffectRenderer.drawActivePotionEffects");
        auditOk("InventoryEffectRenderer.drawActivePotionEffects");
        return write(cn);
    }

    // -----------------------------------------------------------------------
    // 8) GuiNewChat.drawChat -> panel (G1). Head-splice GlassChatHud.begin(this,
    //    updateCounter) to draw one frosted panel behind the visible column, then
    //    redirect every per-line drawRect to GlassChatHud.rect (drops the dark
    //    background rects, keeps the scrollbar rects).
    // -----------------------------------------------------------------------
    private static byte[] patchChat(byte[] basic) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, CHAT_MCP, CHAT_SRG, CHAT_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] GuiNewChat.drawChat not found, skipping");
            auditFail("GuiNewChat.drawChat", "method not found");
            return basic;
        }
        // head: GlassChatHud.begin(this, updateCounter)
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));   // this (GuiNewChat)
        pre.add(new VarInsnNode(Opcodes.ILOAD, 1));   // updateCounter
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_CHAT, "begin",
                "(Lnet/minecraft/client/gui/GuiNewChat;I)V", false));
        m.instructions.insert(pre);
        // redirect the per-line drawRect(IIIII) calls
        int rects = redirectStaticRect(m, HOOKS_CHAT, "rect");
        System.out.println("[S1mp1e/ASM] patched GuiNewChat.drawChat (rect redirects: " + rects + ")");
        auditOk("GuiNewChat.drawChat (panel + rect=" + rects + ")");
        return write(cn);
    }

    // -----------------------------------------------------------------------
    // 9) GuiChat.drawScreen -> input bar (G1). Redirect ONLY the first drawRect
    //    (the input-field background) to GlassChatHud.inputRect; the rest of the
    //    screen (super.drawScreen, tab-complete popup) is untouched.
    // -----------------------------------------------------------------------
    private static byte[] patchChatInput(byte[] basic) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, DRAW_SCREEN_MCP, DRAW_SCREEN_SRG, DRAW_SCREEN_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] GuiChat.drawScreen not found, skipping");
            auditFail("GuiChat.drawScreen", "method not found");
            return basic;
        }
        int done = 0;
        Iterator<AbstractInsnNode> it = m.instructions.iterator();
        while (it.hasNext()) {
            AbstractInsnNode insn = it.next();
            if (insn.getOpcode() != Opcodes.INVOKESTATIC) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            boolean isRect = (RECT_MCP.equals(call.name) || RECT_SRG.equals(call.name))
                             && RECT_DESC.equals(call.desc);
            if (!isRect) continue;
            m.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                    HOOKS_CHAT, "inputRect", RECT_DESC, false));
            done++;
            break;   // only the input-field background (the first drawRect)
        }
        if (done == 0) {
            System.out.println("[S1mp1e/ASM] GuiChat input drawRect not found");
            auditFail("GuiChat.drawScreen", "input drawRect not found");
            return basic;
        }
        System.out.println("[S1mp1e/ASM] patched GuiChat.drawScreen (input bar)");
        auditOk("GuiChat.drawScreen (input bar)");
        return write(cn);
    }

    // -----------------------------------------------------------------------
    // 10) GuiPlayerTabOverlay.renderPlayerlist -> tab list (G2). Redirect every
    //     drawRect to GlassTabList.rect (tall header/list/footer -> glass plates;
    //     short row stripe -> thinned scrim).
    // -----------------------------------------------------------------------
    private static byte[] patchTabList(byte[] basic) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, TAB_MCP, TAB_SRG, TAB_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] GuiPlayerTabOverlay.renderPlayerlist not found, skipping");
            auditFail("GuiPlayerTabOverlay.renderPlayerlist", "method not found");
            return basic;
        }
        int rects = redirectStaticRect(m, HOOKS_TABLIST, "rect");
        if (rects == 0) {
            auditFail("GuiPlayerTabOverlay.renderPlayerlist", "no drawRect calls found");
            return basic;
        }
        System.out.println("[S1mp1e/ASM] patched GuiPlayerTabOverlay.renderPlayerlist (rect redirects: " + rects + ")");
        auditOk("GuiPlayerTabOverlay.renderPlayerlist (rect=" + rects + ")");
        return write(cn);
    }

    // -----------------------------------------------------------------------
    // 11) GuiIngameForge -> boss bar (G3) + action bar / overlay message (G5).
    // -----------------------------------------------------------------------
    private static byte[] patchIngameForge(byte[] basic) {
        ClassNode cn = read(basic);
        int done = 0;

        // renderBossHealth()V: head-splice `if (GlassBossBar.draw()) return;` so a boss
        // bar is fully replaced by the blue capsule fill + concentric glass capsule.
        MethodNode boss = find(cn, BOSS_MCP, BOSS_SRG, BOSS_DESC);
        if (boss != null) {
            LabelNode pass = new LabelNode();
            InsnList pre = new InsnList();
            pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_BOSSBAR, "draw", "()Z", false));
            pre.add(new JumpInsnNode(Opcodes.IFEQ, pass));
            pre.add(new InsnNode(Opcodes.RETURN));
            pre.add(pass);
            boss.instructions.insert(pre);
            done++;
            auditOk("GuiIngameForge.renderBossHealth (capsule)");
            System.out.println("[S1mp1e/ASM] patched GuiIngameForge.renderBossHealth");
        } else {
            System.out.println("[S1mp1e/ASM] GuiIngameForge.renderBossHealth not found, skipping");
            auditFail("GuiIngameForge.renderBossHealth", "method not found");
        }

        // renderRecordOverlay(int,int,float)V: redirect the FontRenderer.drawString
        // to GlassActionBar.draw (glass pill behind the fading overlay-message text).
        MethodNode rec = find(cn, REC_NAME, REC_NAME, REC_DESC);
        if (rec != null) {
            int hits = 0;
            Iterator<AbstractInsnNode> it = rec.instructions.iterator();
            while (it.hasNext()) {
                AbstractInsnNode insn = it.next();
                if (insn.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                MethodInsnNode call = (MethodInsnNode) insn;
                if (!FONT_RENDERER.equals(call.owner)) continue;
                boolean isDraw = (FDRAW_MCP.equals(call.name) || FDRAW_SRG.equals(call.name))
                                 && FDRAW_DESC.equals(call.desc);
                if (!isDraw) continue;
                rec.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        HOOKS_ACTIONBAR, "draw",
                        "(Lnet/minecraft/client/gui/FontRenderer;Ljava/lang/String;III)I", false));
                hits++;
            }
            if (hits > 0) {
                done++;
                auditOk("GuiIngameForge.renderRecordOverlay (pill=" + hits + ")");
                System.out.println("[S1mp1e/ASM] patched GuiIngameForge.renderRecordOverlay (drawString redirects: " + hits + ")");
            } else {
                auditFail("GuiIngameForge.renderRecordOverlay", "drawString call not found");
            }
        } else {
            System.out.println("[S1mp1e/ASM] GuiIngameForge.renderRecordOverlay not found, skipping");
            auditFail("GuiIngameForge.renderRecordOverlay", "method not found");
        }

        if (done == 0) return basic;
        return write(cn);
    }

    // -----------------------------------------------------------------------
    // 12) Render.renderLivingLabel -> name tags (G6). Redirect the WorldRenderer.color
    //     (float) plate vertices to GlassNameTag.color (frosted charcoal, same alpha).
    //     World space -> no refraction, vanilla visibility unchanged (fair-play).
    // -----------------------------------------------------------------------
    private static byte[] patchNameTag(byte[] basic) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, LABEL_MCP, LABEL_SRG, LABEL_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] Render.renderLivingLabel not found, skipping");
            auditFail("Render.renderLivingLabel", "method not found");
            return basic;
        }
        int hits = 0;
        Iterator<AbstractInsnNode> it = m.instructions.iterator();
        while (it.hasNext()) {
            AbstractInsnNode insn = it.next();
            if (insn.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            if (!WORLD_RENDERER.equals(call.owner)) continue;
            boolean isColor = (WRCOLOR_MCP.equals(call.name) || WRCOLOR_SRG.equals(call.name))
                              && WRCOLOR_DESC.equals(call.desc);
            if (!isColor) continue;
            m.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_NAMETAG, "color",
                    "(Lnet/minecraft/client/renderer/WorldRenderer;FFFF)Lnet/minecraft/client/renderer/WorldRenderer;",
                    false));
            hits++;
        }
        if (hits == 0) {
            System.out.println("[S1mp1e/ASM] Render.renderLivingLabel plate color() not found");
            auditFail("Render.renderLivingLabel", "plate color() not found");
            return basic;
        }
        System.out.println("[S1mp1e/ASM] patched Render.renderLivingLabel (plate color redirects: " + hits + ")");
        auditOk("Render.renderLivingLabel (plate=" + hits + ")");
        return write(cn);
    }

    // ---- helpers ----------------------------------------------------------

    /** Redirect every {@code INVOKESTATIC drawRect(IIIII)V} in {@code m} to
     *  {@code owner.hook(IIIII)V}; returns the number rewritten. */
    private static int redirectStaticRect(MethodNode m, String owner, String hook) {
        int n = 0;
        Iterator<AbstractInsnNode> it = m.instructions.iterator();
        while (it.hasNext()) {
            AbstractInsnNode insn = it.next();
            if (insn.getOpcode() != Opcodes.INVOKESTATIC) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            boolean isRect = (RECT_MCP.equals(call.name) || RECT_SRG.equals(call.name))
                             && RECT_DESC.equals(call.desc);
            if (!isRect) continue;
            m.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, owner, hook, RECT_DESC, false));
            n++;
        }
        return n;
    }

    private static MethodNode find(ClassNode cn, String mcp, String srg, String desc) {
        for (MethodNode m : cn.methods) {
            if (!desc.equals(m.desc)) continue;
            if (mcp.equals(m.name) || srg.equals(m.name)) return m;
        }
        return null;
    }

    private static ClassNode read(byte[] basic) {
        ClassNode cn = new ClassNode();
        new ClassReader(basic).accept(cn, 0);
        return cn;
    }

    private static byte[] write(ClassNode cn) {
        // COMPUTE_MAXS only: our splices don't change frames in ways that need
        // COMPUTE_FRAMES, and COMPUTE_FRAMES would make ASM load MC classes
        // mid-transform, which deadlocks LaunchClassLoader.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }
}
