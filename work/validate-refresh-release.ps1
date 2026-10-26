$ErrorActionPreference = 'Stop'
$env:TEMP = Join-Path $PSScriptRoot 'runtime-temp'
$env:TMP = $env:TEMP
$env:JAVA_TOOL_OPTIONS = '-Duser.home=C:/Users/Elysia/Documents/Codex/2026-09-26/new-chat/work/java-home -Djava.io.tmpdir=C:/Users/Elysia/Documents/Codex/2026-09-26/new-chat/work/runtime-temp'
$projectDir = Split-Path $PSScriptRoot -Parent
$apk = Join-Path $projectDir 'app/build/outputs/apk/release/app-release.apk'
$sdkTools = 'C:/Users/Elysia/AppData/Local/Android/build-tools/37.0.0'
$mapping = Join-Path $projectDir 'app/build/outputs/mapping/release/mapping.txt'

$badging = & "$sdkTools/aapt2.exe" dump badging $apk
if ($LASTEXITCODE -ne 0) { throw 'APK badging check failed' }
$badgingText = $badging -join "`n"
if ($badgingText -notmatch "name='com.aykkdk.shop'.*versionCode='29'.*versionName='1.28'") { throw 'Unexpected package/version' }
if ($badgingText -match 'application-debuggable') { throw 'Debuggable APK is not a release artifact' }
$manifest = & "$sdkTools/aapt2.exe" dump xmltree $apk --file AndroidManifest.xml
if ($LASTEXITCODE -ne 0) { throw 'Manifest check failed' }
$manifestText = $manifest -join "`n"
if ($manifestText -match 'android:debuggable.*(?:=true|0xffffffff)') { throw 'Debug manifest flag found' }
if ($manifestText -match 'android:enableOnBackInvokedCallback.*(?:=true|0xffffffff)') { throw 'Predictive back must remain disabled' }
if ($manifestText -notmatch 'android:enableOnBackInvokedCallback.*(?:=false|0x0\b)') { throw 'Traditional back compatibility flag missing' }
if ($manifestText -notmatch 'android.permission.ACCESS_NETWORK_STATE') { throw 'Network-state permission missing' }
if ($manifestText -match 'android:screenOrientation') { throw 'Orientation must remain unrestricted' }
if ($manifestText -notmatch 'android:configChanges.*0x0*d80') { throw 'Resize/rotation configuration handling missing' }
if ($manifestText -notmatch 'android:resizeableActivity.*(?:=true|0xffffffff)') { throw 'Resizable activity flag missing' }

$signature = & 'C:/Program Files/Java/jdk-21.0.10/bin/java.exe' -jar "$sdkTools/lib/apksigner.jar" verify --verbose --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
$signatureText = $signature -join "`n"
$expectedCertificate = '2555816082595d99a8f294ac2539300e0d7cad6f50040a37ec19d46c20fdb5cf'
if ($signatureText -notmatch "certificate SHA-256 digest:\s*$expectedCertificate") { throw 'Certificate changed; update compatibility lost' }
& "$sdkTools/zipalign.exe" -c 4 $apk
if ($LASTEXITCODE -ne 0) { throw 'APK alignment check failed' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($apk)
$assetsVerified = 0
$allDex = [System.Text.StringBuilder]::new()
try {
    if ($archive.GetEntry('assets/marketplace-html-debug.js')) { throw 'Debug HTML capture asset leaked into release' }
    foreach ($oldAsset in @('shopee-pre-navigation.js', 'shopee-public-reader.js', 'shopee-language.js')) {
        if ($archive.GetEntry('assets/' + $oldAsset)) { throw "Removed Shopee bypass asset leaked into release: $oldAsset" }
    }
    foreach ($source in Get-ChildItem (Join-Path $projectDir 'app/src/main/assets') -File) {
        $entry = $archive.GetEntry('assets/' + $source.Name)
        if (!$entry) { throw "Missing asset: $($source.Name)" }
        $stream = $entry.Open()
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try { $digest = [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
        finally { $stream.Dispose(); $sha.Dispose() }
        if ($digest -ne (Get-FileHash -LiteralPath $source.FullName -Algorithm SHA256).Hash.ToLowerInvariant()) {
            throw "Packaged asset differs from current source: $($source.Name)"
        }
        $assetsVerified++
    }
    foreach ($entry in $archive.Entries | Where-Object { $_.FullName -match '^classes\d*\.dex$' }) {
        $stream = $entry.Open()
        $buffer = [System.IO.MemoryStream]::new()
        try { $stream.CopyTo($buffer); $dexText = [System.Text.Encoding]::UTF8.GetString($buffer.ToArray()) }
        finally { $stream.Dispose(); $buffer.Dispose() }
        if ($dexText -match 'early-4s|HTML \u8a3a\u65b7\u7de8\u865f|marketplace-html-debug\.js') { throw 'Debug HTML diagnostics strings leaked into release' }
        [void]$allDex.Append($dexText)
    }
} finally { $archive.Dispose() }
$dexStrings = $allDex.ToString()
if (!$dexStrings.Contains('activity_back_received')) { throw 'Current Activity-owned back callback missing' }
if ($dexStrings.Contains('15 分鐘背景通知測試（Debug）')) { throw 'Debug notification menu leaked into release' }
foreach ($requiredPattern in @('Lcom/aykkdk/shop/AutomaticRefreshWorker;', 'radar-automatic-refresh', 'automatic_refresh_v1', 'sleepEnabled', '\u5237\u65b0\u8a02\u95b1', '\u5237\u65b0\u7af6\u62cd', '\u81ea\u52d5\u5237\u65b0', '\u7761\u7720\u6a21\u5f0f')) {
    $required = [System.Text.RegularExpressions.Regex]::Unescape($requiredPattern)
    if (!$dexStrings.Contains($required)) { throw "Missing current automatic-refresh implementation in packaged DEX: $required" }
}
$mappingText = [System.IO.File]::ReadAllText($mapping)
if ($mappingText -notmatch '(?m)^com\.example\.shopradar\.ScreenBackBinding -> ') {
    throw 'Current back mapping missing from release mapping'
}
if ($mappingText -notmatch '(?m)^com\.example\.shopradar\.PendingAlertDeliveryWorker -> com\.example\.shopradar\.PendingAlertDeliveryWorker:') {
    throw 'Pending alert delivery worker name was not retained'
}
if ($mappingText -notmatch '(?m)^com\.example\.shopradar\.AutomaticRefreshWorker -> com\.example\.shopradar\.AutomaticRefreshWorker:') {
    throw 'Worker class name was not retained for persisted WorkManager jobs'
}

foreach ($variant in @('Debug', 'Release')) {
    $results = Join-Path $projectDir "work/search-streaming-unit-results/${variant}"
    $reports = @(Get-ChildItem -LiteralPath $results -Filter '*.xml' -File)
    if ($reports.Count -eq 0) { throw "Missing current $variant JUnit reports" }
    foreach ($result in $reports) {
        [xml]$suite = [System.IO.File]::ReadAllText($result.FullName)
        if ([int]$suite.testsuite.failures -ne 0 -or [int]$suite.testsuite.errors -ne 0) { throw "Unit test failure: $($result.Name)" }
    }
}
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$name = 'ShopRadar-1.28-release-' + (Get-Date -Format 'yyyyMMdd') + '-' + $hash.Substring(0, 8)
$destination = Join-Path $projectDir "outputs/$name.apk"
Copy-Item -LiteralPath $apk -Destination $destination
Copy-Item -LiteralPath $mapping -Destination (Join-Path $projectDir "outputs/$name-mapping.txt")
[pscustomobject]@{
    Apk = $destination
    Bytes = (Get-Item -LiteralPath $destination).Length
    SHA256 = $hash
    AssetsVerified = $assetsVerified
    CertificateSHA256 = $expectedCertificate
    Badging = $badging | Select-Object -First 7
    Signature = $signature
} | ConvertTo-Json -Depth 3
