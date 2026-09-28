param([string]$JdkPath = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
if (-not $JdkPath) { $JdkPath = Get-ChildItem 'C:\Program Files\Java\jdk-*' -Directory | Sort-Object Name | Select-Object -Last 1 -ExpandProperty FullName }
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'app\build\tests'
New-Item -ItemType Directory -Path $out -Force | Out-Null
& "$JdkPath\bin\javac.exe" --release 8 -encoding UTF-8 -d $out "$root\app\src\main\java\com\stepbeat\app\BeatClock.java" "$root\tests\BeatClockTest.java"
if ($LASTEXITCODE -ne 0) { throw '测试编译失败' }
& "$JdkPath\bin\java.exe" -cp $out com.stepbeat.app.BeatClockTest
if ($LASTEXITCODE -ne 0) { throw '测试失败' }
