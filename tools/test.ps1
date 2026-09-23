$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$out = Join-Path $root 'build/tests'
New-Item -ItemType Directory -Force $out | Out-Null
$sources = @((Join-Path $root 'app/src/ai/jev/assist/BertTokenizer.java'),
    (Join-Path $root 'app/src/ai/jev/assist/CaptureSnapshot.java'),
    (Join-Path $root 'app/src/ai/jev/assist/HomeUiState.java'),
    (Join-Path $PSScriptRoot 'ContextRegressionTest.java'),
    (Join-Path $PSScriptRoot 'SnapshotRegressionTest.java'),
    (Join-Path $PSScriptRoot 'MainUiStateRegressionTest.java'))
& javac -encoding UTF-8 -d $out $sources
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
& java -cp $out ai.jev.assist.ContextRegressionTest
if ($LASTEXITCODE -ne 0) { throw 'Context regression tests failed' }
& java -cp $out ai.jev.assist.SnapshotRegressionTest
if ($LASTEXITCODE -ne 0) { throw 'Snapshot regression tests failed' }
& java -cp $out ai.jev.assist.MainUiStateRegressionTest
if ($LASTEXITCODE -ne 0) { throw 'Main UI state regression tests failed' }
