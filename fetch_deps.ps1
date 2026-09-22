# Fetch the third-party binaries this project embeds but does not vendor:
#   * bge-small-zh-v1.5 (ONNX, int8 quantized) + vocabulary
#   * ONNX Runtime Android 1.20 (Java bindings + arm64-v8a native libs)
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File fetch_deps.ps1
#   powershell -ExecutionPolicy Bypass -File fetch_deps.ps1 -Proxy http://127.0.0.1:7897
#
# Keep this file pure ASCII (Windows PowerShell 5.1 reads .ps1 as ANSI without a BOM).

param(
    [string]$Proxy = "",
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$Root     = $PSScriptRoot
$ModelDir = Join-Path $Root "app\assets\models\bge-small-zh"
$JniDir   = Join-Path $Root "app\jniLibs\arm64-v8a"
$LibsDir  = Join-Path $Root "libs"
$Tmp      = Join-Path $Root "build\fetch"

foreach ($d in @($ModelDir, $JniDir, $LibsDir, $Tmp)) {
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}

if ($Proxy) {
    $env:HTTPS_PROXY = $Proxy
    $env:HTTP_PROXY  = $Proxy
    Write-Host "using proxy $Proxy"
}

function Get-File($url, $dest, $minBytes) {
    if ((Test-Path $dest) -and -not $Force) {
        $size = (Get-Item $dest).Length
        if ($size -ge $minBytes) {
            Write-Host ("  skip   {0} ({1:N2} MB)" -f (Split-Path $dest -Leaf), ($size / 1MB))
            return
        }
    }
    Write-Host ("  fetch  {0}" -f (Split-Path $dest -Leaf))
    & curl.exe -sL --fail --max-time 900 -o $dest $url
    if ($LASTEXITCODE -ne 0) { throw "download failed: $url" }
    $size = (Get-Item $dest).Length
    if ($size -lt $minBytes) { throw "downloaded file too small: $dest ($size bytes)" }
    Write-Host ("  done   {0:N2} MB" -f ($size / 1MB))
}

Write-Host "== bge-small-zh-v1.5 (ONNX) =="
# Xenova's export is used because it ships a ready int8-quantized ONNX graph plus the
# plain vocab.txt that BertTokenizer reads. tokenizer_config.json must come along too:
# without it HuggingFace defaults do_lower_case to true, which contradicts the official
# BAAI config (false) and makes cross-checking token ids report false differences.
$hfBase = "https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/main"
Get-File "$hfBase/onnx/model_quantized.onnx" (Join-Path $ModelDir "model_quantized.onnx") 20000000
Get-File "$hfBase/vocab.txt"                 (Join-Path $ModelDir "vocab.txt")                 90000
Get-File "$hfBase/config.json"               (Join-Path $ModelDir "config.json")                 500
Get-File "$hfBase/tokenizer.json"            (Join-Path $ModelDir "tokenizer.json")            100000
Get-File "$hfBase/tokenizer_config.json"     (Join-Path $ModelDir "tokenizer_config.json")       100

Write-Host "== onnxruntime-android 1.20.0 =="
$aar = Join-Path $Tmp "onnxruntime-android-1.20.0.aar"
Get-File "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.20.0/onnxruntime-android-1.20.0.aar" $aar 20000000

$extract = Join-Path $Tmp "aar"
if (Test-Path $extract) { Remove-Item $extract -Recurse -Force }
# Expand-Archive refuses the .aar extension, so hand it a .zip copy.
$zipCopy = Join-Path $Tmp "ort.zip"
Copy-Item $aar $zipCopy -Force
Expand-Archive -Path $zipCopy -DestinationPath $extract -Force

Copy-Item (Join-Path $extract "jni\arm64-v8a\libonnxruntime.so")      $JniDir -Force
Copy-Item (Join-Path $extract "jni\arm64-v8a\libonnxruntime4j_jni.so") $JniDir -Force
Copy-Item (Join-Path $extract "classes.jar") (Join-Path $LibsDir "onnxruntime-classes.jar") -Force

Write-Host ""
Write-Host "ready:"
Get-ChildItem $ModelDir | ForEach-Object { Write-Host ("  {0,-26} {1,10:N2} MB" -f $_.Name, ($_.Length / 1MB)) }
Get-ChildItem $JniDir   | ForEach-Object { Write-Host ("  {0,-26} {1,10:N2} MB" -f $_.Name, ($_.Length / 1MB)) }
Write-Host ("  {0,-26} {1,10:N2} MB" -f "onnxruntime-classes.jar", ((Get-Item (Join-Path $LibsDir "onnxruntime-classes.jar")).Length / 1MB))
Write-Host ""
Write-Host "now run: powershell -ExecutionPolicy Bypass -File build.ps1 -Clean -Install"
