param(
    [switch]$Offline,
    [switch]$UseExistingDevelopmentKey,
    [string]$SigningFile = (Join-Path $env:LOCALAPPDATA 'Spineplay\signing\release.json')
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ($UseExistingDevelopmentKey) {
    $existingKey = Join-Path $env:USERPROFILE '.android\debug.keystore'
    if (!(Test-Path -LiteralPath $existingKey)) { throw 'Existing development key is missing. Refusing to generate a replacement.' }
    $signing = @{ storeFile=$existingKey; storePassword='android'; keyAlias='androiddebugkey'; keyPassword='android' }
} else {
    if (!(Test-Path -LiteralPath $SigningFile)) { throw 'Private signing settings are missing. See docs/development.md.' }
    $signing = Get-Content -LiteralPath $SigningFile -Raw | ConvertFrom-Json
}
$signingVariables = @('SPINEPLAY_KEYSTORE','SPINEPLAY_STORE_PASSWORD','SPINEPLAY_KEY_ALIAS','SPINEPLAY_KEY_PASSWORD')
$previousValues = @{}
foreach ($name in $signingVariables) { $previousValues[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:SPINEPLAY_KEYSTORE = $signing.storeFile
    $env:SPINEPLAY_STORE_PASSWORD = $signing.storePassword
    $env:SPINEPLAY_KEY_ALIAS = $signing.keyAlias
    $env:SPINEPLAY_KEY_PASSWORD = $signing.keyPassword
    if (!$env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
    $localGradle = Join-Path $projectRoot '.tooling\gradle-8.11.1\bin\gradle.bat'
    if (Test-Path -LiteralPath $localGradle) {
        $env:GRADLE_USER_HOME = Join-Path $projectRoot '.tooling\gradle-home'
        $env:ANDROID_HOME = Join-Path $projectRoot '.tooling\android-sdk'
        $gradle = $localGradle
    } else { $gradle = Join-Path $projectRoot 'gradlew.bat' }
    $buildArguments = @('--no-daemon','--console=plain','testDebugUnitTest','lintRelease','assembleRelease')
    if ($Offline) { $buildArguments = @('--offline') + $buildArguments }
    Push-Location $projectRoot
    try { & $gradle @buildArguments; if ($LASTEXITCODE -ne 0) { throw "Release checks failed ($LASTEXITCODE)." } }
    finally { Pop-Location }
} finally {
    foreach ($name in $signingVariables) { [Environment]::SetEnvironmentVariable($name, $previousValues[$name], 'Process') }
    Remove-Variable signing -ErrorAction SilentlyContinue
}
