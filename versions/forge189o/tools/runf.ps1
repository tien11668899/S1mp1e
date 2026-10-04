# forge189o 開發客戶端（單獨，不含玻璃/Argentum）。用法：runf.ps1 -Tag <名稱> [-Quit 秒]
param([Parameter(Mandatory=$true)][string]$Tag, [double]$Quit = 8, [string]$Test = "", [switch]$With189o, [switch]$Shot)
& C:\Temp\s1bench189o\waitlock_o.ps1
$opt = "C:\Users\Administrator\source\S1mp1e\versions\forge189o\run\options.txt"
if (Test-Path $opt) { (Get-Content $opt) -replace '^fullscreen:true$', 'fullscreen:false' | Set-Content -Encoding ascii $opt }
$work = "C:\Temp\s1port\forgetool\run_$Tag"
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force $work | Out-Null
$env:TEMP = "C:\Temp"; $env:TMP = "C:\Temp"
$env:GRADLE_OPTS = "-Djdk.net.unixdomain.tmpdir=C:\Temp -Djava.io.tmpdir=C:\Temp"
$env:JAVA_HOME = "C:\Users\Administrator\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot"
$env:S1FORGE_AUTOQUIT = "$Quit"
if ($Test -ne "") { $env:S1FORGE_TEST = $Test; $env:S1FORGE_AUTOQUIT = "0" } else { Remove-Item Env:\S1FORGE_TEST -ErrorAction SilentlyContinue }
$extra = ""
if ($Shot) { New-Item -ItemType Directory -Force "$work\shots" | Out-Null; $env:S1MP1E_SHOT = "$work\shots"; $env:S1MP1E_AUDIT = "1"; $env:S1FORGE_AUTOQUIT = "0" } else { Remove-Item Env:\S1MP1E_SHOT, Env:\S1MP1E_AUDIT -ErrorAction SilentlyContinue }
if ($With189o) { $extra = " -Pwith189o" }
cmd /c "C:\Users\Administrator\source\S1mp1e\versions\forge189o\gradlew.bat -p C:\Users\Administrator\source\S1mp1e\versions\forge189o runClient --console=plain$extra > `"$work\game.log`" 2>&1"
"run exit=$LASTEXITCODE"
Remove-Item Env:\S1FORGE_AUTOQUIT, Env:\S1FORGE_TEST, Env:\S1MP1E_SHOT, Env:\S1MP1E_AUDIT -ErrorAction SilentlyContinue
Select-String -Path "$work\game.log" -Pattern "\[test\]|\[audit\] FAIL|COREMOD AUDIT|DevShot|S1Forge|FML|Forge Mod Loader|Mixin apply failed|InvalidInjection|Exception|Caused by|Error|FAILED" | Select-Object -First 80 | ForEach-Object { $_.Line }
