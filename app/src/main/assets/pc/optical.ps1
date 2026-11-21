# 第二段（慢，走 SCSI）：光驱里到底是 CD / DVD / BD
# 只用 GET CONFIGURATION 的 current profile 判断，不读扇区，很轻。
# __DRIVES__ 会被替换成光驱盘符数组，例如 @('L','N')；没有光驱时不会调用。
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
public static class XjScsi2 {
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
    public static byte[] Send(SafeHandle h, byte[] cdb, int dataLen, out int status) {
        int sptSize = Marshal.SizeOf(typeof(SPT));
        IntPtr data = Marshal.AllocHGlobal(dataLen > 0 ? dataLen : 1);
        IntPtr p = IntPtr.Zero;
        status = -1;
        try {
            var spt = new SPT();
            spt.Length = (ushort)sptSize; spt.CdbLength = (byte)cdb.Length;
            spt.SenseInfoLength = 24; spt.DataIn = 1;
            spt.DataTransferLength = (uint)dataLen; spt.TimeOutValue = 5;
            spt.DataBuffer = data; spt.SenseInfoOffset = (uint)sptSize;
            spt.Cdb = new byte[16]; Array.Copy(cdb, spt.Cdb, cdb.Length);
            int bufLen = sptSize + 24;
            p = Marshal.AllocHGlobal(bufLen);
            Marshal.StructureToPtr(spt, p, false);
            int ret;
            bool ok = Io2(h, 0x4D014, p, bufLen, p, bufLen, out ret, IntPtr.Zero);
            var back = (SPT)Marshal.PtrToStructure(p, typeof(SPT));
            status = back.ScsiStatus;
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
    $h = [XjScsi2]::CreateFileW("\\.\$($d):", [uint32]3221225472, [uint32]3, [IntPtr]::Zero, [uint32]3, [uint32]0, [IntPtr]::Zero)
    if ($h.IsInvalid) { continue }
    try {
        $st = 0
        $r = [XjScsi2]::Send($h, [byte[]]@(0x46, 0, 0, 0, 0, 0, 0, 0x08, 0, 0), 8, [ref]$st)
        if ($null -eq $r) { continue }
        $profile = ($r[6] -shl 8) -bor $r[7]
        $media = 'unknown'
        if ($profile -ge 0x08 -and $profile -le 0x0A) { $media = 'cd' }
        elseif ($profile -ge 0x10 -and $profile -le 0x2F) { $media = 'dvd' }
        elseif ($profile -ge 0x40 -and $profile -le 0x43) { $media = 'bd' }
        elseif ($profile -eq 0) { $media = 'empty' }
        # 视频 DVD 判定放在这里：只有 SCSI 已确认是 DVD 才去碰文件系统。
        # 空光驱/音频 CD 上 Test-Path 会卡住（真机 N: 踩过），所以绝不能无条件查。
        $videoTs = $false
        if ($media -eq 'dvd') {
            try { if (Test-Path "$($d):\VIDEO_TS") { $videoTs = $true } } catch { }
        }
        # 卷标只在确认有介质后读：空光驱/坏光驱上读卷标会卡死（N: 就是），
        # 有介质时 DriveInfo 的 IsReady/VolumeLabel 实测是毫秒级。
        $label = ''
        if ($media -ne 'empty' -and $media -ne 'unknown') {
            try {
                $di = New-Object IO.DriveInfo("$($d):\")
                if ($di.IsReady) { $label = [string]$di.VolumeLabel }
            } catch { }
        }
        $result += [ordered]@{ letter = $d; profile = $profile; media = $media; videoTs = $videoTs; label = $label }
    } finally { $h.Close() }
}

ConvertTo-Json -InputObject @($result) -Depth 3 -Compress
