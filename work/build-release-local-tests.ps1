param([switch]$TestsOnly, [switch]$DebugApk)
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21.0.10'
$env:JAVA_TOOL_OPTIONS = '-Duser.home=C:/Users/Elysia/Documents/Codex/2026-09-26/new-chat/work/java-home -Dorg.gradle.native.dir=C:/Users/Elysia/Documents/Codex/2026-09-26/new-chat/work/gradle-native'
$env:GRADLE_RO_DEP_CACHE = 'C:/Users/Elysia/.gradle/caches'
$radarGradle = 'C:/Users/Elysia/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13'
Set-Location 'C:/Users/Elysia/Documents/Codex/2026-09-26/new-chat'
$packagingTasks = @(if (!$TestsOnly) { if ($DebugApk) { ':app:assembleDebug' } else { ':app:assembleRelease' } })
& 'C:/Program Files/Java/jdk-21.0.10/bin/java.exe' '-Xmx2048m' '-Dfile.encoding=UTF-8' '-Duser.country=TW' '-Duser.language=zh' '-Duser.variant=' "-javaagent:$radarGradle/lib/agents/gradle-instrumentation-agent-8.13.jar" -cp "$radarGradle/lib/gradle-gradle-cli-main-8.13.jar" org.gradle.launcher.GradleMain -g work/gradle-home -I work/local-junit.gradle '-Pkotlin.compiler.execution.strategy=in-process' '-PdebugKeystorePath=C:/Users/Elysia/.android/debug.keystore' :app:testDebugLocalUnitTest :app:testReleaseLocalUnitTest @packagingTasks --offline --no-daemon --no-problems-report --max-workers=1
exit $LASTEXITCODE
