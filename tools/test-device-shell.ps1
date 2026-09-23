# Runs Android regressions via ADB without installing an extra app or reading app settings.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
$bt = Join-Path $sdk 'build-tools/34.0.0'
$android = Join-Path $sdk 'platforms/android-34/android.jar'
$adb = Join-Path $sdk 'platform-tools/adb.exe'
$out = Join-Path $root 'build/device-shell'
New-Item -ItemType Directory -Force "$out/classes", "$out/dex" | Out-Null
& javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -cp "$android;$root/build/classes" -d "$out/classes" "$PSScriptRoot/device-tests/DeviceRegressionTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Device test compilation failed' }
$classes = @(Get-ChildItem "$out/classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName })
& java -cp "$bt/lib/d8.jar" com.android.tools.r8.D8 --lib $android --classpath "$root/build/classes" --min-api 26 --output "$out/dex" $classes
if ($LASTEXITCODE -ne 0) { throw 'Test dex failed' }
& $adb shell mkdir -p /data/local/tmp/jev-regression/lib
& $adb push "$out/dex/classes.dex" /data/local/tmp/jev-regression/tests.dex
& $adb push "$root/build/jev-assist.apk" /data/local/tmp/jev-regression/app.apk
& $adb push "$root/app/jniLibs/arm64-v8a/libonnxruntime.so" /data/local/tmp/jev-regression/lib/
& $adb push "$root/app/jniLibs/arm64-v8a/libonnxruntime4j_jni.so" /data/local/tmp/jev-regression/lib/
if ($LASTEXITCODE -ne 0) { throw 'Pushing test assets failed' }
& $adb shell 'LD_LIBRARY_PATH=/data/local/tmp/jev-regression/lib CLASSPATH=/data/local/tmp/jev-regression/tests.dex:/data/local/tmp/jev-regression/app.apk app_process /system/bin ai.jev.assist.DeviceRegressionTest /data/local/tmp/jev-regression/app.apk'
if ($LASTEXITCODE -ne 0) { throw 'Android regression tests failed' }
