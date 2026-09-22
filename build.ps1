# Gradle-free build for the Jev chat assist APK.
#
#   powershell -ExecutionPolicy Bypass -File build.ps1
#   powershell -ExecutionPolicy Bypass -File build.ps1 -Clean -Install
#
# Requires (see fetch_deps.ps1): app/assets/models/bge-small-zh/, app/jniLibs/arm64-v8a/,
# libs/onnxruntime-classes.jar
#
# Keep this file pure ASCII. Windows PowerShell 5.1 reads .ps1 as ANSI unless it has a
# BOM, and non-ASCII text then breaks the parser.

param(
    [switch]$Install,
    [switch]$Clean,

    # Android SDK root; falls back to ANDROID_HOME then the default per-user location.
    [string]$Sdk = $env:ANDROID_HOME,
    # JDK 17 home.
    [string]$Jdk = $env:JAVA_HOME,

    # Build-tools and platform to compile against.
    [string]$BuildToolsVersion = "34.0.0",
    [string]$PlatformVersion = "android-34",
    [int]$MinSdk = 26,
    [int]$TargetSdk = 34
)

$ErrorActionPreference = 'Stop'

if (-not $Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
if (-not $Jdk) {
    foreach ($candidate in @("C:\Program Files\Java\jdk-17", "C:\Program Files\Eclipse Adoptium\jdk-17*")) {
        $found = Get-Item $candidate -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) { $Jdk = $found.FullName; break }
    }
}
if (-not $Jdk) { throw "JDK 17 not found; pass -Jdk or set JAVA_HOME" }

$Bt         = Join-Path $Sdk "build-tools\$BuildToolsVersion"
$AndroidJar = Join-Path $Sdk "platforms\$PlatformVersion\android.jar"
$Adb        = Join-Path $Sdk "platform-tools\adb.exe"
$Javac      = Join-Path $Jdk "bin\javac.exe"
$Jar        = Join-Path $Jdk "bin\jar.exe"
$Keytool    = Join-Path $Jdk "bin\keytool.exe"

$Root    = $PSScriptRoot
$AppDir  = Join-Path $Root "app"
$Assets  = Join-Path $AppDir "assets"
$JniLibs = Join-Path $AppDir "jniLibs"
$OrtJar  = Join-Path $Root "libs\onnxruntime-classes.jar"
$PyScript = Join-Path $Root "pack_apk.py"
$Build   = Join-Path $Root "build"
$ResZip  = Join-Path $Build "res.zip"
$BaseApk = Join-Path $Build "base.apk"
$GenDir  = Join-Path $Build "gen"
$Classes = Join-Path $Build "classes"
$DexDir  = Join-Path $Build "dex"
$Native  = Join-Path $Build "nativestage"
$Unsig   = Join-Path $Build "unsigned.apk"
$Aligned = Join-Path $Build "app-aligned.apk"
$Signed  = Join-Path $Build "jev-assist.apk"
$Ks      = Join-Path $Root "jev-debug.keystore"

foreach ($tool in @($Javac, $Jar, $Keytool)) {
    if (-not (Test-Path $tool)) { throw "missing tool: $tool" }
}
foreach ($tool in @("$Bt\aapt2.exe", "$Bt\zipalign.exe", "$Bt\lib\d8.jar", "$Bt\lib\apksigner.jar", $AndroidJar)) {
    if (-not (Test-Path $tool)) { throw "missing build component: $tool (check -Sdk / -BuildToolsVersion / -PlatformVersion)" }
}

$Model = Join-Path $Assets "models\bge-small-zh\model_quantized.onnx"
if (-not (Test-Path $Model)) {
    throw "local model missing. Run: powershell -ExecutionPolicy Bypass -File fetch_deps.ps1"
}
if (-not (Test-Path $OrtJar)) {
    throw "onnxruntime-classes.jar missing. Run: powershell -ExecutionPolicy Bypass -File fetch_deps.ps1"
}

if ($Clean -and (Test-Path $Build)) {
    Remove-Item $Build -Recurse -Force
}
foreach ($d in @($Build, $GenDir, $Classes, $DexDir, $Native)) {
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}

Write-Host "[1/7] aapt2 compile resources"
& "$Bt\aapt2.exe" compile --dir (Join-Path $AppDir "res") -o $ResZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "[2/7] aapt2 link -> base.apk + R.java"
# Assets are deliberately NOT linked here: on Windows aapt2 emits backslash entry names,
# which breaks AssetManager.open(). pack_apk.py writes them with forward slashes instead.
& "$Bt\aapt2.exe" link `
    -o $BaseApk `
    -I $AndroidJar `
    --manifest (Join-Path $AppDir "AndroidManifest.xml") `
    -R $ResZip `
    --java $GenDir `
    --min-sdk-version $MinSdk `
    --target-sdk-version $TargetSdk `
    --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[3/7] javac"
$sources = @()
$sources += (Get-ChildItem (Join-Path $AppDir "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += (Get-ChildItem $GenDir -Recurse -Filter *.java | ForEach-Object { $_.FullName })
if ($sources.Count -eq 0) { throw "no java sources found" }

& $Javac -encoding UTF-8 -source 1.8 -target 1.8 -bootclasspath $AndroidJar `
    -cp $OrtJar -nowarn -Xlint:-options -d $Classes $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[4/7] d8 -> classes.dex"
$classFiles = Get-ChildItem $Classes -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& java -cp "$Bt\lib\d8.jar" com.android.tools.r8.D8 `
    --lib $AndroidJar --lib $OrtJar --min-api $MinSdk --output $DexDir $classFiles $OrtJar
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[5/7] package dex + native libs + assets"
New-Item -ItemType Directory -Force -Path "$Native\lib\arm64-v8a" | Out-Null
Copy-Item "$JniLibs\arm64-v8a\*.so" "$Native\lib\arm64-v8a\" -Force
& python $PyScript $BaseApk $Unsig $DexDir $Native $Assets
if ($LASTEXITCODE -ne 0) { throw "packaging dex/libs/assets failed" }

Write-Host "[6/7] zipalign"
& "$Bt\zipalign.exe" -p -f 4 $Unsig $Aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[7/7] sign"
if (-not (Test-Path $Ks)) {
    & $Keytool -genkeypair -v -keystore $Ks -alias jev -keyalg RSA -keysize 2048 `
        -validity 10000 -storepass android -keypass android `
        -dname "CN=Jev Assist, OU=dev, O=jev.local, L=Beijing, ST=Beijing, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keystore generation failed" }
}
& java -jar "$Bt\lib\apksigner.jar" sign `
    --ks $Ks --ks-pass pass:android --key-pass pass:android `
    --v1-signing-enabled true --v2-signing-enabled true `
    --out $Signed $Aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

& java -jar "$Bt\lib\apksigner.jar" verify --print-certs $Signed | Select-Object -First 5

$size = [math]::Round((Get-Item $Signed).Length / 1KB, 1)
Write-Host ""
Write-Host "built: $Signed ($size KB)"

if ($Install) {
    Write-Host ""
    Write-Host "installing to device..."
    & $Adb install -r --no-incremental $Signed
    if ($LASTEXITCODE -ne 0) { throw "adb install failed" }
    Write-Host "installed."
}
