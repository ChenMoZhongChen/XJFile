# 第一段（快，不碰 SCSI）：卷标 / 容量 / SSD-HDD-USB-软盘 / 光驱盘符
#
# 为什么不用 Win32_LogicalDisk：它会对每个卷取文件系统信息，遇到空光驱/卡住的盘
# （真机：N: 空光驱）会无限挂住，整段磁盘探测跟着废掉。这里改成
#   Get-PSDrive 取盘符（不碰卷） + DriveInfo 只读固定/可移动盘的卷标和容量；
#   CDRom / Network 一律不读卷属性，交给后面的 optical.ps1 按需探测。
# 光驱不再在这里判断 VIDEO_TS（音频 CD/空盘上 Test-Path 会卡），改由 optical.ps1
# 在 SCSI 确认是 DVD 之后再查。
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$partMap = @{}
try { Get-Partition | Where-Object { $_.DriveLetter } | ForEach-Object { $partMap[[string]$_.DriveLetter] = $_.DiskNumber } } catch { }
$diskMap = @{}
try { Get-PhysicalDisk | ForEach-Object { $diskMap[[string]$_.DeviceId] = @{ media = [string]$_.MediaType; bus = [string]$_.BusType } } } catch { }

# 光驱媒体状态优先问 Explorer：raw SCSI 在“光驱里有音频 CD”时
# CreateFile/GET CONFIGURATION 会直接挂住，N: 实测 20 秒都不返回；
# Shell.Namespace(17) 能秒回 "BD-RE 驱动器 (N:) Audio CD"。
$shellNs = $null
try {
    $shell = New-Object -ComObject Shell.Application
    $shellNs = $shell.Namespace(17)
} catch { }

function Convert-ShellSize([string]$text) {
    if ([string]::IsNullOrWhiteSpace($text)) { return [long]0 }
    $m = [regex]::Match($text, '([\d\.,]+)\s*(B|KB|MB|GB|TB|字节|千字节|兆字节|吉字节|太字节)')
    if (-not $m.Success) { return [long]0 }
    $n = 0.0
    $raw = $m.Groups[1].Value.Replace(',', '')
    if (-not [double]::TryParse($raw, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$n)) {
        return [long]0
    }
    $unit = $m.Groups[2].Value
    $mul = switch ($unit) {
        'TB' { 1099511627776.0 }
        '太字节' { 1099511627776.0 }
        'GB' { 1073741824.0 }
        '吉字节' { 1073741824.0 }
        'MB' { 1048576.0 }
        '兆字节' { 1048576.0 }
        'KB' { 1024.0 }
        '千字节' { 1024.0 }
        default { 1.0 }
    }
    return [long]($n * $mul)
}

$result = @()
$letters = @(Get-PSDrive -PSProvider FileSystem | Sort-Object Name | ForEach-Object { $_.Name })
foreach ($letter in $letters) {
    try { $di = New-Object IO.DriveInfo("$letter`:\") } catch { continue }
    $dt = [int]$di.DriveType
    $kind = 'unknown'
    if ($dt -eq 2) { $kind = 'floppy' }
    elseif ($dt -eq 3) { $kind = 'disk' }
    elseif ($dt -eq 5) { $kind = 'optical' }

    $label = ''
    $size = 0
    $free = 0
    $bus = ''
    $media = ''
    $shellAudio = $false
    $shellEmpty = $false
    $shellMedia = $false
    if ($dt -eq 2 -or $dt -eq 3) {
        if ($partMap.ContainsKey($letter)) {
            $dn = [string]$partMap[$letter]
            if ($diskMap.ContainsKey($dn)) {
                $bus = $diskMap[$dn].bus
                switch ($diskMap[$dn].media) {
                    'SSD' { $kind = 'ssd' }
                    'HDD' { $kind = 'hdd' }
                    default { if ($bus -eq 'USB') { $kind = 'usb' } }
                }
                if ($dt -eq 2 -and $bus -eq 'USB') { $kind = 'usb' }
            }
        }
        try {
            if ($di.IsReady) {
                $label = [string]$di.VolumeLabel
                $size = [long]$di.TotalSize
                $free = [long]$di.AvailableFreeSpace
            }
        } catch { }
    }

    if ($dt -eq 5 -and $null -ne $shellNs) {
        try {
            $item = $shellNs.ParseName("$letter`:\")
            if ($null -ne $item) {
                $shellName = [string]$item.Name
                $tail = ''
                $m = [regex]::Match($shellName, '^.*?\([^)]*\)\s*(.*)$')
                if ($m.Success) { $tail = $m.Groups[1].Value.Trim() }
                if ($tail) {
                    $label = $tail
                    if ($tail -match 'Audio CD|音频 CD|CD 音频') {
                        $media = 'cd'
                        $shellAudio = $true
                        $shellMedia = $true
                    }
                } else {
                    $shellEmpty = $true
                }

                if (-not $shellAudio) {
                    $sizeText = [string]$shellNs.GetDetailsOf($item, 2)
                    $freeText = [string]$shellNs.GetDetailsOf($item, 3)
                    $shellSize = Convert-ShellSize $sizeText
                    if ($shellSize -gt 0) {
                        $size = $shellSize
                        $free = Convert-ShellSize $freeText
                        $shellEmpty = $false
                        $shellMedia = $true
                        # 光驱型号名里带 BD/DVD 时优先按型号判；否则再按容量分档。
                        # 这样小容量数据 BD 也不会被误判成 DVD。
                        if ($shellName -match 'BD') {
                            $media = 'bd'
                        } elseif ($shellName -match 'DVD') {
                            $media = 'dvd'
                        } elseif ($shellSize -ge 25GB) {
                            $media = 'bd'
                        } elseif ($shellSize -ge 2GB) {
                            $media = 'dvd'
                        } else {
                            $media = 'cd'
                        }
                    }
                }
            }
        } catch { }
    }

    $result += [ordered]@{
        letter    = $letter
        label     = $label
        driveType = $dt
        sizeBytes = $size
        freeBytes = $free
        kind      = $kind
        bus       = $bus
        media     = $media
        shellAudio = $shellAudio
        shellEmpty = $shellEmpty
        shellMedia = $shellMedia
        videoTs   = $false
    }
}

ConvertTo-Json -InputObject @($result) -Depth 4 -Compress
