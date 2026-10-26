$ErrorActionPreference = 'Stop'
$radarApk = Join-Path $PSScriptRoot '../app/build/outputs/apk/debug/app-debug.apk'
$radarTools = 'C:/Users/Elysia/AppData/Local/Android/build-tools/37.0.0'
$radarBadging = (& "$radarTools/aapt2.exe" dump badging $radarApk) -join "`n"
if ($LASTEXITCODE -ne 0 -or $radarBadging -notmatch 'application-debuggable' -or $radarBadging -notmatch '1.28-debug-back-20261019') { throw 'Unexpected debug package' }
$radarManifest = (& "$radarTools/aapt2.exe" dump xmltree $radarApk --file AndroidManifest.xml) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Manifest inspection failed' }
if ($radarManifest -match 'android:enableOnBackInvokedCallback[^\n]*(?:=true|0xffffffff)') { throw 'Predictive back must remain disabled' }
if ($radarManifest -notmatch '(?s)MainActivity.*?enableOnBackInvokedCallback[^\n]*(?:=false|0x0\b)') { throw 'MainActivity back compatibility flag missing' }
& 'C:/Program Files/Java/jdk-21.0.10/bin/java.exe' -jar "$radarTools/lib/apksigner.jar" verify --verbose --print-certs $radarApk
if ($LASTEXITCODE -ne 0) { throw 'Signature verification failed' }
& "$radarTools/zipalign.exe" -c 4 $radarApk
if ($LASTEXITCODE -ne 0) { throw 'Alignment verification failed' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$radarArchive = [System.IO.Compression.ZipFile]::OpenRead($radarApk)
try {
    $radarDex = [System.Text.StringBuilder]::new()
    foreach ($radarEntry in $radarArchive.Entries | Where-Object FullName -Match '^classes\d*\.dex$') {
        $radarStream = $radarEntry.Open()
        $radarBuffer = [System.IO.MemoryStream]::new()
        try { $radarStream.CopyTo($radarBuffer); [void]$radarDex.Append([System.Text.Encoding]::UTF8.GetString($radarBuffer.ToArray())) }
        finally { $radarStream.Dispose(); $radarBuffer.Dispose() }
    }
    foreach ($radarMarker in @('DebugNotificationTestKt', 'PendingAlertDeliveryWorker', 'probeStartedAt', 'BackgroundProbeTiming', 'ScreenBackBinding', 'activity_back_received')) {
        if (!$radarDex.ToString().Contains($radarMarker)) { throw "Missing debug feature: $radarMarker" }
    }
} finally { $radarArchive.Dispose() }
Get-FileHash -LiteralPath $radarApk -Algorithm SHA256
Write-Output 'Debug artifact validation passed; device behavior not tested.'
