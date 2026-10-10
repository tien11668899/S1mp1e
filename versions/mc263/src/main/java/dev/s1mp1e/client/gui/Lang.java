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
        MODULE.put("DynamicIsland", "音樂靈動島");
        MODULE.put("NameTags", "名牌");

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
        SETTING.put("Expand on track change", "換歌時展開");
        SETTING.put("Hide when paused", "暫停一陣子後隱藏");
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
        SETTING.put("Key", "按鍵");
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
        // BlockOutline
        SETTING.put("Line width", "線寬");
        SETTING.put("Chroma", "彩虹色");
        SETTING.put("Chroma speed", "彩虹速度");
        SETTING.put("Fill", "填充");
        SETTING.put("Fill colour", "填充顏色");
        // ChromaHud
        SETTING.put("Speed", "速度");
        SETTING.put("Saturation", "飽和度");
        SETTING.put("Wave", "字元波動");
        SETTING.put("Accents", "套用至高光點綴");
        // LowFire
        SETTING.put("Fire lower", "下降高度");
        SETTING.put("Fire opacity", "火焰不透明度");
        // AttackRing
        SETTING.put("Ring radius", "環半徑");
        SETTING.put("Ring width", "環粗細");
        SETTING.put("Ring colour", "進度顏色");
        SETTING.put("Show when ready", "蓄滿且瞄準生物時保持顯示");
        // HitMarker
        SETTING.put("Hit colour", "命中顏色");
        SETTING.put("Crit colour", "暴擊顏色");
        SETTING.put("Marker size", "標記長度");
        SETTING.put("Marker time", "顯示時間 (ms)");
        SETTING.put("Glass ticks", "玻璃底");
        SETTING.put("Fire size", "火焰大小");
        SETTING.put("Hide fire", "完全隱藏火焰");
        SETTING.put("Hide with Fire Res", "有抗火時隱藏");
        SETTING.put("Ready colour", "蓄滿顏色");
        SETTING.put("Glass track", "玻璃底環");
        SETTING.put("Track opacity", "底環不透明度");
        SETTING.put("Clockwise", "順時針");
        SETTING.put("Kill colour", "擊殺顏色");
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
        MODE.put("Glass", "液態玻璃");
        MODE.put("Vanilla", "原版");
        MODE.put("Off", "關閉");
        MODULE.put("CS2Knife", "CS2 刀");
        SETTING.put("Skin", "刀皮");
        SETTING.put("Wear", "磨損");
        MODE.put("fade", "漸層彩虹");
        MODE.put("crimson_web", "赤紅之網");
        MODE.put("case_hardened", "外殼硬化");
        MODE.put("slaughter", "屠夫");
        MODE.put("night", "惡夢之夜");
        MODE.put("blue_steel", "藍鋼");
        MODE.put("stained", "髒亂");
        MODE.put("safari_mesh", "狩獵網格");
        MODE.put("boreal_forest", "北寒帶林");
        MODE.put("forest_ddpat", "數位森林");
        MODE.put("scorched", "熾灼");
        MODE.put("urban_masked", "都市偽裝");
        MODE.put("doppler_p1", "都卜勒（第 1 相）");
        MODE.put("doppler_p2", "都卜勒（第 2 相）");
        MODE.put("doppler_p3", "都卜勒（第 3 相）");
        MODE.put("doppler_p4", "都卜勒（第 4 相）");
        MODE.put("ruby", "都卜勒（紅寶石）");
        MODE.put("sapphire", "都卜勒（藍寶石）");
        MODE.put("black_pearl", "都卜勒（黑珍珠）");
        MODE.put("tiger_tooth", "虎牙");
        MODE.put("marble_fade", "大理石漸層");
        MODE.put("damascus", "大馬士革鋼");
        MODE.put("rust_coat", "鏽衣");
        MODE.put("ultraviolet", "致命紫羅蘭");
        MODE.put("gamma_p1", "伽傌都卜勒（第 1 相）");
        MODE.put("gamma_p2", "伽傌都卜勒（第 2 相）");
        MODE.put("gamma_p3", "伽傌都卜勒（第 3 相）");
        MODE.put("gamma_p4", "伽傌都卜勒（第 4 相）");
        MODE.put("emerald", "伽傌都卜勒（綠寶石）");
        MODE.put("lore", "傳說");
        MODE.put("autotronic", "車用電子");
        MODE.put("black_laminate", "黑層壓版");
        MODE.put("bright_water", "澄水");
        MODE.put("freehand", "手繪紋");
        SETTING.put("Gloves", "手套");
        SETTING.put("Viewmodel FOV", "視角 FOV");
        SETTING.put("Offset X", "偏移 X");
        SETTING.put("Offset Y", "偏移 Y");
        SETTING.put("Offset Z", "偏移 Z");
        SETTING.put("Inspect on F", "F 檢視");
        SETTING.put("Heavy on right click", "右鍵重擊");
        SETTING.put("Locker key (GLFW)", "刀庫存按鍵");
        SETTING.put("Sounds", "音效");
        SETTING.put("Arms", "手臂");
        SETTING.put("Pattern seed", "花紋編號");
        SETTING.put("CS arms everywhere", "空手也用 CS 手臂");
        SETTING.put("Guard X", "守備 左右");
        SETTING.put("Guard height", "守備 高度");
        SETTING.put("Guard distance", "守備 距離");
        SETTING.put("Guard angle", "守備 角度");
        SETTING.put("Punch X", "出拳 左右");
        SETTING.put("Punch height", "出拳 高度");
        SETTING.put("Punch distance", "出拳 距離");
        SETTING.put("Punch angle", "出拳 角度");
        SETTING.put("Shoulder drive", "出拳 送肩");
        SETTING.put("Punch out time", "出拳 出手時間");
        SETTING.put("Dig lift", "挖掘 抬高");
        SETTING.put("Dig reach", "挖掘 前捶");
        MODE.put("cs2_arms", "CS2 手臂");
        MODE.put("mc_arms", "原版手臂");
        SETTING.put("Sound volume", "音量");
        MODE.put("knife_karambit", "爪子刀");
        MODE.put("knife_m9", "M9 刺刀");
        MODE.put("knife_butterfly", "蝴蝶刀");
        MODE.put("knife_bayonet", "刺刀");
        MODE.put("knife_talon", "熊爪刀");
        MODE.put("knife_stiletto", "短劍");
        MODE.put("knife_skeleton", "骷髏匕首");
        MODE.put("knife_ursus", "熊刀");
        MODE.put("knife_flip", "折疊刀");
        MODE.put("knife_gut", "穿腸刀");
        MODE.put("knife_falchion", "彎刀");
        MODE.put("knife_bowie", "鮑伊獵刀");
        MODE.put("knife_tactical", "獵殺者匕首");
        MODE.put("knife_push", "暗影雙匕");
        MODE.put("knife_navaja", "折刀");
        MODE.put("knife_outdoor", "流浪者匕首");
        MODE.put("knife_canis", "求生匕首");
        MODE.put("knife_cord", "系繩匕首");
        MODE.put("knife_css", "海豹短刀");
        MODE.put("knife_kukri", "廓爾喀刀");
        MODE.put("knife_default_ct", "預設刀（CT）");
        MODE.put("knife_default_t", "預設刀（T）");
        MODE.put("glove_sporty", "運動員手套");
        MODE.put("glove_specialist", "技術士手套");
        MODE.put("glove_slick", "駕駛手套");
        MODE.put("glove_motorcycle", "機車手套");
        MODE.put("glove_handwrap", "手綁帶");
        MODE.put("glove_hydra", "九頭蛇手套");
        MODE.put("glove_bloodhound", "獵犬手套");
        MODE.put("glove_brokenfang", "《狂牙行動》手套");
        MODE.put("glove_fullfinger", "全指手套");
        MODE.put("glove_fingerless", "預設 T 手套");
        MODE.put("glove_hardknuckle", "預設 CT 手套");
    }

    public static String module(String id)  { String v = MODULE.get(id);  return v != null ? v : id; }
    public static String setting(String id)  { String v = SETTING.get(id); return v != null ? v : id; }
    public static String mode(String id)     { String v = MODE.get(id);    return v != null ? v : id; }
}
