param(
    [string[]]$Tasks = @(':app:assembleDebug'),
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$androidRoot = Join-Path $projectRoot 'android'
$userRoot = $env:USERPROFILE
$javaRoot = $env:JAVA_HOME
if (-not $javaRoot) { $javaRoot = 'C:\Program Files\Android\Android Studio\jbr' }
if (-not (Test-Path -LiteralPath (Join-Path $javaRoot 'bin\java.exe'))) {
    throw 'Set JAVA_HOME to Android Studio''s bundled JDK.'
}

$androidUserRoot = Join-Path $androidRoot '.android-home'
New-Item -ItemType Directory -Path $androidUserRoot -Force | Out-Null

# The generated renderer is not tracked. Build it when missing, or when a
# renderer/bridge source is newer, so an APK cannot ship a stale UI.
$rendererIndex = Join-Path $androidRoot 'app\src\main\assets\renderer\index.html'
$rendererAssets = Join-Path $androidRoot 'app\src\main\assets\renderer'
$bridgeSource = Join-Path $projectRoot 'bridge\bridge.js'
$androidBridgeSource = Join-Path $projectRoot 'bridge\android.js'
$bridgeAsset = Join-Path $rendererAssets 'bridge-host\bridge.js'
$needsRenderer = -not (Test-Path -LiteralPath $rendererIndex) -or
    -not (Test-Path -LiteralPath $bridgeAsset)
if (-not $needsRenderer -and (Test-Path -LiteralPath $rendererIndex)) {
    $rendererTime = (Get-Item -LiteralPath $rendererIndex).LastWriteTimeUtc
    $sourceRoot = Join-Path $projectRoot 'recordly-upstream\src'
    if (Test-Path -LiteralPath $sourceRoot) {
        $newestSource = Get-ChildItem -LiteralPath $sourceRoot -Recurse -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
        if ($newestSource -and $newestSource.LastWriteTimeUtc -gt $rendererTime) { $needsRenderer = $true }
    }
    foreach ($source in @($bridgeSource, $androidBridgeSource)) {
        if ((Test-Path -LiteralPath $source) -and
            (Get-Item -LiteralPath $source).LastWriteTimeUtc -gt $rendererTime) {
            $needsRenderer = $true
        }
    }
}
if ($needsRenderer) {
    Write-Host 'Renderer assets are missing or stale; building them first...'
    & node (Join-Path $projectRoot 'scripts\build-renderer.mjs')
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $rendererIndex)) {
        throw 'Could not build the renderer assets. Run node scripts/build-renderer.mjs, then retry.'
    }
}

# Gradle can report assembleDebug up-to-date when only assets changed, leaving
# the previous renderer in the APK. If the asset tree is newer than the APK,
# drop the asset-merge and packaging outputs so the next build re-merges and
# repackages. Kotlin and other incremental outputs are untouched.
$debugApk = Join-Path $androidRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (@($Tasks) -contains ':app:assembleDebug') {
    $newestAsset = Get-ChildItem -LiteralPath $rendererAssets -Recurse -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    $apkTime = if (Test-Path -LiteralPath $debugApk) {
        (Get-Item -LiteralPath $debugApk).LastWriteTimeUtc
    } else {
        $null
    }
    if (-not $apkTime -or ($newestAsset -and $newestAsset.LastWriteTimeUtc -gt $apkTime)) {
        Write-Host 'Renderer assets are newer than the debug APK; forcing repackage...'
        Remove-Item -Recurse -Force (Join-Path $androidRoot 'app\build\intermediates\assets\debug\mergeDebugAssets') -ErrorAction SilentlyContinue
        Remove-Item -Recurse -Force (Join-Path $androidRoot 'app\build\intermediates\apk\debug') -ErrorAction SilentlyContinue
        Remove-Item -Force $debugApk -ErrorAction SilentlyContinue
    }
}

$signingRoot = Join-Path $androidRoot '.build-signing'
New-Item -ItemType Directory -Path $signingRoot -Force | Out-Null
$debugKey = Join-Path $signingRoot 'recordly-debug.keystore'
$existingDebugKey = Join-Path $userRoot '.android\debug.keystore'
if (-not (Test-Path -LiteralPath $debugKey)) {
    if (Test-Path -LiteralPath $existingDebugKey) {
        # Preserve the existing debug signature, so APK updates remain installable.
        Copy-Item -LiteralPath $existingDebugKey -Destination $debugKey
    } else {
        & (Join-Path $javaRoot 'bin\keytool.exe') -genkeypair -keystore $debugKey `
            -storepass android -keypass android -alias androiddebugkey `
            -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000
        if ($LASTEXITCODE -ne 0) { throw 'Could not generate the local debug signing key.' }
    }
}

$wrapperProperties = Get-Content -LiteralPath (Join-Path $androidRoot 'gradle\wrapper\gradle-wrapper.properties')
$versionLine = $wrapperProperties | Where-Object { $_ -match 'gradle-([\d.]+)-bin\.zip' }
$gradleVersion = [regex]::Match($versionLine, 'gradle-([\d.]+)-bin\.zip').Groups[1].Value
$gradlePattern = Join-Path $userRoot ".gradle\wrapper\dists\gradle-$gradleVersion-bin\*\gradle-$gradleVersion\bin\gradle.bat"
$gradleExecutable = Get-ChildItem -Path $gradlePattern -File -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $gradleExecutable) { $gradleExecutable = Join-Path $androidRoot 'gradlew.bat' }

$savedEnvironment = @{}
foreach ($name in @('JAVA_HOME', 'JAVA_TOOL_OPTIONS', 'GRADLE_USER_HOME', 'GRADLE_RO_DEP_CACHE', 'ANDROID_USER_HOME')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    $env:JAVA_HOME = $javaRoot
    $env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS -Duser.home=$userRoot".Trim()
    $env:GRADLE_USER_HOME = Join-Path $androidRoot '.gradle-home'
    $existingCache = Join-Path $userRoot '.gradle\caches'
    if (Test-Path -LiteralPath $existingCache) { $env:GRADLE_RO_DEP_CACHE = $existingCache }
    $env:ANDROID_USER_HOME = Join-Path $androidRoot '.android-home'
    $arguments = @($Tasks) + @(
        '--console=plain', '--no-daemon', '--no-problems-report',
        '-Pkotlin.compiler.execution.strategy=in-process',
        "-PrecordlyDebugKeystore=$debugKey"
    )
    if ($Offline) { $arguments += '--offline' }
    Push-Location $androidRoot
    try {
        & $gradleExecutable @arguments
        $buildExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
} finally {
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
}
exit $buildExitCode
