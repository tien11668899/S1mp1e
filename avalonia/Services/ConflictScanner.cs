using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

namespace S1mp1e.Services;

/// <summary>Outcome of a conflict scan: how many jars were auto-disabled, a
/// human line per auto-fix, and a warning per conflict we would not touch.</summary>
public sealed record ConflictResult(int AutoDisabled, IReadOnlyList<string> Fixed, IReadOnlyList<string> Warnings);

/// <summary>
/// Static mod-conflict self-correction (v1). Detects the classic hard-crash where two
/// jars in the SAME launched folder declare the SAME mod id — Fabric/Forge refuse to
/// load and the game dies with "Duplicate mod". We auto-disable the older copy ONLY
/// when the versions are cleanly comparable (real semver, one unique maximum); anything
/// ambiguous (unparseable versions, a tie, mixed loaders) is surfaced as a warning
/// instead of guessing. Fixes are reversible — the loser is renamed to .jar.disabled,
/// never deleted, and launch.rs's pick_user_mods globs *.jar so it's simply skipped.
/// </summary>
public static class ConflictScanner
{
    /// Mirror of Rust launch.rs `pick_user_mods`: the per-MC subfolder if it holds any
    /// non-glass *.jar, else the flat s1mp1e-mods/. Duplicates only crash within the set
    /// that actually co-loads, so detection is scoped to this one directory.
    public static string LaunchModDir(string mcRoot, string mc)
    {
        var perMc = Path.Combine(mcRoot, "s1mp1e-mods", mc);
        try
        {
            if (Directory.Exists(perMc) && Directory.EnumerateFiles(perMc, "*.jar")
                    .Any(p => !Path.GetFileName(p).StartsWith("glass-", StringComparison.OrdinalIgnoreCase)))
                return perMc;
        }
        catch { }
        return Path.Combine(mcRoot, "s1mp1e-mods");
    }

    public static ConflictResult DetectAndFix(IEnumerable<LocalMod> mods, string launchDir)
    {
        var fixedMsgs = new List<string>();
        var warnings = new List<string>();
        int disabled = 0;

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
            bool allParse = true;
            foreach (var m in members)
            {
                if (TryParseSemver(m.Version, out var v)) ranked.Add((m, v));
                else { allParse = false; break; }
            }

            if (allParse && ranked.Count == members.Count)
            {
                ranked.Sort((x, y) => Compare(y.ver, x.ver));       // newest first
                if (Compare(ranked[0].ver, ranked[1].ver) > 0)      // a UNIQUE maximum
                {
                    foreach (var loser in ranked.Skip(1))
                    {
                        try { LocalModScanner.SetEnabled(loser.mod.JarPath, false); disabled++; }
                        catch { }
                    }
                    fixedMsgs.Add($"{id}(留 {ranked[0].mod.Version})");
                    continue;
                }
            }
            // Unparseable / tie / mixed → don't guess; ask the user to pick.
            warnings.Add($"{id} ×{members.Count}");
        }
        return new ConflictResult(disabled, fixedMsgs, warnings);
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

    private static int Compare(SemVer a, SemVer b)
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
