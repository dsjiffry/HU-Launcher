<#
  PC side of HU Launcher's "Let PC decide" link. Windows PowerShell 5.1+.

  .\hu.ps1 find                          -> scans your local /24 network(s) for the head unit
  .\hu.ps1 pull -Pin 123456              -> saves every installed app to apps.json
  .\hu.ps1 push -Pin 123456              -> sends verdicts.json to the unit
  .\hu.ps1 push -Pin 123456 -Replace     -> same, but wipes earlier PC verdicts first

  .\hu.ps1 install app.apk -Pin 123456   -> uploads and installs an APK (also HU Launcher itself)
  .\hu.ps1 install bt -Pin 123456        -> installs the newest APK received over Bluetooth
  .\hu.ps1 keep [add|remove <pkg...>]    -> "keep stopped" list for apps that can't be disabled

  -Ip <address> skips the network scan. verdicts.json looks like:
    { "com.android.browser": { "v": "bloat", "why": "Old insecure browser" },
      "com.android.music":   { "v": "maybe", "why": "Only if you play USB music" } }
  v is bloat | maybe | keep.
#>
param(
    [Parameter(Position = 0)][ValidateSet('find', 'pull', 'push', 'verdicts', 'remote', 'shot', 'tap', 'swipe', 'key', 'text', 'launch', 'net', 'allow', 'block', 'stats', 'forcestop', 'disable', 'install', 'keep')][string]$Cmd = 'find',
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)][string[]]$Args2,
    [string]$Ip,
    [string]$Pin,
    [string]$File,
    [int]$Port = 8765,
    [switch]$Replace
)
$ErrorActionPreference = 'Stop'

function Find-Unit {
    $nets = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -notmatch '^(127\.|169\.254\.)' } |
        ForEach-Object { ($_.IPAddress -split '\.')[0..2] -join '.' } | Sort-Object -Unique
    $found = @()
    foreach ($net in $nets) {
        Write-Host "Scanning $net.0/24 on port $Port ..."
        $pending = @()
        foreach ($h in 1..254) {
            $c = New-Object System.Net.Sockets.TcpClient
            $pending += [pscustomobject]@{ Ip = "$net.$h"; Client = $c; Task = $c.ConnectAsync("$net.$h", $Port) }
        }
        try { [System.Threading.Tasks.Task]::WaitAll(($pending | ForEach-Object Task), 1500) | Out-Null } catch { }
        foreach ($p in $pending) {
            if ($p.Task.Status -eq 'RanToCompletion' -and $p.Client.Connected) {
                try {
                    $r = Invoke-RestMethod -Uri "http://$($p.Ip):$Port/ping" -TimeoutSec 3
                    if ($r.app -eq 'hulauncher') {
                        Write-Host "  found $($p.Ip)" -ForegroundColor Green
                        $found += $p.Ip
                    }
                } catch { }
            }
            $p.Client.Dispose()
        }
    }
    return , $found
}

function Get-Base {
    if ($Ip) { return "http://${Ip}:$Port" }
    $units = Find-Unit
    if ($units.Count -eq 0) { throw "No head unit found. On the unit open All apps > Debloat > Let PC decide, and make sure both are on the same Wi-Fi. Or pass -Ip." }
    if ($units.Count -gt 1) { throw "Several units found ($($units -join ', ')); pass -Ip." }
    return "http://$($units[0]):$Port"
}

function Need-Pin { if (-not $Pin) { throw "Pass -Pin <the 6 digits shown on the unit>" } }

switch ($Cmd) {
    'find' { $u = Find-Unit; if ($u.Count -eq 0) { Write-Host 'Nothing found.' -ForegroundColor Yellow } }

    'pull' {
        Need-Pin
        $base = Get-Base
        if (-not $File) { $File = Join-Path $PSScriptRoot 'apps.json' }
        Write-Host "Reading app list from $base (takes a few seconds on the unit) ..."
        $r = Invoke-WebRequest -UseBasicParsing -Uri "$base/apps" -Headers @{ 'X-Pin' = $Pin } -TimeoutSec 120
        $text = [System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
        [System.IO.File]::WriteAllText($File, $text, (New-Object System.Text.UTF8Encoding $false))
        $j = $text | ConvertFrom-Json
        $apps = @($j.apps)
        Write-Host ("{0} apps saved to {1}" -f $apps.Count, $File) -ForegroundColor Green
        Write-Host ("Device: {0} / {1} / Android {2} (SDK {3})" -f $j.device.model, $j.device.hardware, $j.device.release, $j.device.sdk)
        $apps | Group-Object { $_.current.v } | ForEach-Object { Write-Host ("  {0,-6} {1}" -f $_.Name, $_.Count) }
    }

    'push' {
        Need-Pin
        $base = Get-Base
        if (-not $File) { $File = Join-Path $PSScriptRoot 'verdicts.json' }
        $v = Get-Content -Raw -Encoding UTF8 $File | ConvertFrom-Json
        if ($null -eq $v.verdicts) { $v = [pscustomobject]@{ replace = [bool]$Replace; verdicts = $v } }
        elseif ($Replace) { $v | Add-Member -Force replace $true }
        $body = [System.Text.Encoding]::UTF8.GetBytes(($v | ConvertTo-Json -Depth 6 -Compress))
        $r = Invoke-RestMethod -Method Post -Uri "$base/verdicts" -Headers @{ 'X-Pin' = $Pin } `
            -ContentType 'application/json; charset=utf-8' -Body $body -TimeoutSec 30
        Write-Host "Unit stored $($r.stored) verdicts. Its Bloat tab is updated." -ForegroundColor Green
    }

    'verdicts' {
        Need-Pin
        $base = Get-Base
        Invoke-RestMethod -Uri "$base/verdicts" -Headers @{ 'X-Pin' = $Pin } | ConvertTo-Json -Depth 6
    }

    # ---- Remote control: .\hu.ps1 remote   (then open the printed address in a browser) ----
    'remote' {
        Need-Pin
        $base = Get-Base
        $r = Invoke-RestMethod -Method Post -Uri "$base/remote/start" -Headers @{ 'X-Pin' = $Pin } -Body '{}' -ContentType 'application/json'
        Write-Host "Open $base in your browser. Capturing: $($r.capturing)  Touch: $($r.touch)  $($r.hint)"
    }
    'shot' {   # .\hu.ps1 shot [file.jpg]
        Need-Pin
        $base = Get-Base
        $out = if ($Args2) { $Args2[0] } else { Join-Path $PSScriptRoot 'screen.jpg' }
        Invoke-WebRequest -UseBasicParsing -Uri "$base/screen.jpg" -Headers @{ 'X-Pin' = $Pin } -OutFile $out
        Write-Host "Saved $out"
    }
    { $_ -in 'tap', 'swipe', 'key', 'text', 'launch' } {
        # tap 0.5 0.5 | swipe 0.5 0.8 0.5 0.2 | key back|home|recents|notifications | text "hello" | launch com.spotify.music
        Need-Pin
        $base = Get-Base
        $b = switch ($Cmd) {
            'tap'    { @{ type = 'tap'; x = [double]$Args2[0]; y = [double]$Args2[1] } }
            'swipe'  { @{ type = 'swipe'; x = [double]$Args2[0]; y = [double]$Args2[1]; x2 = [double]$Args2[2]; y2 = [double]$Args2[3]; ms = 300 } }
            'key'    { @{ type = $Args2[0] } }
            'text'   { @{ type = 'text'; text = ($Args2 -join ' ') } }
            'launch' { @{ type = 'launch'; pkg = $Args2[0] } }
        }
        $body = [System.Text.Encoding]::UTF8.GetBytes(($b | ConvertTo-Json -Compress))
        Invoke-RestMethod -Method Post -Uri "$base/input" -Headers @{ 'X-Pin' = $Pin } -ContentType 'application/json; charset=utf-8' -Body $body | ConvertTo-Json -Compress
    }

    # ---- Firewall: net (list) | allow <pkg...> | block <pkg...> ----
    { $_ -in 'net', 'allow', 'block' } {
        Need-Pin
        $base = Get-Base
        $h = @{ 'X-Pin' = $Pin }
        $r = if ($Cmd -eq 'net') { Invoke-RestMethod -Uri "$base/net" -Headers $h }
             else { Invoke-RestMethod -Method Post -Uri "$base/net" -Headers $h -ContentType 'application/json' -Body (@{ $Cmd = @($Args2) } | ConvertTo-Json -Compress) }
        Write-Host ("Firewall enabled={0} running={1} dropped={2}" -f $r.enabled, $r.running, $r.blockedPackets)
        $r.apps | Sort-Object allowed, label | Format-Table @{ n = 'state'; e = { if ($_.allowed) { 'ALLOW' } else { 'BLOCK' } } }, label, pkg -AutoSize
    }

    { $_ -in 'forcestop', 'disable' } {   # .\hu.ps1 forcestop com.example.app   (presses the buttons on the unit)
        Need-Pin
        $base = Get-Base
        foreach ($p in $Args2) {
            $r = Invoke-RestMethod -Method Post -Uri "$base/auto" -Headers @{ 'X-Pin' = $Pin } -ContentType 'application/json' `
                -Body (@{ action = $Cmd; pkg = $p } | ConvertTo-Json -Compress) -TimeoutSec 30
            Write-Host ("{0,-45} {1} ({2})" -f $p, $(if ($r.ok) { 'OK' } else { 'FAILED' }), $r.message)
        }
    }

    # ---- Install: install <file.apk> (upload over Wi-Fi) | install bt (newest APK received over Bluetooth) ----
    'install' {
        Need-Pin
        $base = Get-Base
        $h = @{ 'X-Pin' = $Pin }
        $before = Invoke-RestMethod -Uri "$base/install" -Headers $h
        if (-not $Args2) { throw 'Usage: install <file.apk>  or  install bt' }
        if ($Args2[0] -eq 'bt') {
            $r = Invoke-RestMethod -Method Post -Uri "$base/install" -Headers $h -ContentType 'application/json' -Body '{"bluetooth":true}'
            Write-Host "Installing $($r.file) from the Bluetooth folder ..."
        } else {
            $apk = (Resolve-Path $Args2[0]).Path
            $name = [Uri]::EscapeDataString((Split-Path $apk -Leaf))
            Write-Host ("Uploading {0} ({1:N0} KB) ..." -f (Split-Path $apk -Leaf), ((Get-Item $apk).Length / 1KB))
            $r = Invoke-RestMethod -Method Post -Uri "$base/install?name=$name" -Headers $h `
                -ContentType 'application/vnd.android.package-archive' -InFile $apk -TimeoutSec 200
        }
        if (-not $r.helper) { Write-Host 'Accessibility helper is off: tap Install on the unit.' -ForegroundColor Yellow }
        # Watch the result. Updating HU Launcher itself restarts the link (same PIN) - wait for it.
        $deadline = (Get-Date).AddSeconds(120)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep 2
            try { $st = Invoke-RestMethod -Uri "$base/install" -Headers $h -TimeoutSec 4 } catch { Write-Host '  (link restarting ...)'; continue }
            if ($st.versionCode -ne $before.versionCode) { Write-Host "HU Launcher updated: $($before.versionName) -> $($st.versionName)" -ForegroundColor Green; return }
            Write-Host "  $($st.last)"
            if ($st.last -match ': (installed|failed)') { return }
        }
        Write-Host 'Gave up waiting - check the unit.' -ForegroundColor Yellow
    }

    # ---- Keep stopped: keep (list) | keep add <pkg...> | keep remove <pkg...> ----
    'keep' {
        Need-Pin
        $base = Get-Base
        $h = @{ 'X-Pin' = $Pin }
        $r = if (-not $Args2) { Invoke-RestMethod -Uri "$base/keep" -Headers $h }
             else { Invoke-RestMethod -Method Post -Uri "$base/keep" -Headers $h -ContentType 'application/json' -Body (@{ $Args2[0] = @($Args2 | Select-Object -Skip 1) } | ConvertTo-Json -Compress) }
        if (-not $r.helper) { Write-Host 'Accessibility helper is off: only silent kills, no full force stops.' -ForegroundColor Yellow }
        foreach ($p in $r.refused.PSObject.Properties) { Write-Host "Refused $($p.Name): $($p.Value)" -ForegroundColor Yellow }
        $r.apps | Format-Table pkg, state, strikes, silentKills, forceStops, nextForceStopMin -AutoSize
    }

    'stats' {
        Need-Pin
        $base = Get-Base
        $r = Invoke-RestMethod -Uri "$base/stats" -Headers @{ 'X-Pin' = $Pin }
        $d = $r.device
        Write-Host ("RAM {0}/{1} MB free  CPU {2}%  Temp {3} C  Up {4} min" -f $d.ramAvailMb, $d.ramTotalMb, $d.cpuPercent, $d.tempC, $d.uptimeMin)
        if (-not $r.usageAccess) { Write-Host 'Per-app stats need Usage access on the unit (Tools > Activity).' -ForegroundColor Yellow }
        $r.today | Select-Object -First 25 | Format-Table label, foregroundMin, rxKb, txKb, stopped, pkg -AutoSize
    }
}
