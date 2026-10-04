# 建置用環境：JDK 25＋AF_UNIX 暫存目錄修正（見 LiquidGlass26 記錄）
param([string]$Tasks = "build")
$env:TEMP = "C:\Temp"; $env:TMP = "C:\Temp"
$env:GRADLE_OPTS = "-Djdk.net.unixdomain.tmpdir=C:\Temp -Djava.io.tmpdir=C:\Temp"
$env:JAVA_HOME = "C:\Users\Administrator\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot"
Set-Location $PSScriptRoot
& .\gradlew.bat $Tasks.Split(" ") --console=plain
