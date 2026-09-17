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
        SETTING.put("Sheen colour", "掃光顏色");
        SETTING.put("Glow", "散發微光");
        SETTING.put("Hide vanilla effects", "隱藏原版效果");

        MODE.put("Cross", "十字");
        MODE.put("Dot", "圓點");
        MODE.put("Circle", "圓環");
        MODE.put("T", "T 字");
    }

    public static String module(String id)  { String v = MODULE.get(id);  return v != null ? v : id; }
    public static String setting(String id)  { String v = SETTING.get(id); return v != null ? v : id; }
    public static String mode(String id)     { String v = MODE.get(id);    return v != null ? v : id; }
}
