param([string]$SdkRoot = $env:ANDROID_HOME, [string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
if ($SdkRoot) { $env:ANDROID_HOME = $SdkRoot }
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 17, or pass -JavaHome.' }
# Native Vosk/JNA libraries must be packaged by Gradle. The former manual SDK build is obsolete.
& node (Join-Path $PSScriptRoot 'prepare-voice-model.mjs')
if ($LASTEXITCODE -ne 0) { throw 'Unable to prepare the bundled voice model.' }
& (Join-Path $PSScriptRoot 'gradlew.bat') -p $PSScriptRoot --no-daemon :app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw 'Android Gradle build failed.' }
Write-Output (Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk')