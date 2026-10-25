# Builds tools\worldconv\build\worldconv.jar against the installed 26.3 game (vanilla classpath from the launcher's own
# launch plan), and writes build\classpath.txt so run.ps1 can start the tool on that same classpath.
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$repo = Resolve-Path (Join-Path $here '..\..')
$itest = Join-Path $repo 'src-tauri\target\release\itest.exe'
$mc = Join-Path $env:APPDATA '.minecraft'
$plan = (& $itest plan 26.3 vanilla $mc) | Out-String | ConvertFrom-Json
$a = @($plan.args)
$cp = $a[[Array]::IndexOf($a, '-cp') + 1]
$jdk = Join-Path $env:LOCALAPPDATA 'Programs\Java\temurin-25\bin'
$build = Join-Path $here 'build'
$classes = Join-Path $build 'classes'
if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
New-Item -ItemType Directory -Force $classes | Out-Null
$sources = Get-ChildItem (Join-Path $here 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$argfile = Join-Path $build 'javac.args'
[IO.File]::WriteAllLines($argfile, @('--release', '25', '-encoding', 'UTF-8', '-Xlint:-options', '-cp', "`"$($cp -replace '\\', '/')`"", '-d', "`"$($classes -replace '\\', '/')`"") + ($sources | ForEach-Object { "`"$($_ -replace '\\', '/')`"" }))
& (Join-Path $jdk 'javac.exe') "@$argfile"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
$res = Join-Path $here 'resources'
if (Test-Path $res) { Copy-Item (Join-Path $res '*') $classes -Recurse -Force }
$jar = Join-Path $build 'worldconv.jar'
& (Join-Path $jdk 'jar.exe') --create --file $jar -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
[IO.File]::WriteAllText((Join-Path $build 'classpath.txt'), $cp)
[IO.File]::WriteAllText((Join-Path $build 'java.txt'), ($plan.java -replace 'javaw\.exe$', 'java.exe'))
"built $jar"
