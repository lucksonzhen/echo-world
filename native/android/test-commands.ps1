param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
if (-not $JavaHome) { throw 'Pass -JavaHome pointing to JDK 17.' }
$testClasses = Join-Path $PSScriptRoot 'app\build\command-tests'
New-Item -ItemType Directory -Force -Path $testClasses | Out-Null
$javaSources = @('ScreenCommand.java', 'VoiceCommandRouter.java', 'VoiceWakeController.java', 'PlaybackPausePolicy.java', 'ImageWatchPolicy.java', 'DirectApiConfig.java', 'SetupFlow.java') | ForEach-Object { Join-Path $PSScriptRoot ('app\src\main\java\com\tingjian\assistant\' + $_) }
$javaTests = @('ScreenCommandTest.java', 'VoiceWakeControllerTest.java', 'MediaPausePolicyTest.java', 'ImageWatchPolicyTest.java', 'DirectApiConfigTest.java', 'SetupFlowTest.java') | ForEach-Object { Join-Path $PSScriptRoot ('tests\' + $_) }
& (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 -d $testClasses $javaSources $javaTests
if ($LASTEXITCODE -ne 0) { throw 'Command test compilation failed.' }
& (Join-Path $JavaHome 'bin\java.exe') -cp $testClasses com.tingjian.assistant.ScreenCommandTest
if ($LASTEXITCODE -ne 0) { throw 'Command tests failed.' }
& (Join-Path $JavaHome 'bin\java.exe') -cp $testClasses com.tingjian.assistant.VoiceWakeControllerTest
if ($LASTEXITCODE -ne 0) { throw 'Wake controller tests failed.' }
& (Join-Path $JavaHome 'bin\java.exe') -cp $testClasses com.tingjian.assistant.MediaPausePolicyTest
if ($LASTEXITCODE -ne 0) { throw 'Media pause policy tests failed.' }
& (Join-Path $JavaHome 'bin\java.exe') -cp $testClasses com.tingjian.assistant.ImageWatchPolicyTest
if ($LASTEXITCODE -ne 0) { throw 'Image watch policy tests failed.' }
& (Join-Path $JavaHome 'bin\java.exe') -cp $testClasses com.tingjian.assistant.DirectApiConfigTest
if ($LASTEXITCODE -ne 0) { throw 'Direct API configuration tests failed.' }
& (Join-Path $JavaHome 'bin\java.exe') -cp $testClasses com.tingjian.assistant.SetupFlowTest
if ($LASTEXITCODE -ne 0) { throw 'Setup flow tests failed.' }
