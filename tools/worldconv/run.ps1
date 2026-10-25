# Runs worldconv on the 26.3 classpath recorded by build.ps1: .\run.ps1 info "<world dir>"
$build = Join-Path $PSScriptRoot 'build'
$cp = [IO.File]::ReadAllText((Join-Path $build 'classpath.txt')) + ';' + (Join-Path $build 'worldconv.jar')
$java = [IO.File]::ReadAllText((Join-Path $build 'java.txt'))
& $java '-Xmx4G' '-cp' $cp 'dev.s1mp1e.worldconv.Main' @args
