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

    $result += [ordered]@{
        letter    = $letter
        label     = $label
        driveType = $dt
        sizeBytes = $size
        freeBytes = $free
        kind      = $kind
        bus       = $bus
        media     = ''
        videoTs   = $false
    }
}

ConvertTo-Json -InputObject @($result) -Depth 4 -Compress
