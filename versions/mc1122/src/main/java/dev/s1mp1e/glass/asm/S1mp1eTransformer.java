package dev.s1mp1e.glass.asm;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
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
 *       {@code INVOKEVIRTUAL drawGuiContainerBackgroundLayer} is retargeted to
 *       {@link ContainerHook#background}. The receiver is already first on the
 *       stack, so swapping the opcode for an INVOKESTATIC whose first parameter
 *       is the screen is a drop-in substitution. Everything drawScreen does
 *       afterwards — slots, items, tooltips — is untouched.</li>
 * </ol>
 *
 * <p>Both are name-tolerant: the MCP names are used in a dev workspace and the
 * 1.8.9 SRG names in production, and each patch is skipped (loudly) rather than
 * throwing if its target isn't found, so a mismatch degrades to "no glass"
 * instead of a crash on startup.
 */
public final class S1mp1eTransformer implements IClassTransformer {

    // ---- targets ----------------------------------------------------------

    private static final String GUI_BUTTON    = "net.minecraft.client.gui.GuiButton";
    private static final String GUI_CONTAINER = "net.minecraft.client.gui.inventory.GuiContainer";

    // drawButton(Minecraft,int,int,float)V — 1.12.2 adds partialTicks (SRG func_191745_a)
    private static final String DRAW_BUTTON_MCP = "drawButton";
    private static final String DRAW_BUTTON_SRG = "func_191745_a";
    private static final String DRAW_BUTTON_DESC = "(Lnet/minecraft/client/Minecraft;IIF)V";

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

    // drawHoveringText(List,int,int,FontRenderer)V — every tooltip funnels here.
    // Forge-added overload, so it keeps its name in production (not SRG-renamed).
    private static final String TIP_NAME = "drawHoveringText";
    private static final String TIP_DESC =
        "(Ljava/util/List;IILnet/minecraft/client/gui/FontRenderer;)V";

    // renderSkybox(int,int,float)V — the title screen's panorama pass
    private static final String SKY_MCP  = "renderSkybox";
    private static final String SKY_SRG  = "func_73972_b";
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

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return null;
        try {
            if (GUI_BUTTON.equals(transformedName)) {
                return patchButton(basicClass);
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
        pre.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

        m.instructions.insert(pre);
        System.out.println("[S1mp1e/ASM] patched GuiButton.drawButton");
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

            // stack is already [GuiContainer, float, int, int] — exactly the
            // static hook's parameter list, so only the opcode/owner/desc change.
            m.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                    HOOKS_CONTAINER, "background",
                    "(Lnet/minecraft/client/gui/inventory/GuiContainer;FII)V", false));
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
        MethodNode slotM = find(cn, SLOT_MCP, SLOT_SRG, SLOT_DESC);
        if (slotM != null) {
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

        if (patched == 0) {
            System.out.println("[S1mp1e/ASM] background-layer call not found in drawScreen");
            return basic;
        }
        System.out.println("[S1mp1e/ASM] patched GuiContainer.drawScreen (hover redirects: " + hover + ")");
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
            return basic;
        }
        // if (BlitSuppressor.consume()) return;
        LabelNode pass = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_SUPPRESS, "consume", "()Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, pass));
        pre.add(new InsnNode(Opcodes.RETURN));
        pre.add(pass);
        pre.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        m.instructions.insert(pre);
        System.out.println("[S1mp1e/ASM] patched Gui.drawTexturedModalRect");
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
        pre.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
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
            tpre.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            tip.instructions.insert(tpre);
            System.out.println("[S1mp1e/ASM] patched GuiScreen.drawHoveringText");
        } else {
            System.out.println("[S1mp1e/ASM] drawHoveringText not found, tooltips stay vanilla");
        }

        System.out.println("[S1mp1e/ASM] patched GuiScreen.drawBackground");
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
            return basic;
        }
        System.out.println("[S1mp1e/ASM] patched GuiMainMenu.drawScreen (panorama capture)");
        return write(cn);
    }

    // ---- helpers ----------------------------------------------------------

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
        // COMPUTE_MAXS + hand-authored F_SAME frames: every head-splice branches
        // to the ORIGINAL method entry, so a single same-frame at that target is
        // exact. 1.12.2 classes are V1.8 (strict verifier) and REQUIRE it;
        // COMPUTE_FRAMES would force ASM to load MC classes and deadlock LCL.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }
}
