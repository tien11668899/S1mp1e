using System;
using System.IO;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace S1mp1e.Services;

/// <summary>
/// Writes launcher-owned fields into the S1mp1e client mod's own config — currently just
/// the key that opens the in-game config GUI.
///
/// <para>The mod reads ONE shared <c>modules.json</c> at <c>&lt;.minecraft&gt;/s1mp1e-mods/</c>
/// (every version the launcher starts runs from <c>&lt;.minecraft&gt;/instances/&lt;mc&gt;</c>
/// and looks one level up), so the open key set here applies to 1.8.9, 1.20.1, 1.21.1 and 26.2
/// alike. The value stored in the file is a <b>GLFW</b> code, which is what the modern versions
/// use natively and what 1.8.9 translates through its own KeyCodes table.</para>
///
/// <para>The launcher's own <c>LauncherConfig.MenuKey</c> keeps its historical LWJGL meaning so
/// existing launcher configs stay valid. Merging write, atomic tmp+move, never throws.</para>
/// </summary>
public static class S1mp1eModConfig
{
    /// <summary>The shared cross-version modules.json every S1mp1e client build reads.</summary>
    public static string SharedModulesJsonPath(string mcDir) =>
        Path.Combine(mcDir, "s1mp1e-mods", "modules.json");

    /// <summary>The legacy per-instance path (dev runs / non-launcher launches still use it).</summary>
    public static string ModulesJsonPath(string instanceDir) =>
        Path.Combine(instanceDir, "config", "s1mp1e", "modules.json");

    /// <param name="mcDir">The .minecraft root (the launcher's EffectiveMcDir).</param>
    /// <param name="glfwCode">The open key as a GLFW code — see <see cref="KeyChoices"/>.</param>
    public static void WriteMenuKey(string mcDir, int glfwCode)
    {
        try
        {
            var path = SharedModulesJsonPath(mcDir);
            JsonObject root;
            try
            {
                root = File.Exists(path)
                    ? (JsonNode.Parse(File.ReadAllText(path)) as JsonObject) ?? new JsonObject()
                    : new JsonObject();
            }
            catch { root = new JsonObject(); }

            root["version"] ??= 1;
            root["menuKey"] = glfwCode;
            // Everything else is the mod's: unknown modules, per-setting values and the
            // "_seedRank"/"_migrated" bookkeeping all stay exactly as written.
            if (root["modules"] is null) root["modules"] = new JsonObject();

            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            var tmp = path + ".tmp";
            File.WriteAllText(tmp, root.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));
            File.Move(tmp, path, overwrite: true);
        }
        catch { /* best effort — the in-game rebind is the reliable path */ }
    }

    /// <summary>The open key the shared modules.json currently holds (a GLFW code), or null
    /// when there is no shared file yet / it has no menuKey. Lets the settings page show a
    /// key the user rebound in-game.</summary>
    public static int? ReadMenuKey(string mcDir)
    {
        try
        {
            var path = SharedModulesJsonPath(mcDir);
            if (!File.Exists(path)) return null;
            if (JsonNode.Parse(File.ReadAllText(path)) is not JsonObject root) return null;
            var mk = root["menuKey"];
            if (mk is null) return null;
            return mk.GetValue<int>();
        }
        catch { return null; }
    }

    /// <summary>The curated open-key choices offered in Settings. Each carries BOTH code
    /// spaces: <c>Lwjgl</c> is what 1.8.9 and the launcher's own config use, <c>Glfw</c> is
    /// what goes into the shared modules.json. (Avalonia's Key enum is neither, so the
    /// mapping is explicit.)</summary>
    public static readonly (string Label, int Lwjgl, int Glfw)[] KeyChoices =
    {
        ("Right Shift", 54,  344),  // Keyboard.KEY_RSHIFT = 0x36 / GLFW_KEY_RIGHT_SHIFT
        ("Right Ctrl",  157, 345),  // KEY_RCONTROL = 0x9D / GLFW_KEY_RIGHT_CONTROL
        ("Right Alt",   184, 346),  // KEY_RMENU = 0xB8 / GLFW_KEY_RIGHT_ALT
        ("Insert",      210, 260),  // KEY_INSERT = 0xD2 / GLFW_KEY_INSERT
        ("Home",        199, 268),  // KEY_HOME = 0xC7 / GLFW_KEY_HOME
        ("End",         207, 269),  // KEY_END = 0xCF / GLFW_KEY_END
        ("Page Up",     201, 266),  // KEY_PRIOR / GLFW_KEY_PAGE_UP
        ("`  (grave)",  41,  96),   // KEY_GRAVE = 0x29 / GLFW_KEY_GRAVE_ACCENT
        ("\\  ",        43,  92),   // KEY_BACKSLASH = 0x2B / GLFW_KEY_BACKSLASH
        ("F6",          64,  295),  // KEY_F6 / GLFW_KEY_F6
        ("F10",         68,  299),  // KEY_F10 / GLFW_KEY_F10
    };

    /// <summary>Label for a key code in EITHER code space (launcher configs hold LWJGL codes,
    /// the shared modules.json holds GLFW ones).</summary>
    public static string LabelFor(int code)
    {
        foreach (var c in KeyChoices) if (c.Lwjgl == code) return c.Label;
        foreach (var c in KeyChoices) if (c.Glfw == code) return c.Label;
        return "Right Shift";
    }
}
