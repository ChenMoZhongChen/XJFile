# 第四段（只对 DVD/BD 跑）：查千年盘（M-DISC）
#
# 为什么单独一段、不并进 optical.ps1：
#   READ DISC STRUCTURE 支持的 format 随盘型而变（DVD+R / DVD-R / BD 各不相同），
#   不支持的 format 会返回 CHECK CONDITION，个别光驱还可能真的卡住。
#   单独一段 + 自己的超时，最坏情况也只是「认不出千年盘」，不会把已经拿到的
#   CD/DVD/BD 判定一起弄丢（那会让磁盘列表整块退回「未知」）。
#
# 判据：M-DISC 的 MID 一定以 MILLEN 开头 ——
#   DVD：MILLENIA001（Millenniata / Verbatim / Ritek 代工都是这个）
#   BD ：MILLEN/MR1/0
# 这里不按固定偏移读字段，而是把每种盘结构的整段应答按 ASCII 扫一遍：
#   不同盘型/固件把 MID 放在不同 format 的不同偏移，按偏移硬解很容易全错（踩过
#   CD 的 READ_TOC_EX）；扫字符串对「厂商 ID 是 ASCII」这件事是稳的，
#   而且误判面很小 —— 只有 Millenniata 一家的 MID 以 MILLEN 开头。
#
# 只读盘结构，不读扇区、不写盘。
# __DRIVES__ 会被替换成光驱盘符数组，例如 @('L','N')；没有 DVD/BD 时不会调用。
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
public static class XjScsiMd {
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
            spt.DataTransferLength = (uint)dataLen;
            // 5 秒：正常的 CHECK CONDITION 是立刻回的，真卡住也不至于把整段探测拖过 20 秒
            spt.TimeOutValue = 5;
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
    $h = [XjScsiMd]::CreateFileW("\\.\$($d):", [uint32]3221225472, [uint32]3, [IntPtr]::Zero, [uint32]3, [uint32]0, [IntPtr]::Zero)
    if ($h.IsInvalid) { continue }
    try {
        # 先用 GET CONFIGURATION 的 current profile 判 DVD 还是 BD，决定要问哪些结构
        $st = 0
        $cfg = [XjScsiMd]::Send($h, [byte[]]@(0x46, 0, 0, 0, 0, 0, 0, 0x08, 0, 0), 8, [ref]$st)
        $profile = if ($null -ne $cfg -and $cfg.Length -ge 8) { ($cfg[6] -shl 8) -bor $cfg[7] } else { 0 }
        $mediaType = -1
        $formats = @()
        if ($profile -ge 0x10 -and $profile -le 0x2F) {
            # MMC：媒体类型 0 = DVD/HD DVD。0x00 物理格式信息、0x04 制造信息、
            # 0x0E 预录信息（DVD-R 的 MID 在这里）、0x0F/0x10 是 DVD-R 专用结构。
            $mediaType = 0
            $formats = @(0x00, 0x04, 0x0E, 0x0F, 0x10)
        } elseif ($profile -ge 0x40 -and $profile -le 0x43) {
            # 媒体类型 1 = BD；format 0x00 的 Disc Information 里带厂商 ID
            $mediaType = 1
            $formats = @(0x00)
        }
        if ($mediaType -lt 0) {
            $result += [ordered]@{ letter = $d; mdisc = $false; mid = '' }
            continue
        }

        $mdisc = $false
        $mid = ''
        $sw = [Diagnostics.Stopwatch]::StartNew()
        foreach ($fmt in $formats) {
            # 总预算 8 秒：下面每条 SCSI 自带 5 秒超时，不能让它把整个探测拖死
            if ($sw.ElapsedMilliseconds -gt 8000) { break }
            $st = 0
            # READ DISC STRUCTURE (0xAD)：byte1=媒体类型，byte7=format，
            # byte8-9=分配长度（大端 4096），byte10=AGID，其余保留
            $r = [XjScsiMd]::Send($h, [byte[]]@(0xAD, $mediaType, 0, 0, 0, 0, 0, $fmt, 0x10, 0x00, 0, 0), 4096, [ref]$st)
            if ($null -eq $r -or $r.Length -eq 0) { continue }
            $txt = [Text.Encoding]::ASCII.GetString($r)
            $i = $txt.IndexOf('MILLEN', [StringComparison]::OrdinalIgnoreCase)
            if ($i -ge 0) {
                $mdisc = $true
                # 多留几个字节方便日志里看是 DVD 的 MILLENIA001 还是 BD 的 MILLEN/MR1/0
                $mid = $txt.Substring($i, [Math]::Min(16, $txt.Length - $i)).Replace([char]0, ' ').Trim()
                break
            }
        }
        $result += [ordered]@{ letter = $d; mdisc = $mdisc; mid = $mid }
    } finally { $h.Close() }
}

ConvertTo-Json -InputObject @($result) -Depth 3 -Compress
