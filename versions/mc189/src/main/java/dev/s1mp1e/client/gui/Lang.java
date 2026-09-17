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

        MODE.put("Cross", "十字");
        MODE.put("Dot", "圓點");
        MODE.put("Circle", "圓環");
        MODE.put("T", "T 字");
    }

    public static String module(String id)  { String v = MODULE.get(id);  return v != null ? v : id; }
    public static String setting(String id)  { String v = SETTING.get(id); return v != null ? v : id; }
    public static String mode(String id)     { String v = MODE.get(id);    return v != null ? v : id; }
}
