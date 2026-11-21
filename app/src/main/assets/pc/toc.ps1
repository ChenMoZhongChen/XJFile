# 第三段（只有音频 CD 才跑）：读 TOC，给出每条音轨的起始 LBA 和扇区数。
# 用 SCSI READ TOC（CDB 0x43，Msf=0 直接要 LBA）。不要用 IOCTL_CDROM_READ_TOC_EX：
# 这台 USB 光驱不管 Msf 标志都给 MSF，按位解析会把地址读错（实测过）。
# __DRIVES__ 会被替换成需要读 TOC 的盘符数组，例如 @('L')。
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
public static class XjToc {
    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    public static extern SafeFileHandle CreateFileW(string n, uint a, uint s, IntPtr sa, uint d, uint f, IntPtr t);
    [DllImport("kernel32.dll", SetLastError = true, EntryPoint = "DeviceIoControl")]
    public static extern bool Io2(SafeHandle h, uint code, IntPtr inBuf, int inSize, IntPtr outBuf, int outSize, out int ret, IntPtr ov);
    [StructLayout(LayoutKind.Sequential)]
    public struct SPT {
        public ushort Length; public byte ScsiStatus, PathId, TargetId, Lun, CdbLength, SenseInfoLength, DataIn;
        public uint DataTransferLength, TimeOutValue;
        public IntPtr DataBuffer; public uint SenseInfoOffset;
        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 16)] public byte[] Cdb;
    }
    // CDB 0x43：Msf=0 → 地址是 4 字节大端 LBA；format=0 → 完整 TOC（含 lead-out 0xAA）。
    public static byte[] ReadToc(SafeHandle h, int dataLen) {
        int sptSize = Marshal.SizeOf(typeof(SPT));
        IntPtr data = Marshal.AllocHGlobal(dataLen);
        IntPtr p = IntPtr.Zero;
        try {
            var spt = new SPT();
            spt.Length = (ushort)sptSize; spt.CdbLength = 12; spt.SenseInfoLength = 24; spt.DataIn = 1;
            spt.DataTransferLength = (uint)dataLen; spt.TimeOutValue = 15;
            spt.DataBuffer = data; spt.SenseInfoOffset = (uint)sptSize;
            spt.Cdb = new byte[16];
            spt.Cdb[0] = 0x43; spt.Cdb[1] = 0x00; spt.Cdb[2] = 0x00;
            spt.Cdb[7] = (byte)(dataLen >> 8); spt.Cdb[8] = (byte)dataLen;
            int bufLen = sptSize + 24;
            p = Marshal.AllocHGlobal(bufLen);
            Marshal.StructureToPtr(spt, p, false);
            int ret;
            bool ok = Io2(h, 0x4D014, p, bufLen, p, bufLen, out ret, IntPtr.Zero);
            var back = (SPT)Marshal.PtrToStructure(p, typeof(SPT));
            if (!ok || back.ScsiStatus != 0) return null;
            byte[] r = new byte[(int)back.DataTransferLength];
            Marshal.Copy(data, r, 0, r.Length);
            return r;
        } finally {
            Marshal.FreeHGlobal(data);
            if (p != IntPtr.Zero) Marshal.FreeHGlobal(p);
        }
    }
}
"@

$drives = __DRIVES__
$result = @()
foreach ($d in $drives) {
    $item = [ordered]@{ letter = $d; audio = $false; tracks = @() }
    $h = [XjToc]::CreateFileW("\\.\$($d):", [uint32]3221225472, [uint32]3, [IntPtr]::Zero, [uint32]3, [uint32]0, [IntPtr]::Zero)
    if ($h.IsInvalid) { $result += $item; continue }
    try {
        $toc = $null
        try { $toc = [XjToc]::ReadToc($h, 804) } catch { }
        if ($null -ne $toc -and $toc.Length -ge 4) {
            # TOC 长度字段不含它自己那 2 字节：2（首/末轨）+ n*8（描述符）
            $len = ([int]$toc[0] -shl 8) -bor [int]$toc[1]
            $count = [Math]::Floor(($len - 2) / 8)
            $leadOut = -1
            $byNum = @{}
            for ($i = 0; $i -lt $count; $i++) {
                $o = 4 + $i * 8
                if ($o + 7 -ge $toc.Length) { break }
                # byte1 低 4 位是 CONTROL：0x04 = 数据轨，音频轨没这一位
                $ctrl = [int]$toc[$o + 1] -band 0x0F
                $num = [int]$toc[$o + 2]
                $lba = ([int]$toc[$o + 4] -shl 24) -bor ([int]$toc[$o + 5] -shl 16) -bor ([int]$toc[$o + 6] -shl 8) -bor [int]$toc[$o + 7]
                if ($num -eq 170) { $leadOut = $lba }
                elseif ($num -ge 1 -and $num -le 99) { $byNum[$num] = @($lba, $ctrl) }
            }
            $tracks = @()
            $dataCount = 0
            $nums = @($byNum.Keys | Sort-Object)
            foreach ($k in $nums) {
                $start = $byNum[$k][0]
                $next = $leadOut
                foreach ($k2 in $nums) {
                    if ($k2 -gt $k -and $byNum[$k2][0] -gt $start) { $next = $byNum[$k2][0]; break }
                }
                if ($next -gt $start) {
                    $tracks += [ordered]@{ n = $k; lba = $start; sectors = ($next - $start) }
                }
                if (($byNum[$k][1] -band 0x04) -ne 0) { $dataCount++ }
            }
            $item['tracks'] = $tracks
            $item['audio'] = ($tracks.Count -gt 0 -and $dataCount -eq 0)
        }
    } finally { $h.Close() }
    $result += $item
}

ConvertTo-Json -InputObject @($result) -Depth 4 -Compress
