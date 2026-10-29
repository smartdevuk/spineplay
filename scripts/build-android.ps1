param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.tooling\gradle-home'
$env:ANDROID_HOME = Join-Path $projectRoot '.tooling\android-sdk'
$gradleExecutable = Join-Path $projectRoot '.tooling\gradle-8.11.1\bin\gradle.bat'
if (!(Test-Path -LiteralPath $gradleExecutable)) { throw 'Run scripts/bootstrap-android.py first.' }
$buildArguments = @('--no-daemon', '--console=plain', 'testDebugUnitTest', 'lintDebug', 'assembleDebug')
if ($Offline) { $buildArguments = @('--offline') + $buildArguments }
Push-Location $projectRoot
try { & $gradleExecutable @buildArguments; if ($LASTEXITCODE -ne 0) { throw "Android build failed ($LASTEXITCODE)." } }
finally { Pop-Location }
