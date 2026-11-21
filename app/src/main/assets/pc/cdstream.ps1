# 从 __DRIVE__ 光驱的 __LBA__ 扇区开始，连续读 __SECTORS__ 个扇区，裸 PCM 直接写 stdout。
# 一次 SCSI READ CD 最多 26 扇区（这台 USB 光驱的上限），循环读即可。
# SET CD SPEED 把读速限到 __SPEED__ KB/s（1x = 176）；不限速光驱会按最高倍速狂转，听着像翻录。
# WatchQuit：调用方关流前会写一行 quit。Windows 没有 SIGHUP，只断通道可能把进程留在
# 阻塞写里，成为抢光驱的孤儿进程（真机踩过：两个读者交替读同一张盘 = 疯狂巡道）。
#
# 注意整条命令要塞进 Windows 的 8191 字符命令行上限，所以 C# 里**不写注释**，
# 说明都放上面这些 PowerShell 注释里（minify 会把 # 行删掉，不占预算）。
$ErrorActionPreference = 'Stop'

# 同一台光驱同一时刻只允许一个读流：新流启动前，先把上一个 ssh 会话派生的 powershell 清掉。
# 为什么必须这样做：手机上快速连按「下一首」，或直接强杀 App 时，旧流不会被及时回收；
# 多个读者各自读不同的 LBA，光驱就在它们之间来回跳 —— 听起来就是疯狂巡道，
# 而且往往要等旧的自己退出（约 30 秒）才恢复。
try {
    Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" | Where-Object { $_.ProcessId -ne $PID } | ForEach-Object {
        $par = Get-CimInstance Win32_Process -Filter "ProcessId=$($_.ParentProcessId)" -ErrorAction SilentlyContinue
        if ($null -ne $par -and ($par.Name -eq 'cmd.exe' -or $par.Name -eq 'sshd.exe')) {
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        }
    }
} catch { }

# 找出这条 exec 通道对应的 sshd 祖先进程。连接一断（手机上强杀 App、掉线），
# sshd 就会退出；脚本靠它自杀，不会留下占着光驱的孤儿。
$sshdPid = 0
try {
    $pp = (Get-CimInstance Win32_Process -Filter "ProcessId=$PID").ParentProcessId
    $gp = (Get-CimInstance Win32_Process -Filter "ProcessId=$pp").ParentProcessId
    $g = Get-CimInstance Win32_Process -Filter "ProcessId=$gp" -ErrorAction SilentlyContinue
    if ($null -ne $g -and $g.Name -eq 'sshd.exe') { $sshdPid = [int]$gp }
} catch { }

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
public static class XjCdda {
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
    public static byte[] Run(SafeHandle h, byte[] cdb, int dataLen, int dataIn, out int status) {
        int sptSize = Marshal.SizeOf(typeof(SPT));
        IntPtr data = Marshal.AllocHGlobal(dataLen > 0 ? dataLen : 1);
        IntPtr p = IntPtr.Zero;
        status = -1;
        try {
            var spt = new SPT();
            spt.Length = (ushort)sptSize; spt.CdbLength = (byte)cdb.Length; spt.SenseInfoLength = 24;
            spt.DataIn = (byte)dataIn; spt.DataTransferLength = (uint)dataLen; spt.TimeOutValue = 30;
            spt.DataBuffer = data; spt.SenseInfoOffset = (uint)sptSize;
            byte[] cb = new byte[16]; Array.Copy(cdb, cb, cdb.Length); spt.Cdb = cb;
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
    public static int SetSpeed(SafeHandle h, int kbps) {
        byte[] c = new byte[12]; c[0] = 0xBB; c[2] = (byte)(kbps >> 8); c[3] = (byte)kbps; c[4] = 0xFF; c[5] = 0xFF;
        int st;
        return Run(h, c, 0, 0, out st) == null ? -1 : 0;
    }
    public static byte[] Read(SafeHandle h, uint lba, int sectors) {
        byte[] c = new byte[12]; c[0] = 0xBE;
        c[2] = (byte)(lba >> 24); c[3] = (byte)(lba >> 16); c[4] = (byte)(lba >> 8); c[5] = (byte)lba;
        c[8] = (byte)sectors; c[9] = 0x10;
        int st;
        return Run(h, c, sectors * 2352, 1, out st);
    }
    public static void WatchParent(int sshdPid) {
        if (sshdPid <= 0) { return; }
        var t = new System.Threading.Thread(() => {
            while (true) {
                System.Threading.Thread.Sleep(2000);
                try {
                    var p = System.Diagnostics.Process.GetProcessById(sshdPid);
                    if (p.HasExited) { Environment.Exit(0); }
                } catch { Environment.Exit(0); }
            }
        });
        t.IsBackground = true;
        t.Start();
    }
    public static void WatchQuit() {
        var t = new System.Threading.Thread(() => {
            try {
                while (true) {
                    string line = Console.In.ReadLine();
                    if (line == null) { return; }
                    if (line.Trim() == "quit") { Environment.Exit(0); }
                }
            } catch { }
        });
        t.IsBackground = true;
        t.Start();
    }
}
"@

$lba = __LBA__
$left = __SECTORS__
$stdout = [Console]::OpenStandardOutput()
$h = [XjCdda]::CreateFileW('\\.\__DRIVE__:', [uint32]3221225472, [uint32]3, [IntPtr]::Zero, [uint32]3, [uint32]0, [IntPtr]::Zero)
if ($h.IsInvalid) { [Console]::Error.WriteLine('OPEN-FAIL'); exit 1 }
# 把自己的 PID 写成带 token 的文件：调用方关流时靠它定点杀进程（见 cdkill.ps1）
try { Set-Content -Path (Join-Path $env:TEMP 'xjcd-__TOKEN__.pid') -Value $PID -Force } catch { }
try { [XjCdda]::WatchQuit() } catch { }
try { [XjCdda]::WatchParent($sshdPid) } catch { }
# 限速失败也不致命（个别光驱不支持 SET CD SPEED），最多就是响一点
try { [void][XjCdda]::SetSpeed($h, [int]__SPEED__) } catch { }
try {
    while ($left -gt 0) {
        $n = [Math]::Min(26, $left)
        $chunk = [XjCdda]::Read($h, [uint32]$lba, [int]$n)
        if ($null -eq $chunk) { [Console]::Error.WriteLine('READ-FAIL'); exit 1 }
        $stdout.Write($chunk, 0, $chunk.Length)
        # 每块都 flush：不 flush 的话 .NET 会攒满缓冲区才发，起播会白等好几秒
        $stdout.Flush()
        $lba += $n
        $left -= $n
    }
} finally {
    $h.Close()
}
