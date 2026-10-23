using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

namespace S1mp1e.Services;

/// <summary>
/// Outcome of a conflict scan. <see cref="Resolved"/>: duplicate mod ids where the launcher already knows which copy it
/// loads (one clearly newest version) — informational. <see cref="Warnings"/>: duplicates it cannot rank, which the
/// player should sort out with 整理重複.
/// </summary>
public sealed record ConflictResult(IReadOnlyList<string> Resolved, IReadOnlyList<string> Warnings);

/// <summary>
/// Detects the classic hard crash where two jars in the folder the game launches from declare the SAME mod id. It never
/// renames or disables anything: the launch itself (launch.rs <c>dedupe_by_mod_id</c>) loads only the newest copy of
/// each id, so the game cannot crash on it, and the player's files stay exactly as they left them. (Auto-renaming the
/// older copy to <c>.jar.disabled</c> used to pile up disabled twins the player never asked for.)
/// </summary>
public static class ConflictScanner
{
    /// Mirror of Rust launch.rs <c>pick_user_mods</c>: a version's mods live in <c>s1mp1e-mods/&lt;mc&gt;/</c> and only
    /// there. (The flat <c>s1mp1e-mods/</c> fallback was removed — it loaded one version's mods into another.)
    public static string LaunchModDir(string mcRoot, string mc) => Path.Combine(mcRoot, "s1mp1e-mods", mc);

    public static ConflictResult Detect(IEnumerable<LocalMod> mods, string launchDir)
    {
        var resolved = new List<string>();
        var warnings = new List<string>();

        var inDir = mods.Where(m => m.Enabled
                                 && !string.IsNullOrEmpty(m.Id)
                                 && PathEq(Path.GetDirectoryName(m.JarPath), launchDir))
                        .ToList();

        // Group by (loader, id): a same-loader same-id group of >1 is the duplicate crash.
        foreach (var grp in inDir.GroupBy(m => (m.Loader, Key: m.Id.ToLowerInvariant()))
                                 .Where(g => g.Count() > 1))
        {
            var members = grp.ToList();
            var id = members[0].Id;

            var ranked = new List<(LocalMod mod, SemVer ver)>();
            foreach (var m in members)
                if (TryParseSemver(m.Version, out var v)) ranked.Add((m, v));

            if (ranked.Count == members.Count)
            {
                ranked.Sort((x, y) => Compare(y.ver, x.ver));       // newest first
                if (Compare(ranked[0].ver, ranked[1].ver) > 0)      // a UNIQUE maximum: the launch loads that one
                {
                    resolved.Add($"{id} ×{members.Count}（只載入 {ranked[0].mod.Version}）");
                    continue;
                }
            }
            // Unparseable / tie: the launch falls back to the newest file, which may not be what the player wants.
            warnings.Add($"{id} ×{members.Count}");
        }
        return new ConflictResult(resolved, warnings);
    }

    private static bool PathEq(string? a, string? b)
        => !string.IsNullOrEmpty(a) && !string.IsNullOrEmpty(b)
        && string.Equals(Path.GetFullPath(a!).TrimEnd('\\'), Path.GetFullPath(b!).TrimEnd('\\'),
                         StringComparison.OrdinalIgnoreCase);

    // ---- minimal semver (MAJOR.MINOR.PATCH[-pre][+build]); bail on anything else ----
    public readonly record struct SemVer(int Major, int Minor, int Patch, string Pre);

    public static bool TryParseSemver(string? s, out SemVer v)
    {
        v = default;
        if (string.IsNullOrWhiteSpace(s)) return false;
        s = s!.Trim();
        if (s.StartsWith("v", StringComparison.OrdinalIgnoreCase)) s = s.Substring(1);
        s = s.Split('+')[0];                       // drop build metadata
        var pre = "";
        var dash = s.IndexOf('-');
        if (dash >= 0) { pre = s.Substring(dash + 1); s = s.Substring(0, dash); }
        var parts = s.Split('.');
        if (parts.Length == 0 || parts.Length > 3) return false;
        int[] n = { 0, 0, 0 };
        for (int i = 0; i < parts.Length && i < 3; i++)
            if (!int.TryParse(parts[i], out n[i])) return false;
        v = new SemVer(n[0], n[1], n[2], pre);
        return true;
    }

    public static int Compare(SemVer a, SemVer b)
    {
        if (a.Major != b.Major) return a.Major.CompareTo(b.Major);
        if (a.Minor != b.Minor) return a.Minor.CompareTo(b.Minor);
        if (a.Patch != b.Patch) return a.Patch.CompareTo(b.Patch);
        // A release outranks a prerelease of the same X.Y.Z.
        if (string.IsNullOrEmpty(a.Pre) && !string.IsNullOrEmpty(b.Pre)) return 1;
        if (!string.IsNullOrEmpty(a.Pre) && string.IsNullOrEmpty(b.Pre)) return -1;
        return string.Compare(a.Pre, b.Pre, StringComparison.Ordinal);
    }
}
