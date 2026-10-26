$ErrorActionPreference = 'Stop'
$env:TEMP = Join-Path $PSScriptRoot 'runtime-temp'
$env:TMP = $env:TEMP
$env:JAVA_TOOL_OPTIONS = '-Duser.home=C:/Users/Elysia/Documents/Codex/2026-09-26/new-chat/work/java-home -Djava.io.tmpdir=C:/Users/Elysia/Documents/Codex/2026-09-26/new-chat/work/runtime-temp'
$projectDir = Split-Path $PSScriptRoot -Parent
$apk = Join-Path $projectDir 'app/build/outputs/apk/release/app-release.apk'
$sdkTools = 'C:/Users/Elysia/AppData/Local/Android/build-tools/37.0.0'

$badging = & "$sdkTools/aapt2.exe" dump badging $apk
if ($LASTEXITCODE -ne 0) { throw 'APK badging check failed' }
if (($badging -join "`n") -notmatch "name='com.aykkdk.shop'") { throw 'Unexpected package' }
if (($badging -join "`n") -match 'application-debuggable') { throw 'Debuggable APK is not a release artifact' }
$manifest = & "$sdkTools/aapt2.exe" dump xmltree $apk --file AndroidManifest.xml
if ($LASTEXITCODE -ne 0) { throw 'Manifest check failed' }
if (($manifest -join "`n") -match 'android:debuggable.*(?:=true|0xffffffff)') { throw 'Debug manifest flag found' }
if (($manifest -join "`n") -match 'android:screenOrientation') { throw 'Orientation must remain unrestricted' }
if (($manifest -join "`n") -notmatch 'android:configChanges.*0x0*d80') { throw 'Resize/rotation configuration handling missing' }
if (($manifest -join "`n") -notmatch 'android:resizeableActivity.*(?:=true|0xffffffff)') { throw 'Resizable activity flag missing' }

$signature = & "$sdkTools/apksigner.bat" verify --verbose --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
$signatureText = $signature -join "`n"
$expectedCertificate = '2555816082595d99a8f294ac2539300e0d7cad6f50040a37ec19d46c20fdb5cf'
if ($signatureText -notmatch "certificate SHA-256 digest:\s*$expectedCertificate") { throw 'Certificate changed; update compatibility lost' }
& "$sdkTools/zipalign.exe" -c 4 $apk
if ($LASTEXITCODE -ne 0) { throw 'APK alignment check failed' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($apk)
$assetsVerified = 0
try {
    if ($archive.GetEntry('assets/marketplace-html-debug.js')) { throw 'Debug HTML capture asset leaked into release' }
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
        if ($dexText -match 'early-4s|HTML 診斷編號|marketplace-html-debug\.js') { throw 'Debug HTML diagnostics strings leaked into release' }
    }
} finally { $archive.Dispose() }

$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$name = 'ShopRadar-1.27-tablet-public-reader-release-20261004-' + $hash.Substring(0, 8)
$destination = Join-Path $projectDir "outputs/$name.apk"
Copy-Item -LiteralPath $apk -Destination $destination
Copy-Item -LiteralPath (Join-Path $projectDir 'app/build/outputs/mapping/release/mapping.txt') -Destination (Join-Path $projectDir "outputs/$name-mapping.txt")
[pscustomobject]@{
    Apk = $destination
    Bytes = (Get-Item -LiteralPath $destination).Length
    SHA256 = $hash
    AssetsVerified = $assetsVerified
    CertificateSHA256 = $expectedCertificate
    Badging = $badging | Select-Object -First 7
    Signature = $signature
} | ConvertTo-Json -Depth 3
