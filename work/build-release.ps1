param([switch]$SkipResourceShrink)
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
$env:JAVA_TOOL_OPTIONS = '-Duser.home=C:/Users/Elysia/Documents/Codex/2026-09-26/new-chat/work/java-home -Dorg.gradle.native.dir=C:/Users/Elysia/Documents/Codex/2026-09-26/new-chat/work/gradle-native'
$env:GRADLE_RO_DEP_CACHE = 'C:\Users\Elysia\.gradle\caches'
Set-Location 'C:\Users\Elysia\Documents\Codex\2026-09-26\new-chat'
& 'C:\Users\Elysia\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat' -g work/gradle-home '-Pkotlin.compiler.execution.strategy=in-process' '-PdebugKeystorePath=C:/Users/Elysia/.android/debug.keystore' "-PskipResourceShrink=$($SkipResourceShrink.IsPresent.ToString().ToLowerInvariant())" :app:testReleaseUnitTest :app:assembleRelease --offline --no-daemon --no-problems-report --max-workers=1 --stacktrace
exit $LASTEXITCODE
