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
 *       {@code INVOKEVIRTUAL drawGuiContainerBackgroundLayer} is left in place
 *       and merely bracketed with {@link ContainerHook#arm} /
 *       {@link ContainerHook#disarm}, so vanilla's virtual dispatch runs
 *       untouched while {@link BlitSuppressor} drops only the opaque panel
 *       strips (matched by geometry at the head of
 *       {@code Gui.drawTexturedModalRect}). Keeping the original INVOKE also
 *       keeps any Mixin-based mod that targets that call (ItemScroller/MaLiLib,
 *       EntityCulling, ...) working. Everything drawScreen does afterwards —
 *       slots, items, tooltips — is untouched.</li>
 * </ol>
 *
 * <p>All patches are name-tolerant: the MCP names are used in a dev workspace
 * and the 1.12.2 SRG names in production (FML's deobf transformer renames the
 * notch classes to SRG before ours runs), and each patch is skipped (loudly)
 * rather than throwing if its target isn't found, so a mismatch degrades to "no
 * glass" instead of a crash on startup. Every patch that adds a branch
 * hand-inserts an {@code F_SAME} stack-map frame (v52 classes require it).
 *
 * <p>The hook descriptors emitted here MUST match the hook signatures in the
 * same build ({@code BlitSuppressor.consume(IIIIII)Z},
 * {@code ContainerHook.arm(GuiContainer)V}, {@code ContainerHook.disarm()V}).
 * javac cannot see a mismatch; at runtime it is a NoSuchMethodError inside
 * every GUI blit.
 */
public final class S1mp1eTransformer implements IClassTransformer {

    // ---- targets ----------------------------------------------------------

    private static final String GUI_BUTTON    = "net.minecraft.client.gui.GuiButton";
    private static final String GUI_BUTTON_EXT = "net.minecraftforge.fml.client.config.GuiButtonExt";
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

    // Feature (F) — InventoryEffectRenderer.drawActivePotionEffects()V. Private,
    // called by drawScreen AFTER super.drawScreen() (i.e. after the tooltip). The
    // glass strip + icons are drawn earlier (BackgroundDrawnEvent) so the tooltip
    // stays on top (R1); a head early-return then cancels the vanilla drawer when
    // the strip already drew this frame.
    private static final String EFF_RENDERER = "net.minecraft.client.renderer.InventoryEffectRenderer";
    private static final String EFF_DRAW_MCP  = "drawActivePotionEffects";
    private static final String EFF_DRAW_SRG  = "func_147044_g";
    private static final String EFF_DRAW_DESC = "()V";
    private static final String HOOKS_EFFECT  = "dev/s1mp1e/glass/asm/EffectStripHook";
    private static final String EFFECT_HANDLED_DESC =
            "(Lnet/minecraft/client/renderer/InventoryEffectRenderer;)Z";

    // Feature (C) — GuiContainerCreative.drawGuiContainerBackgroundLayer draws the
    // scrollbar thumb with one this.drawTexturedModalRect(i, y, u, 0, 12, 15). It is
    // the only 12x15 blit in that method (the body is 195x136, already suppressed;
    // the tabs are 28x32 inside drawTab), so it is picked out by its w=12,h=15
    // constants and swapped for the glass scrollbar. The receiver `this` is already
    // on the stack, so INVOKEVIRTUAL -> INVOKESTATIC(GuiContainerCreative,IIIIII)V
    // is stack-neutral and needs no frame.
    private static final String GUI_CONTAINER_CREATIVE =
            "net.minecraft.client.gui.inventory.GuiContainerCreative";
    private static final String CREATIVE_BG_MCP  = "drawGuiContainerBackgroundLayer";
    private static final String CREATIVE_BG_SRG  = "func_146976_a";
    private static final String CREATIVE_BG_DESC = "(FII)V";
    private static final String HOOKS_CREATIVE   = "dev/s1mp1e/glass/asm/CreativeHook";
    private static final String CREATIVE_SCROLL_DESC =
            "(Lnet/minecraft/client/gui/inventory/GuiContainerCreative;IIIIII)V";

    // Feature (D) — the creative item grid glides sub-pixel. GuiContainer.drawSlot(Slot) gets a head
    // splice `if (CreativeGlideHook.handleSlot(this, slot)) return;` so, while gliding, vanilla's own
    // draw of a grid slot is suppressed and the moving overlay is drawn instead (shared drawSlot, so
    // the hook no-ops for every non-creative / not-gliding container). GuiContainerCreative.mouseClicked
    // gets a head splice `CreativeGlideHook.snapOnClick(this)` so a mid-glide click first snaps the grid
    // to the target row and then acts on the item drawn under the cursor.
    private static final String HOOKS_GLIDE = "dev/s1mp1e/glass/hook/CreativeGlideHook";
    private static final String GLIDE_HANDLE_DESC =
            "(Lnet/minecraft/client/gui/inventory/GuiContainer;Lnet/minecraft/inventory/Slot;)Z";
    private static final String GLIDE_SNAP_DESC =
            "(Lnet/minecraft/client/gui/inventory/GuiContainerCreative;)V";
    private static final String CREATIVE_CLICK_MCP  = "mouseClicked";
    private static final String CREATIVE_CLICK_SRG  = "func_73864_a";
    private static final String CREATIVE_CLICK_DESC = "(III)V";

    // Feature (A) — GuiScreenAdvancements: glass panel under the tree (spliced at
    // renderInside head) + drop the wooden WINDOW frame (swap the blit in
    // renderWindow). All-or-nothing so a partial patch never leaves a frame over
    // a glass panel; degrades to the vanilla window otherwise.
    private static final String GUI_ADVANCEMENTS  = "net.minecraft.client.gui.advancements.GuiScreenAdvancements";
    private static final String ADV_INSIDE_MCP    = "renderInside";
    private static final String ADV_INSIDE_SRG    = "func_191936_c";
    private static final String ADV_INSIDE_DESC   = "(IIII)V";
    private static final String ADV_WINDOW_MCP    = "renderWindow";
    private static final String ADV_WINDOW_SRG    = "func_191934_b";
    private static final String ADV_WINDOW_DESC   = "(II)V";
    private static final String HOOKS_ADV         = "dev/s1mp1e/glass/asm/AdvancementsHook";
    private static final String ADV_PANEL_DESC =
            "(Lnet/minecraft/client/gui/advancements/GuiScreenAdvancements;II)V";
    private static final String ADV_FRAME_DESC =
            "(Lnet/minecraft/client/gui/advancements/GuiScreenAdvancements;IIIIII)V";
    private static final String MAIN_MENU  = "net.minecraft.client.gui.GuiMainMenu";
    private static final String GUI_SLOT   = "net.minecraft.client.gui.GuiSlot";

    // Recipe book (V-4) — 1.12.2 has GuiRecipeBook and three button classes the
    // reference glasses with four mixins. Each patch is a single opcode swap of
    // the INVOKEVIRTUAL drawTexturedModalRect blit for an INVOKESTATIC into the
    // matching RecipeBookHook method (the receiver `this` is already first on the
    // stack, so the stack shape is unchanged; no branch, so no stack-map frame).
    private static final String GUI_RECIPE_BOOK  = "net.minecraft.client.gui.recipebook.GuiRecipeBook";
    private static final String GUI_BTN_REC_TAB  = "net.minecraft.client.gui.recipebook.GuiButtonRecipeTab";
    private static final String GUI_BTN_RECIPE   = "net.minecraft.client.gui.recipebook.GuiButtonRecipe";
    private static final String GUI_BTN_TOGGLE   = "net.minecraft.client.gui.GuiButtonToggle";

    // GuiRecipeBook.render(int,int,float)V — SRG verified in mcp-srg.srg.
    private static final String RB_RENDER_MCP  = "render";
    private static final String RB_RENDER_SRG  = "func_191861_a";
    private static final String RB_RENDER_DESC = "(IIF)V";
    // The three recipe buttons all override drawButton(Minecraft,int,int,float)V
    // (DRAW_BUTTON_MCP/SRG/DESC), so they reuse those method-name constants.

    // GuiSlot.drawContainerBackground(Tessellator)V — Forge-added (it is absent
    // from the 1.12.2 mcp-srg data), so it keeps its MCP name in production.
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

    // renderSkybox(int,int,float)V — the title screen's panorama pass.
    // SRG verified in the 1.12.2 mcp-srg.srg. (The old literal here was the SRG
    // name of addDemoButtons(II)V, so the panorama capture only matched in dev.)
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
    private static final String HOOKS_RECIPE    = "dev/s1mp1e/glass/asm/RecipeBookHook";

    // Hook descriptors — must equal the Java signatures of the hook methods.
    private static final String SUPPRESS_CONSUME_DESC = "(IIIIII)Z";
    private static final String CONTAINER_ARM_DESC =
            "(Lnet/minecraft/client/gui/inventory/GuiContainer;)V";
    private static final String CONTAINER_DISARM_DESC = "()V";

    // Recipe-book hook descriptors — first param is the blit's receiver `this`.
    private static final String RB_PANEL_DESC =
            "(Lnet/minecraft/client/gui/recipebook/GuiRecipeBook;IIIIII)V";
    private static final String RB_TAB_DESC =
            "(Lnet/minecraft/client/gui/recipebook/GuiButtonRecipeTab;IIIIII)V";
    private static final String RB_CELL_DESC =
            "(Lnet/minecraft/client/gui/recipebook/GuiButtonRecipe;IIIIII)V";
    private static final String RB_TOGGLE_DESC =
            "(Lnet/minecraft/client/gui/GuiButtonToggle;IIIIII)V";

    // ---- BATCH B (G) HUD-overlay targets ----------------------------------

    // G1 — GuiNewChat.drawChat(int)V: head-splice the glass panel, redirect the per-line drawRect.
    private static final String GUI_NEW_CHAT = "net.minecraft.client.gui.GuiNewChat";
    private static final String CHAT_MCP  = "drawChat";
    private static final String CHAT_SRG  = "func_146230_a";
    private static final String CHAT_DESC = "(I)V";
    private static final String HOOKS_CHAT = "dev/s1mp1e/glass/hook/GlassChatHud";
    private static final String CHAT_BEGIN_DESC = "(Lnet/minecraft/client/gui/GuiNewChat;I)V";
    // G1 — GuiChat.drawScreen(int,int,float)V: redirect the input-bar drawRect (reuses DRAW_SCREEN_*).
    private static final String GUI_CHAT = "net.minecraft.client.gui.GuiChat";

    // G2 — GuiPlayerTabOverlay.renderPlayerlist(int,Scoreboard,ScoreObjective)V: redirect the bg drawRects.
    private static final String TAB_OVERLAY = "net.minecraft.client.gui.GuiPlayerTabOverlay";
    private static final String TAB_MCP  = "renderPlayerlist";
    private static final String TAB_SRG  = "func_175249_a";
    private static final String TAB_DESC = "(ILnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/ScoreObjective;)V";
    private static final String HOOKS_TABLIST = "dev/s1mp1e/glass/hook/GlassTabList";

    // G5 — GuiIngameForge.renderRecordOverlay(int,int,float)V: redirect the overlay-message drawString.
    // GuiIngameForge is a Forge class (not SRG-renamed), so the method name is stable in production.
    private static final String INGAME_FORGE = "net.minecraftforge.client.GuiIngameForge";
    private static final String REC_NAME = "renderRecordOverlay";
    private static final String REC_DESC = "(IIF)V";
    private static final String FDRAW_MCP  = "drawString";
    private static final String FDRAW_SRG  = "func_78276_b";
    private static final String FDRAW_DESC = "(Ljava/lang/String;III)I";
    private static final String HOOKS_ACTIONBAR = "dev/s1mp1e/glass/hook/GlassActionBar";
    private static final String ACTIONBAR_DRAW_DESC = "(Lnet/minecraft/client/gui/FontRenderer;Ljava/lang/String;III)I";

    // G6 — EntityRenderer.drawNameplate(FontRenderer,String,F,F,F,I,F,F,Z,Z)V: redirect the plate's
    // four BufferBuilder.color calls to the frosted-charcoal restyle.
    private static final String ENTITY_RENDERER = "net.minecraft.client.renderer.EntityRenderer";
    private static final String NAMEPLATE_MCP  = "drawNameplate";
    private static final String NAMEPLATE_SRG  = "func_189692_a";
    private static final String NAMEPLATE_DESC =
            "(Lnet/minecraft/client/gui/FontRenderer;Ljava/lang/String;FFFIFFZZ)V";
    private static final String WRCOLOR_MCP  = "color";
    private static final String WRCOLOR_SRG  = "func_181666_a";
    private static final String WRCOLOR_DESC = "(FFFF)Lnet/minecraft/client/renderer/BufferBuilder;";
    private static final String HOOKS_NAMETAG = "dev/s1mp1e/glass/hook/GlassNameTag";
    private static final String NAMETAG_COLOR_DESC =
            "(Lnet/minecraft/client/renderer/BufferBuilder;FFFF)Lnet/minecraft/client/renderer/BufferBuilder;";

    // G4 — the four concrete toasts. Each overrides draw(GuiToast,long)IToast$Visibility (SRG
    // func_193653_a) and draws its frame with the FIRST toastGui.drawTexturedModalRect; swap that blit
    // for the glass card (the receiver GuiToast becomes the hook's first parameter).
    private static final String TOAST_ADV = "net.minecraft.client.gui.toasts.AdvancementToast";
    private static final String TOAST_REC = "net.minecraft.client.gui.toasts.RecipeToast";
    private static final String TOAST_SYS = "net.minecraft.client.gui.toasts.SystemToast";
    private static final String TOAST_TUT = "net.minecraft.client.gui.toasts.TutorialToast";
    private static final String TOAST_DRAW_MCP = "draw";
    private static final String TOAST_DRAW_SRG = "func_193653_a";
    private static final String TOAST_DRAW_DESC =
            "(Lnet/minecraft/client/gui/toasts/GuiToast;J)Lnet/minecraft/client/gui/toasts/IToast$Visibility;";
    private static final String HOOKS_TOAST = "dev/s1mp1e/glass/hook/GlassToast";
    private static final String TOAST_CARD_DESC = "(Lnet/minecraft/client/gui/toasts/GuiToast;IIIIII)V";

    // ---- per-patch success flags (read by the DevShot coremod audit) -------
    //
    // These live on the transformer, never on a hook class, so the transform
    // path class-loads nothing new — the same hazard the camera/combat
    // transformers document. Each flag is set true only once its patch has
    // matched and the rewritten bytes were produced. A false flag after the
    // audit's force-load means that site never applied (a failed patch).

    /** GuiButton.drawButton early-return splice matched. */
    public static volatile boolean buttonPatched = false;
    /** GuiButtonExt.drawButton early-return splice matched. */
    public static volatile boolean buttonExtPatched = false;
    /** GuiContainer.drawScreen background-layer bracket matched. */
    public static volatile boolean containerPatched = false;
    /** Gui.drawTexturedModalRect panel suppressor spliced. */
    public static volatile boolean guiBlitPatched = false;
    /** GuiScreen.drawBackground blurred-backdrop splice matched. */
    public static volatile boolean screenBgPatched = false;
    /** GuiScreen.drawHoveringText glass-tooltip splice matched. */
    public static volatile boolean tooltipPatched = false;
    /** GuiMainMenu.drawScreen panorama capture spliced. */
    public static volatile boolean mainMenuPatched = false;
    /** GuiSlot list-background splice matched (at least one of the two sites). */
    public static volatile boolean guiSlotPatched = false;
    /** GuiRecipeBook.render panel-blit swap matched. */
    public static volatile boolean recipePanelPatched = false;
    /** GuiButtonRecipeTab.drawButton tab-blit swap matched. */
    public static volatile boolean recipeTabPatched = false;
    /** GuiButtonRecipe.drawButton cell-blit swap matched. */
    public static volatile boolean recipeCellPatched = false;
    /** GuiButtonToggle.drawButton toggle-blit swap matched. */
    public static volatile boolean recipeTogglePatched = false;
    /** InventoryEffectRenderer.drawActivePotionEffects early-return splice matched. */
    public static volatile boolean effectStripPatched = false;
    /** GuiContainerCreative scrollbar-thumb blit swap matched. */
    public static volatile boolean creativeScrollPatched = false;
    /** GuiContainer.drawSlot glide-suppress head splice matched (feature D). */
    public static volatile boolean creativeGlidePatched = false;
    /** GuiContainerCreative.mouseClicked snap-on-click head splice matched (feature D). */
    public static volatile boolean creativeClickPatched = false;
    /** GuiScreenAdvancements panel-splice + frame-swap matched (both). */
    public static volatile boolean advancementsPatched = false;
    /** GuiNewChat.drawChat panel head-splice + per-line drawRect redirect matched (G1). */
    public static volatile boolean chatPatched = false;
    /** GuiChat.drawScreen input-bar drawRect redirect matched (G1). */
    public static volatile boolean chatInputPatched = false;
    /** GuiPlayerTabOverlay.renderPlayerlist background drawRect redirect matched (G2). */
    public static volatile boolean tabListPatched = false;
    /** GuiIngameForge.renderRecordOverlay drawString redirect matched (G5). */
    public static volatile boolean actionBarPatched = false;
    /** EntityRenderer.drawNameplate BufferBuilder.color redirect matched (G6). */
    public static volatile boolean nameTagPatched = false;
    /** At least one concrete toast's frame blit was swapped for the glass card (G4). */
    public static volatile boolean toastPatched = false;
    /** How many of the four concrete toasts were patched (G4). */
    public static volatile int toastCount = 0;

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
            if (EFF_RENDERER.equals(transformedName)) {
                return patchEffectStrip(basicClass);
            }
            if (GUI_CONTAINER_CREATIVE.equals(transformedName)) {
                return patchCreative(basicClass);
            }
            if (GUI_ADVANCEMENTS.equals(transformedName)) {
                return patchAdvancements(basicClass);
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
            if (GUI_RECIPE_BOOK.equals(transformedName)) {
                return swapBlit(basicClass, RB_RENDER_MCP, RB_RENDER_SRG, RB_RENDER_DESC,
                        "panel", RB_PANEL_DESC, "GuiRecipeBook.render");
            }
            if (GUI_BTN_REC_TAB.equals(transformedName)) {
                return swapBlit(basicClass, DRAW_BUTTON_MCP, DRAW_BUTTON_SRG, DRAW_BUTTON_DESC,
                        "tab", RB_TAB_DESC, "GuiButtonRecipeTab.drawButton");
            }
            if (GUI_BTN_RECIPE.equals(transformedName)) {
                return swapBlit(basicClass, DRAW_BUTTON_MCP, DRAW_BUTTON_SRG, DRAW_BUTTON_DESC,
                        "cell", RB_CELL_DESC, "GuiButtonRecipe.drawButton");
            }
            if (GUI_BTN_TOGGLE.equals(transformedName)) {
                return swapBlit(basicClass, DRAW_BUTTON_MCP, DRAW_BUTTON_SRG, DRAW_BUTTON_DESC,
                        "toggle", RB_TOGGLE_DESC, "GuiButtonToggle.drawButton");
            }
            // BATCH B (G) HUD overlays
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
                return patchActionBar(basicClass);
            }
            if (ENTITY_RENDERER.equals(transformedName)) {
                return patchNameTag(basicClass);
            }
            if (TOAST_ADV.equals(transformedName)) {
                return patchToast(basicClass, "AdvancementToast");
            }
            if (TOAST_REC.equals(transformedName)) {
                return patchToast(basicClass, "RecipeToast");
            }
            if (TOAST_SYS.equals(transformedName)) {
                return patchToast(basicClass, "SystemToast");
            }
            if (TOAST_TUT.equals(transformedName)) {
                return patchToast(basicClass, "TutorialToast");
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
        buttonPatched = true;
        System.out.println("[S1mp1e/ASM] patched GuiButton.drawButton");
        return write(cn);
    }

    // -----------------------------------------------------------------------
    // 1b) GuiButtonExt.drawButton -> the same early-return splice, so FML config
    //     GUI buttons (the Mods list, every mod config screen) become capsules
    //     too. GuiButtonExt OVERRIDES drawButton, so the GuiButton splice never
    //     runs for it and it needs its own.
    //
    //     Name lookup: in a dev workspace the Forge class carries the MCP name
    //     ("drawButton"); in production the universal jar is notch-obfuscated
    //     (GuiButtonExt.a(bib,int,int,float)) and FML's deobf transformer
    //     (sorting index 1000, ahead of ours at 1001) merges the vanilla super
    //     map and renames it to the SRG name func_191745_a. Matching only the
    //     MCP name — as the 1.8.9 line does — silently skips in production, so
    //     we accept EITHER name with the (Minecraft,int,int,float)V descriptor.
    // -----------------------------------------------------------------------
    private static byte[] patchButtonExt(byte[] basic) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, DRAW_BUTTON_MCP, DRAW_BUTTON_SRG, DRAW_BUTTON_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] GuiButtonExt.drawButton not found, skipping");
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
        // v52 classes are verified with stack maps: the branch target needs one.
        pre.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        m.instructions.insert(pre);
        buttonExtPatched = true;
        System.out.println("[S1mp1e/ASM] patched GuiButtonExt.drawButton");
        return write(cn);
    }

    // -----------------------------------------------------------------------
    // 2) GuiContainer.drawScreen -> bracket the background-layer call
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

            // Leave the virtual dispatch untouched: bracket it with arm/disarm so
            // BlitSuppressor drops only the panel strips while the vanilla layer
            // (player model, furnace fire, slot icons) runs normally.
            //   ... ALOAD 0; INVOKESTATIC ContainerHook.arm(GuiContainer)V   <- before
            //   INVOKEVIRTUAL drawGuiContainerBackgroundLayer                 (unchanged)
            //   INVOKESTATIC ContainerHook.disarm()V                          <- after
            // arm() pushes and consumes its own operand, so the call's own
            // [GuiContainer, float, int, int] stack is left exactly as it was.
            // Stack-neutral and branch-free -> no stack-map frame is needed.
            InsnList before = new InsnList();
            before.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this (GuiContainer)
            before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_CONTAINER, "arm",
                    CONTAINER_ARM_DESC, false));
            m.instructions.insertBefore(call, before);
            m.instructions.insert(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                    HOOKS_CONTAINER, "disarm", CONTAINER_DISARM_DESC, false));
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
            // Feature (D) head splice: if (CreativeGlideHook.handleSlot(this, slot)) return; — while the
            // creative grid is mid-glide, drop vanilla's draw of each grid slot (the hook draws the moving
            // overlay once, on the anchor slot). The hook no-ops for every other container, so this shared
            // GuiContainer.drawSlot patch never affects them.
            LabelNode gpass = new LabelNode();
            InsnList gpre = new InsnList();
            gpre.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this (GuiContainer)
            gpre.add(new VarInsnNode(Opcodes.ALOAD, 1)); // Slot
            gpre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_GLIDE, "handleSlot", GLIDE_HANDLE_DESC, false));
            gpre.add(new JumpInsnNode(Opcodes.IFEQ, gpass));
            gpre.add(new InsnNode(Opcodes.RETURN));
            gpre.add(gpass);
            gpre.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            slotM.instructions.insert(gpre);
            creativeGlidePatched = true;

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
        containerPatched = true;
        System.out.println("[S1mp1e/ASM] patched GuiContainer.drawScreen (hover redirects: " + hover + ")");
        return write(cn);
    }

    // -----------------------------------------------------------------------
    // 2b) InventoryEffectRenderer.drawActivePotionEffects -> early-return when the
    //     glass effect strip already drew this frame (feature F). The strip + icons
    //     are drawn at BackgroundDrawnEvent (before the tooltip) so the tooltip
    //     stays on top (R1); cancelling here stops vanilla redrawing the boxes over
    //     the tooltip. A single head splice, one F_SAME frame at the entry.
    // -----------------------------------------------------------------------
    private static byte[] patchEffectStrip(byte[] basic) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, EFF_DRAW_MCP, EFF_DRAW_SRG, EFF_DRAW_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] InventoryEffectRenderer.drawActivePotionEffects not found, skipping");
            return basic;
        }
        // if (EffectStripHook.handled(this)) return;
        LabelNode pass = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));   // this (InventoryEffectRenderer)
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_EFFECT, "handled",
                EFFECT_HANDLED_DESC, false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, pass));
        pre.add(new InsnNode(Opcodes.RETURN));
        pre.add(pass);
        pre.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        m.instructions.insert(pre);
        effectStripPatched = true;
        System.out.println("[S1mp1e/ASM] patched InventoryEffectRenderer.drawActivePotionEffects");
        return write(cn);
    }

    // -----------------------------------------------------------------------
    // 2c) GuiContainerCreative.drawGuiContainerBackgroundLayer -> swap ONLY the
    //     scrollbar-thumb blit (the 12x15 one) for the glass scrollbar (feature C).
    // -----------------------------------------------------------------------
    private static byte[] patchCreative(byte[] basic) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, CREATIVE_BG_MCP, CREATIVE_BG_SRG, CREATIVE_BG_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] GuiContainerCreative.drawGuiContainerBackgroundLayer not found, skipping");
            return basic;
        }
        int swapped = 0;
        Iterator<AbstractInsnNode> it = m.instructions.iterator();
        while (it.hasNext()) {
            AbstractInsnNode insn = it.next();
            if (insn.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            boolean isBlit = (BLIT_MCP.equals(call.name) || BLIT_SRG.equals(call.name))
                             && BLIT_DESC.equals(call.desc);
            if (!isBlit) continue;
            // The scrollbar blit's last two args are the constants w=12, h=15,
            // pushed immediately before the call; the body blit pushes xSize/ySize
            // via GETFIELD, so this uniquely selects the scrollbar.
            AbstractInsnNode pH = prevReal(call);
            AbstractInsnNode pW = pH == null ? null : prevReal(pH);
            if (intVal(pH) != 15 || intVal(pW) != 12) continue;
            m.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                    HOOKS_CREATIVE, "scrollbar", CREATIVE_SCROLL_DESC, false));
            swapped++;
            break;   // exactly one scrollbar blit
        }
        if (swapped == 0) {
            System.out.println("[S1mp1e/ASM] creative scrollbar blit (12x15) not found");
            return basic;
        }
        creativeScrollPatched = true;
        System.out.println("[S1mp1e/ASM] patched GuiContainerCreative scrollbar (glass)");

        // Feature (D) — snap the grid to the target row when clicked mid-glide, so the click acts on the
        // item drawn under the cursor. A plain head insert (push this, call a void static) — stack-neutral
        // at the entry and branch-free, so no stack-map frame is needed.
        MethodNode click = find(cn, CREATIVE_CLICK_MCP, CREATIVE_CLICK_SRG, CREATIVE_CLICK_DESC);
        if (click != null) {
            InsnList pre = new InsnList();
            pre.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this (GuiContainerCreative)
            pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_GLIDE, "snapOnClick", GLIDE_SNAP_DESC, false));
            click.instructions.insert(pre);
            creativeClickPatched = true;
            System.out.println("[S1mp1e/ASM] patched GuiContainerCreative.mouseClicked (glide snap)");
        } else {
            System.out.println("[S1mp1e/ASM] GuiContainerCreative.mouseClicked not found, glide-snap skipped");
        }
        return write(cn);
    }

    // -----------------------------------------------------------------------
    // 2d) GuiScreenAdvancements -> glass panel under the tree + drop wooden frame
    //     (feature A). All-or-nothing: both sites, or vanilla.
    // -----------------------------------------------------------------------
    private static byte[] patchAdvancements(byte[] basic) {
        ClassNode cn = read(basic);

        // renderInside HEAD: AdvancementsHook.panel(this, i, j)  (i=arg3, j=arg4)
        MethodNode ri = find(cn, ADV_INSIDE_MCP, ADV_INSIDE_SRG, ADV_INSIDE_DESC);
        boolean spliced = false;
        if (ri != null) {
            InsnList pre = new InsnList();
            pre.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
            pre.add(new VarInsnNode(Opcodes.ILOAD, 3)); // i (guiLeft)
            pre.add(new VarInsnNode(Opcodes.ILOAD, 4)); // j (guiTop)
            pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_ADV, "panel", ADV_PANEL_DESC, false));
            ri.instructions.insert(pre);
            spliced = true;
        }

        // renderWindow: swap the WINDOW frame blit (the only drawTexturedModalRect
        // there) -> AdvancementsHook.frame(this, i, j, u, v, w, h)
        MethodNode rw = find(cn, ADV_WINDOW_MCP, ADV_WINDOW_SRG, ADV_WINDOW_DESC);
        boolean swapped = false;
        if (rw != null) {
            Iterator<AbstractInsnNode> it = rw.instructions.iterator();
            while (it.hasNext()) {
                AbstractInsnNode insn = it.next();
                if (insn.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                MethodInsnNode call = (MethodInsnNode) insn;
                boolean isBlit = (BLIT_MCP.equals(call.name) || BLIT_SRG.equals(call.name))
                                 && BLIT_DESC.equals(call.desc);
                if (!isBlit) continue;
                rw.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        HOOKS_ADV, "frame", ADV_FRAME_DESC, false));
                swapped = true;
                break;
            }
        }

        if (!spliced || !swapped) {
            System.out.println("[S1mp1e/ASM] advancements patch incomplete (inside=" + spliced
                    + " window=" + swapped + "), keeping vanilla");
            return basic;   // discard cn -> full vanilla, no half-patched state
        }
        advancementsPatched = true;
        System.out.println("[S1mp1e/ASM] patched GuiScreenAdvancements (glass window)");
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
        // if (BlitSuppressor.consume(x, y, u, v, w, h)) return;
        // drawTexturedModalRect(int x, int y, int u, int v, int w, int h) —
        // params in local slots 1..6 (slot 0 is `this`). The suppressor matches
        // the panel strips by geometry, so creative tabs, the scrollbar, the
        // furnace fire/arrow and every other blit of the layer survive.
        LabelNode pass = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ILOAD, 1));
        pre.add(new VarInsnNode(Opcodes.ILOAD, 2));
        pre.add(new VarInsnNode(Opcodes.ILOAD, 3));
        pre.add(new VarInsnNode(Opcodes.ILOAD, 4));
        pre.add(new VarInsnNode(Opcodes.ILOAD, 5));
        pre.add(new VarInsnNode(Opcodes.ILOAD, 6));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_SUPPRESS, "consume",
                SUPPRESS_CONSUME_DESC, false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, pass));
        pre.add(new InsnNode(Opcodes.RETURN));
        pre.add(pass);
        pre.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        m.instructions.insert(pre);
        guiBlitPatched = true;
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
            tooltipPatched = true;
            System.out.println("[S1mp1e/ASM] patched GuiScreen.drawHoveringText");
        } else {
            System.out.println("[S1mp1e/ASM] drawHoveringText not found, tooltips stay vanilla");
        }

        screenBgPatched = true;
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
        mainMenuPatched = true;
        System.out.println("[S1mp1e/ASM] patched GuiMainMenu.drawScreen (panorama capture)");
        return write(cn);
    }

    // -----------------------------------------------------------------------
    // 6) GuiSlot list screens -> blurred backdrop instead of dirt
    //
    //    Both splices add a branch, so each needs its own FrameNode(F_SAME)
    //    after its pass label: 1.12.2 classes are v52 and the verifier rejects a
    //    branch target with no stack map (the 1.8.9 line's frame-less versions
    //    would throw VerifyError while loading GuiSlot).
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
            pre.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            bg.instructions.insert(pre);
            done++;
        } else {
            System.out.println("[S1mp1e/ASM] GuiSlot.drawContainerBackground not found, skipping");
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
            pre.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            ovl.instructions.insert(pre);
            done++;
        } else {
            System.out.println("[S1mp1e/ASM] GuiSlot.overlayBackground not found, skipping");
        }

        if (done == 0) return basic;
        guiSlotPatched = true;
        System.out.println("[S1mp1e/ASM] patched GuiSlot list background (sites: " + done + ")");
        return write(cn);
    }

    // -----------------------------------------------------------------------
    // 7) Recipe book (V-4) -> swap the single drawTexturedModalRect blit inside
    //    one method for an INVOKESTATIC into RecipeBookHook.
    //
    //    Each of the four recipe classes calls this.drawTexturedModalRect(...)
    //    exactly once inside the named method (verified against forgeSrc-1.12.2):
    //      GuiRecipeBook.render          -> RecipeBookHook.panel  (147x166 book)
    //      GuiButtonRecipeTab.drawButton -> RecipeBookHook.tab    (tab sprite)
    //      GuiButtonRecipe.drawButton    -> RecipeBookHook.cell   (result cell)
    //      GuiButtonToggle.drawButton    -> RecipeBookHook.toggle (filter/arrows)
    //    The receiver (`this`, aload_0) is already first on the stack and its
    //    verification type is the class being patched, so swapping INVOKEVIRTUAL
    //    for an INVOKESTATIC whose first parameter is that class keeps the stack
    //    shape identical. Both opcodes are 3 bytes, so no offsets move and every
    //    existing stack-map frame stays valid — no branch is added, so none is
    //    needed. Per-class isolation comes from transform()'s try/catch: a throw
    //    here logs and returns the class unmodified (vanilla recipe book).
    // -----------------------------------------------------------------------
    private static byte[] swapBlit(byte[] basic, String mcpMethod, String srgMethod,
                                   String methodDesc, String hookName, String hookDesc,
                                   String label) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, mcpMethod, srgMethod, methodDesc);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] " + label + " not found, skipping recipe glass");
            return basic;
        }
        int swapped = 0;
        Iterator<AbstractInsnNode> it = m.instructions.iterator();
        while (it.hasNext()) {
            AbstractInsnNode insn = it.next();
            if (insn.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            boolean isBlit = (BLIT_MCP.equals(call.name) || BLIT_SRG.equals(call.name))
                             && BLIT_DESC.equals(call.desc);
            if (!isBlit) continue;
            m.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                    HOOKS_RECIPE, hookName, hookDesc, false));
            swapped++;
            break;  // exactly one blit per method
        }
        if (swapped == 0) {
            System.out.println("[S1mp1e/ASM] " + label + ": no drawTexturedModalRect blit found");
            return basic;
        }
        if ("panel".equals(hookName))       recipePanelPatched = true;
        else if ("tab".equals(hookName))    recipeTabPatched = true;
        else if ("cell".equals(hookName))   recipeCellPatched = true;
        else if ("toggle".equals(hookName)) recipeTogglePatched = true;
        System.out.println("[S1mp1e/ASM] patched " + label + " (recipe glass, sites: " + swapped + ")");
        return write(cn);
    }

    // -----------------------------------------------------------------------
    // BATCH B (G) HUD overlays
    // -----------------------------------------------------------------------

    // G1a — GuiNewChat.drawChat: head-splice GlassChatHud.begin(this, updateCounter) (straight-line,
    //       no branch -> no stack-map frame), then redirect every per-line drawRect to
    //       GlassChatHud.rect (drops the dark background rects, keeps the scrollbar rects).
    private static byte[] patchChat(byte[] basic) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, CHAT_MCP, CHAT_SRG, CHAT_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] GuiNewChat.drawChat not found, skipping");
            return basic;
        }
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));   // this (GuiNewChat)
        pre.add(new VarInsnNode(Opcodes.ILOAD, 1));   // updateCounter
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_CHAT, "begin", CHAT_BEGIN_DESC, false));
        m.instructions.insert(pre);
        int rects = redirectStaticRect(m, HOOKS_CHAT, "rect", 0);
        chatPatched = true;
        System.out.println("[S1mp1e/ASM] patched GuiNewChat.drawChat (rect redirects: " + rects + ")");
        return write(cn);
    }

    // G1b — GuiChat.drawScreen: redirect the single input-bar drawRect to GlassChatHud.inputRect.
    private static byte[] patchChatInput(byte[] basic) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, DRAW_SCREEN_MCP, DRAW_SCREEN_SRG, DRAW_SCREEN_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] GuiChat.drawScreen not found, skipping");
            return basic;
        }
        int n = redirectStaticRect(m, HOOKS_CHAT, "inputRect", 1);   // only the first drawRect = input bar
        if (n == 0) {
            System.out.println("[S1mp1e/ASM] GuiChat.drawScreen: no input drawRect found");
            return basic;
        }
        chatInputPatched = true;
        System.out.println("[S1mp1e/ASM] patched GuiChat.drawScreen (input bar)");
        return write(cn);
    }

    // G2 — GuiPlayerTabOverlay.renderPlayerlist: redirect the header/list/footer/row drawRects to
    //      GlassTabList.rect (it splits tall plates from short row stripes by height).
    private static byte[] patchTabList(byte[] basic) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, TAB_MCP, TAB_SRG, TAB_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] GuiPlayerTabOverlay.renderPlayerlist not found, skipping");
            return basic;
        }
        int n = redirectStaticRect(m, HOOKS_TABLIST, "rect", 0);
        if (n == 0) {
            System.out.println("[S1mp1e/ASM] tab list: no drawRect found");
            return basic;
        }
        tabListPatched = true;
        System.out.println("[S1mp1e/ASM] patched GuiPlayerTabOverlay.renderPlayerlist (rects: " + n + ")");
        return write(cn);
    }

    // G5 — GuiIngameForge.renderRecordOverlay: swap the overlay-message drawString (INVOKEVIRTUAL
    //      FontRenderer.drawString) for GlassActionBar.draw (the receiver becomes the first param).
    private static byte[] patchActionBar(byte[] basic) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, REC_NAME, REC_NAME, REC_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] GuiIngameForge.renderRecordOverlay not found, skipping");
            return basic;
        }
        int swapped = redirectInvokeVirtual(m, FDRAW_MCP, FDRAW_SRG, FDRAW_DESC,
                HOOKS_ACTIONBAR, "draw", ACTIONBAR_DRAW_DESC, 1);
        if (swapped == 0) {
            System.out.println("[S1mp1e/ASM] action bar: no FontRenderer.drawString found");
            return basic;
        }
        actionBarPatched = true;
        System.out.println("[S1mp1e/ASM] patched GuiIngameForge.renderRecordOverlay (action-bar pill)");
        return write(cn);
    }

    // G6 — EntityRenderer.drawNameplate: swap the plate's four BufferBuilder.color(FFFF) calls for
    //      GlassNameTag.color (the receiver BufferBuilder becomes the first param).
    private static byte[] patchNameTag(byte[] basic) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, NAMEPLATE_MCP, NAMEPLATE_SRG, NAMEPLATE_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] EntityRenderer.drawNameplate not found, skipping");
            return basic;
        }
        int swapped = redirectInvokeVirtual(m, WRCOLOR_MCP, WRCOLOR_SRG, WRCOLOR_DESC,
                HOOKS_NAMETAG, "color", NAMETAG_COLOR_DESC, 0);
        if (swapped == 0) {
            System.out.println("[S1mp1e/ASM] name tag: no BufferBuilder.color found in drawNameplate");
            return basic;
        }
        nameTagPatched = true;
        System.out.println("[S1mp1e/ASM] patched EntityRenderer.drawNameplate (name-tag plate: " + swapped + " verts)");
        return write(cn);
    }

    // G4 — a concrete toast's draw: swap its FIRST drawTexturedModalRect for GlassToast.card (the
    //      GuiToast receiver becomes the first param; the redirect inherits the manager's slide pose).
    private static byte[] patchToast(byte[] basic, String label) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, TOAST_DRAW_MCP, TOAST_DRAW_SRG, TOAST_DRAW_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] " + label + ".draw not found, skipping toast glass");
            return basic;
        }
        int swapped = redirectInvokeVirtual(m, BLIT_MCP, BLIT_SRG, BLIT_DESC,
                HOOKS_TOAST, "card", TOAST_CARD_DESC, 1);
        if (swapped == 0) {
            System.out.println("[S1mp1e/ASM] " + label + ": no drawTexturedModalRect found");
            return basic;
        }
        toastCount++;
        toastPatched = true;
        System.out.println("[S1mp1e/ASM] patched " + label + " (glass toast card)");
        return write(cn);
    }

    /**
     * Redirect INVOKESTATIC {@code Gui.drawRect(IIIII)V} calls in {@code m} to
     * {@code hookOwner.hookName(IIIII)V}. Stack-neutral (same args, same void return), so no frame is
     * needed. {@code max <= 0} redirects all; otherwise stops after {@code max}. Returns the count.
     */
    private static int redirectStaticRect(MethodNode m, String hookOwner, String hookName, int max) {
        int n = 0;
        AbstractInsnNode insn = m.instructions.getFirst();
        while (insn != null) {
            AbstractInsnNode next = insn.getNext();
            if (insn.getOpcode() == Opcodes.INVOKESTATIC) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if ((RECT_MCP.equals(call.name) || RECT_SRG.equals(call.name)) && RECT_DESC.equals(call.desc)) {
                    m.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, hookOwner, hookName, RECT_DESC, false));
                    n++;
                    if (max > 0 && n >= max) break;
                }
            }
            insn = next;
        }
        return n;
    }

    /**
     * Redirect INVOKEVIRTUAL calls to {@code (mcp|srg)+callDesc} in {@code m} into a static hook
     * {@code hookOwner.hookName(hookDesc)} whose FIRST parameter is the original receiver — stack-neutral
     * (the receiver was already first on the stack). {@code max <= 0} redirects all; else stops after
     * {@code max}. Returns the count.
     */
    private static int redirectInvokeVirtual(MethodNode m, String mcp, String srg, String callDesc,
                                             String hookOwner, String hookName, String hookDesc, int max) {
        int n = 0;
        AbstractInsnNode insn = m.instructions.getFirst();
        while (insn != null) {
            AbstractInsnNode next = insn.getNext();
            if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if ((mcp.equals(call.name) || srg.equals(call.name)) && callDesc.equals(call.desc)) {
                    m.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, hookOwner, hookName, hookDesc, false));
                    n++;
                    if (max > 0 && n >= max) break;
                }
            }
            insn = next;
        }
        return n;
    }

    // ---- helpers ----------------------------------------------------------

    private static MethodNode find(ClassNode cn, String mcp, String srg, String desc) {
        for (MethodNode m : cn.methods) {
            if (!desc.equals(m.desc)) continue;
            if (mcp.equals(m.name) || srg.equals(m.name)) return m;
        }
        return null;
    }

    /** The previous REAL instruction, skipping labels / line numbers / frames. */
    private static AbstractInsnNode prevReal(AbstractInsnNode insn) {
        AbstractInsnNode p = insn == null ? null : insn.getPrevious();
        while (p != null && (p instanceof LabelNode || p instanceof FrameNode
                || p instanceof org.objectweb.asm.tree.LineNumberNode)) {
            p = p.getPrevious();
        }
        return p;
    }

    /** The int a constant-push instruction pushes, or {@link Integer#MIN_VALUE}. */
    private static int intVal(AbstractInsnNode insn) {
        if (insn == null) return Integer.MIN_VALUE;
        int op = insn.getOpcode();
        if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return op - Opcodes.ICONST_0;
        if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) return ((org.objectweb.asm.tree.IntInsnNode) insn).operand;
        return Integer.MIN_VALUE;
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
