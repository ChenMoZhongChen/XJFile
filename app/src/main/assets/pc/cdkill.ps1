# 按 token 定点杀掉对应的 CD 读流进程。
# 为什么需要它：Windows 上没有 SIGHUP，只断开 SSH 通道时远端 PowerShell 可能
# 一直卡在写 stdout 的阻塞里，成为抢光驱的孤儿进程（真机踩过：切歌后新旧两个
# 读者交替读同一张盘，听起来就是疯狂巡道 + 断断续续）。
# pid 文件是 cdstream.ps1 启动时写下的，文件名带 token，所以不会误杀别的流。
$f = Join-Path $env:TEMP 'xjcd-__TOKEN__.pid'
if (Test-Path $f) {
    $target = 0
    try { $target = [int]((Get-Content $f -Raw).Trim()) } catch { }
    if ($target -gt 0) {
        try { Stop-Process -Id $target -Force -ErrorAction Stop } catch { }
    }
    Remove-Item $f -Force -ErrorAction SilentlyContinue
}
