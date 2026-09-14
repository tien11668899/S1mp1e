using System;
using System.IO;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace S1mp1e.Services;

/// <summary>
/// Writes launcher-owned fields into the S1mp1e client mod's own config
/// (<c>&lt;instance&gt;/config/s1mp1e/modules.json</c>) — currently just the key that
/// opens the in-game config GUI. The mod reads this at startup and re-normalizes the
/// file on its next save, so we only need to set the one field without disturbing the
/// rest. Atomic tmp+move, never throws.
/// </summary>
public static class S1mp1eModConfig
{
    public static string ModulesJsonPath(string instanceDir) =>
        Path.Combine(instanceDir, "config", "s1mp1e", "modules.json");

    public static void WriteMenuKey(string instanceDir, int lwjglCode)
    {
        try
        {
            var path = ModulesJsonPath(instanceDir);
            JsonObject root;
            try
            {
                root = File.Exists(path)
                    ? (JsonNode.Parse(File.ReadAllText(path)) as JsonObject) ?? new JsonObject()
                    : new JsonObject();
            }
            catch { root = new JsonObject(); }

            root["version"] ??= 1;
            root["menuKey"] = lwjglCode;
            // Leave "modules" as-is (or absent); the mod fills its own defaults on next save().
            if (root["modules"] is null) root["modules"] = new JsonObject();

            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            var tmp = path + ".tmp";
            File.WriteAllText(tmp, root.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));
            File.Move(tmp, path, overwrite: true);
        }
        catch { /* best effort — the in-game rebind is the reliable path */ }
    }

    /// <summary>The curated open-key choices offered in Settings: label → LWJGL keycode.
    /// (Avalonia's Key enum ≠ LWJGL codes, so we map explicitly.)</summary>
    public static readonly (string Label, int Code)[] KeyChoices =
    {
        ("Right Shift", 54),   // Keyboard.KEY_RSHIFT = 0x36 = 54
        ("Right Ctrl",  157),  // KEY_RCONTROL = 0x9D
        ("Right Alt",   184),  // KEY_RMENU = 0xB8
        ("Insert",      210),  // KEY_INSERT = 0xD2
        ("Home",        199),  // KEY_HOME = 0xC7
        ("End",         207),  // KEY_END = 0xCF
        ("Page Up",     201),  // KEY_PRIOR
        ("`  (grave)",  41),   // KEY_GRAVE = 0x29
        ("\\  ",        43),   // KEY_BACKSLASH = 0x2B
        ("F6",          64),   // KEY_F6
        ("F10",         68),   // KEY_F10
    };

    public static string LabelFor(int code)
    {
        foreach (var c in KeyChoices) if (c.Code == code) return c.Label;
        return "Right Shift";
    }
}
