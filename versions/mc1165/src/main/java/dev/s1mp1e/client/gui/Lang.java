package dev.s1mp1e.client.gui;

import java.util.HashMap;
import java.util.Map;

/**
 * Display-only Traditional-Chinese labels for the in-game GUI. The underlying
 * module / setting / mode <em>identifiers</em> stay English (they key the config
 * file and vanilla keybinds), so this maps id → 中文 purely for rendering. An
 * unmapped id falls through to itself, so nothing ever renders blank.
 */
public final class Lang {

    private Lang() {}

    private static final Map<String, String> MODULE  = new HashMap<String, String>();
    private static final Map<String, String> SETTING = new HashMap<String, String>();
    private static final Map<String, String> MODE    = new HashMap<String, String>();

    static {
        MODULE.put("CPS", "點擊速度");
        MODULE.put("Crosshair", "準星");
        MODULE.put("ArmorHUD", "裝備欄");
        MODULE.put("PotionHUD", "藥水欄");
        MODULE.put("OldAnimations", "舊版動畫");
        MODULE.put("NoHurtCam", "關閉受傷晃動");
        MODULE.put("FpsHUD", "幀率顯示");
        MODULE.put("CoordsHUD", "座標顯示");
        MODULE.put("InventoryHUD", "背包預覽");
        MODULE.put("HungerHUD", "隱藏飽食度");
        MODULE.put("Keystrokes", "按鍵顯示");
        MODULE.put("HandPosition", "手部位置");
        MODULE.put("SteadyFOV", "固定視野");
        MODULE.put("Zoom", "視野縮放");
        MODULE.put("Fullbright", "全亮");
        MODULE.put("XpFlow", "經驗條流動");
        MODULE.put("BlockOutline", "方塊外框");
        MODULE.put("ChromaHud", "彩虹 HUD");
        MODULE.put("LowFire", "火焰畫面降低");
        MODULE.put("Particles", "粒子");
        MODULE.put("AttackRing", "攻擊冷卻玻璃環");
        MODULE.put("HitMarker", "命中標記");

        SETTING.put("X", "X 位置");
        SETTING.put("Y", "Y 位置");
        SETTING.put("PosX", "X 位置");
        SETTING.put("PosY", "Y 位置");
        SETTING.put("Text Colour", "文字顏色");
        SETTING.put("Colour", "顏色");
        SETTING.put("Background", "背景");
        SETTING.put("Scale", "縮放");
        SETTING.put("Show right CPS", "顯示右側");
        SETTING.put("Shadow", "文字陰影");
        SETTING.put("Shape", "形狀");
        SETTING.put("Size", "大小");
        SETTING.put("Thickness", "粗細");
        SETTING.put("Gap", "間距");
        SETTING.put("Outline", "外框");
        SETTING.put("Outline Colour", "外框顏色");
        SETTING.put("Center Dot", "中心點");
        SETTING.put("Dot Size", "點大小");
        SETTING.put("Rotation", "旋轉");
        SETTING.put("Camera shake", "鏡頭晃動");
        SETTING.put("Red flash", "紅色閃爍");
        SETTING.put("Swing while using", "使用時揮手");
        SETTING.put("Show 'FPS'", "顯示 FPS");
        SETTING.put("Show facing", "顯示朝向");
        SETTING.put("Y offset", "Y 偏移");
        SETTING.put("Glint colour", "飽食度顏色");
        SETTING.put("Offset X", "X 偏移");
        SETTING.put("Offset Y", "Y 偏移");
        SETTING.put("Mouse (L/R)", "滑鼠鍵 (L/R)");
        SETTING.put("Sneak", "蹲下鍵");
        SETTING.put("Space", "空白鍵");
        SETTING.put("Highlight", "高光顏色");
        SETTING.put("Main X", "主手 X");
        SETTING.put("Main Y", "主手 Y");
        SETTING.put("Main Z", "主手 Z");
        SETTING.put("Off X", "副手 X");
        SETTING.put("Off Y", "副手 Y");
        SETTING.put("Off Z", "副手 Z");
        SETTING.put("Key (GLFW)", "按鍵 (GLFW)");
        SETTING.put("Zoom", "縮放倍率");
        SETTING.put("Smoothness", "平滑度");
        SETTING.put("Block other zoom", "阻擋其他模組縮放");
        SETTING.put("Brightness", "亮度");
        SETTING.put("Amount", "粒子量");
        SETTING.put("Keep", "保留比例");
        SETTING.put("Sheen colour", "掃光顏色");
        SETTING.put("Glow", "散發微光");
        SETTING.put("Hide vanilla effects", "隱藏原版效果");
        SETTING.put("Show time", "顯示時間");
        SETTING.put("Colour fill", "顏色填滿");
        // Block Outline (H1)
        SETTING.put("Line width", "線寬");
        SETTING.put("Chroma", "彩虹色");
        SETTING.put("Chroma speed", "彩虹速度");
        SETTING.put("Fill", "填充");
        SETTING.put("Fill colour", "填充顏色");
        // Chroma HUD (H2)
        SETTING.put("Speed", "速度");
        SETTING.put("Saturation", "飽和度");
        SETTING.put("Wave", "字元波動");
        SETTING.put("Accents", "套用至高光點綴");
        SETTING.put("Fire lower", "下降高度");
        SETTING.put("Fire opacity", "火焰不透明度");
        SETTING.put("Fire size", "火焰大小");
        SETTING.put("Hide fire", "完全隱藏火焰");
        SETTING.put("Hide with Fire Res", "有抗火時隱藏");
        SETTING.put("Ring radius", "環半徑");
        SETTING.put("Ring width", "環粗細");
        SETTING.put("Ring colour", "進度顏色");
        SETTING.put("Ready colour", "蓄滿顏色");
        SETTING.put("Glass track", "玻璃底環");
        SETTING.put("Track opacity", "底環不透明度");
        SETTING.put("Clockwise", "順時針");
        SETTING.put("Show when ready", "蓄滿且瞄準生物時保持顯示");
        SETTING.put("Hit colour", "命中顏色");
        SETTING.put("Crit colour", "暴擊顏色");
        SETTING.put("Kill colour", "擊殺顏色");
        SETTING.put("Marker size", "標記長度");
        SETTING.put("Marker time", "顯示時間 (ms)");
        SETTING.put("Glass ticks", "玻璃底");
        SETTING.put("Pop", "彈出幅度");
        SETTING.put("Crits only", "只顯示暴擊");
        SETTING.put("Ready shape", "蓄滿形狀");
        SETTING.put("Ready size", "蓄滿大小 (0=同環半徑)");
        SETTING.put("Ready width", "蓄滿粗細 (0=同環粗細)");

        MODE.put("Cross", "十字");
        MODE.put("Dot", "圓點");
        MODE.put("Circle", "圓環");
        MODE.put("T", "T 字");
        MODE.put("X", "X 形");
        MODE.put("Square", "方框");
        MODE.put("Wrap", "包覆準星");
        MODE.put("Same", "同上");
        MODE.put("Reduced", "減少");
        MODE.put("None", "無");
    }

    public static String module(String id)  { String v = MODULE.get(id);  return v != null ? v : id; }
    public static String setting(String id)  { String v = SETTING.get(id); return v != null ? v : id; }
    public static String mode(String id)     { String v = MODE.get(id);    return v != null ? v : id; }
}
