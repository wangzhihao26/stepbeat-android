param(
    [string]$SdkPath = $env:ANDROID_HOME,
    [string]$JdkPath = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
if (-not $SdkPath) {
    $candidates = @("$env:LOCALAPPDATA\Android\Sdk")
    $candidates += @(Get-ChildItem -Path 'C:\Program Files\Unity\Hub\Editor\*\Editor\Data\PlaybackEngines\AndroidPlayer\SDK' -Directory -ErrorAction SilentlyContinue | ForEach-Object FullName)
    $SdkPath = $candidates | Where-Object { Test-Path -LiteralPath "$_\build-tools" } | Select-Object -Last 1
}
if (-not $JdkPath) {
    $bundledJdk = if ($SdkPath) { Join-Path (Split-Path -Parent $SdkPath) 'OpenJDK' } else { '' }
    if ($bundledJdk -and (Test-Path -LiteralPath "$bundledJdk\bin\javac.exe")) { $JdkPath = $bundledJdk }
    else { $JdkPath = Get-ChildItem -Path 'C:\Program Files\Java\jdk-*' -Directory -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1 -ExpandProperty FullName }
}
if (-not $SdkPath -or -not $JdkPath) { throw '请传入 -SdkPath 和 -JdkPath（离线构建推荐 JDK 11 或 17）。' }
$sdk = (Resolve-Path -LiteralPath $SdkPath).Path
$jdk = (Resolve-Path -LiteralPath $JdkPath).Path
$buildTools = Get-ChildItem -LiteralPath "$sdk\build-tools" -Directory | Where-Object Name -Match '^\d+\.\d+\.\d+$' | Sort-Object { [version]$_.Name } | Select-Object -Last 1 -ExpandProperty FullName
$platform = Get-ChildItem -LiteralPath "$sdk\platforms" -Directory | Where-Object Name -Match '^android-\d+$' | Sort-Object { [int]($_.Name -replace 'android-', '') } | Select-Object -Last 1 -ExpandProperty FullName
if (-not $buildTools -or -not $platform) { throw 'SDK 缺少 build-tools 或 Android platform。' }
$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"
$out = Join-Path $projectRoot 'app\build\offline'
$generated = Join-Path $out 'generated'
$classes = Join-Path $out 'classes'
$dex = Join-Path $out 'dex'
$toolsDir = Join-Path $projectRoot '.tools'
@($out,$generated,$classes,$dex,$toolsDir) | ForEach-Object { New-Item -ItemType Directory -Path $_ -Force | Out-Null }
function Invoke-Checked([string]$Command, [string[]]$Arguments) {
    # Old aapt2/zipalign on Windows cannot open non-ASCII absolute paths.
    $Arguments = @($Arguments | ForEach-Object {
        if ($_.StartsWith($projectRoot + '\')) { '.' + $_.Substring($projectRoot.Length) } else { $_ }
    })
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "命令执行失败 ($LASTEXITCODE): $Command" }
}
$main = Join-Path $projectRoot 'app\src\main'
$manifest = Get-Content -LiteralPath "$main\AndroidManifest.xml" -Raw
# The manifest references an integer resource for the API 34 specialUse value.
if ($manifest -notmatch 'package="com.stepbeat.app"') { $manifest = $manifest.Replace('<manifest ', '<manifest package="com.stepbeat.app" ') }
[IO.File]::WriteAllText("$out\AndroidManifest.xml", $manifest, [Text.UTF8Encoding]::new($false))
Invoke-Checked "$buildTools\aapt2.exe" @('compile','--dir',"$main\res",'-o',"$out\resources.zip")
Invoke-Checked "$buildTools\aapt2.exe" @('link','-o',"$out\base.apk",'-I',"$platform\android.jar",'--manifest',"$out\AndroidManifest.xml",'--java',$generated,'--min-sdk-version','26','--target-sdk-version','35','--version-code','1','--version-name','1.0.0','--debug-mode',"$out\resources.zip")
$sources = @(Get-ChildItem -LiteralPath "$main\java",$generated -Filter '*.java' -Recurse | ForEach-Object FullName)
Invoke-Checked "$jdk\bin\javac.exe" (@('--release','8','-encoding','UTF-8','-classpath',"$platform\android.jar",'-d',$classes) + $sources)
Invoke-Checked "$jdk\bin\jar.exe" @('cf',"$out\classes.jar",'-C',$classes,'.')
Invoke-Checked "$jdk\bin\java.exe" @('-cp',"$buildTools\lib\d8.jar",'com.android.tools.r8.D8','--lib',"$platform\android.jar",'--min-api','26','--output',$dex,"$out\classes.jar")
Copy-Item -LiteralPath "$out\base.apk" -Destination "$out\unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$out\unsigned.apk", [IO.Compression.ZipArchiveMode]::Update)
try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$dex\classes.dex",'classes.dex') | Out-Null } finally { $zip.Dispose() }
Invoke-Checked "$buildTools\zipalign.exe" @('-f','4',"$out\unsigned.apk","$out\aligned.apk")
$key = Join-Path $toolsDir 'debug.keystore'
if (-not (Test-Path -LiteralPath $key)) {
    Invoke-Checked "$jdk\bin\keytool.exe" @('-genkeypair','-keystore',$key,'-storepass','android','-keypass','android','-alias','androiddebugkey','-dname','CN=Android Debug,O=Android,C=US','-keyalg','RSA','-keysize','2048','-validity','10000')
}
$apkDir = Join-Path $projectRoot 'dist'
New-Item -ItemType Directory -Path $apkDir -Force | Out-Null
$apk = Join-Path $apkDir 'stepbeat-debug.apk'
Invoke-Checked "$jdk\bin\java.exe" @('-jar',"$buildTools\lib\apksigner.jar",'sign','--ks',$key,'--ks-pass','pass:android','--key-pass','pass:android','--out',$apk,"$out\aligned.apk")
Invoke-Checked "$jdk\bin\java.exe" @('-jar',"$buildTools\lib\apksigner.jar",'verify','--verbose',$apk)
Write-Output "APK: $apk"
} finally { Pop-Location }
