package com.cmzc.xj_file.app.core.disk

import android.content.Context
import android.util.Log
import com.cmzc.xj_file.app.core.fs.SftpFileSystem
import com.cmzc.xj_file.app.core.util.DebugLog
import org.json.JSONArray
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * 向 PC 要一份「磁盘清单」：卷标、容量、SSD/HDD/U盘/软盘、光驱介质、音频 CD 音轨表、
 * 千年盘（M-DISC）标记。
 *
 * **分四条命令跑**（都是被 Windows 8191 的命令行上限和各自的耗时逼出来的）：
 * 1. `drives.ps1`：WMI 一把梭，快（约 0.5 秒），顺便看一眼光驱里有没有 VIDEO_TS；
 * 2. `optical.ps1`：只对光驱跑 SCSI 的 GET CONFIGURATION，判断 CD/DVD/BD；
 * 3. `mdisc.ps1`：**只有 DVD/BD 才跑**，读盘结构找 MILLEN 厂商 ID（M-DISC）；
 * 4. `toc.ps1`：**只有真认出音频 CD 才跑**，读 TOC 拿每条音轨的起始 LBA。
 *    拆成独立命令是因为塞进第 2 段会把整条命令顶到 8191 字符上限附近（实测 8014）。
 *
 * 结果按连接缓存 30 秒 —— 每次进根目录都 SSH 四趟太慢，但缓存太久又看不到"刚插上碟"。
 */
object DriveProbe {

    private const val TAG = "XjDriveProbe"
    private const val TTL_MS = 30_000L

    private class Entry(val at: Long, val drives: List<DriveInfo>)

    private val cache = HashMap<String, Entry>()

    /** 掉线/换连接时清掉，避免把上一台的盘符显示到另一台机器上。 */
    @Synchronized
    fun invalidate(sourceId: String) {
        cache.remove(sourceId)
    }

    suspend fun load(context: Context, fs: SftpFileSystem, sourceId: String): List<DriveInfo>? {
        synchronized(this) {
            cache[sourceId]?.let {
                if (System.currentTimeMillis() - it.at < TTL_MS) return it.drives
            }
        }
        val base = runCatching { fs.exec(PcScript.command(context, "drives.ps1"), 25_000) }
            .getOrElse {
                DebugLog.ev(context, "drives.ps1 失败：${it.javaClass.simpleName}: ${it.message}")
                Log.e(TAG, "取磁盘清单失败", it)
                return null
            }
        DebugLog.ev(context, "drives.ps1 返回 ${base.length} 字符")
        val drives = runCatching { parseDrives(base) }.getOrElse {
            DebugLog.ev(context, "drives.ps1 解析失败：${it.javaClass.simpleName}: ${it.message}")
            Log.e(TAG, "解析磁盘清单失败", it)
            return null
        }
        DebugLog.ev(context, "drives.ps1 解析出 ${drives.size} 个盘")

        val refined = refineOptical(context, fs, drives)
        // 千年盘（M-DISC）识别：只对真有 DVD/BD 的光驱跑，读盘结构自带超时，失败不影响上面结果
        val withMdisc = attachMdisc(context, fs, refined)
        // 音频 CD 的音轨表：探测阶段顺带拿好，用户点开时就不用再等一趟 SSH
        val full = attachTracks(context, fs, withMdisc)
        synchronized(this) { cache[sourceId] = Entry(System.currentTimeMillis(), full) }
        return full
    }

    /**
     * 单独补一次 TOC，给「探测时没读到、点开时才补」用。
     *
     * @return letter(大写) → (是否纯音频轨, 音轨表)；整体失败返回 null
     */
    suspend fun loadTracks(
        context: Context,
        fs: SftpFileSystem,
        letters: List<String>,
    ): Map<String, Pair<Boolean, List<CdTrack>>>? {
        if (letters.isEmpty()) return null
        val arg = letters.joinToString(",", "'", "'") { it }
        val cmd = PcScript.command(context, "toc.ps1", "__DRIVES__" to "@($arg)")
        val out = runCatching { withContext(Dispatchers.IO) { fs.exec(cmd, 25_000) } }.getOrElse {
            Log.e(TAG, "取 TOC 失败", it)
            return null
        }
        return runCatching { parseTracks(out) }.getOrElse {
            Log.e(TAG, "解析 TOC 失败", it)
            null
        }
    }

    /**
     * 第二段：只问光驱的物理介质，问不到就用第一段的容量推断结果。
     *
     * **一台光驱一条命令**，不再把所有光驱塞进同一条命令：
     * 真机上 N: 是空光驱，GET CONFIGURATION 会卡到 SCSI 超时；塞在一起时
     * 整条命令超时，M:/O: 也一起拿不到。拆开 + 各自 6 秒超时后，卡住的那台
     * 只拖自己，其它光驱照样出结果。
     */
    private suspend fun refineOptical(
        context: Context,
        fs: SftpFileSystem,
        drives: List<DriveInfo>,
    ): List<DriveInfo> {
        val letters = drives.filter { it.isOptical }.map { it.letter }
        if (letters.isEmpty()) return drives
        val byLetter = coroutineScope {
            letters.map { letter ->
                async(Dispatchers.IO) { probeOptical(context, fs, letter) }
            }.awaitAll()
                .flatMap { it.entries }
                .associate { it.key to it.value }
        }
        if (byLetter.isEmpty()) return drives
        DebugLog.ev(context, "optical 探测到 ${byLetter.size} 台光驱")
        return drives.map { d ->
            val probe = byLetter[d.letter.uppercase()] ?: return@map d
            // ★ 视频 DVD / 数据 DVD 的区分只有 SCSI 确认是 DVD 后才可靠；
            // 第一段不再碰光驱文件系统（空盘会卡），所以 videoTs 由第二段带回。
            val videoTs = d.videoTs || probe.videoTs
            val merged = if (probe.media == OpticalMedia.DVD_DATA && videoTs) {
                OpticalMedia.DVD_VIDEO
            } else {
                probe.media
            }
            d.copy(
                optical = merged,
                videoTs = videoTs,
                // 第一段为了不碰坏光驱不再读卷标；有介质时第二段把 "音频 CD"/碟名补回来
                label = probe.label.ifBlank { d.label },
            )
        }
    }

    /** 单台光驱的 SCSI 探测；失败/超时就返回空表，绝不拖累其它盘。 */
    private suspend fun probeOptical(
        context: Context,
        fs: SftpFileSystem,
        letter: String,
    ): Map<String, OpticalProbe> {
        val cmd = PcScript.command(context, "optical.ps1", "__DRIVES__" to "@('$letter')")
        val out = runCatching { fs.exec(cmd, 6_000) }.getOrElse {
            DebugLog.ev(context, "optical $letter 超时/失败：${it.message}")
            return emptyMap()
        }
        return runCatching { parseOptical(out) }.getOrElse {
            Log.e(TAG, "解析光驱 $letter 失败", it)
            emptyMap()
        }
    }

    private class OpticalProbe(
        val media: OpticalMedia,
        val videoTs: Boolean,
        val label: String,
    )

    /**
     * 千年盘识别：只问「物理介质已经是 DVD / BD」的光驱。
     *
     * 音频 CD 不可能是 M-DISC（M-DISC 只有 DVD±R / BD-R），数据 CD 同理，
     * 所以不拿 CD 去浪费一次 SSH。失败就返回原列表 —— 宁可图标不显示千年盘，
     * 也不能因为这一段把磁盘类型/容量一起弄没。
     */
    private suspend fun attachMdisc(
        context: Context,
        fs: SftpFileSystem,
        drives: List<DriveInfo>,
    ): List<DriveInfo> {
        val letters = drives
            .filter {
                it.isOptical && (
                    it.optical == OpticalMedia.DVD_DATA ||
                        it.optical == OpticalMedia.DVD_VIDEO ||
                        it.optical == OpticalMedia.BD
                    )
            }
            .map { it.letter }
        if (letters.isEmpty()) return drives
        val arg = letters.joinToString(",", "'", "'") { it }
        val cmd = PcScript.command(context, "mdisc.ps1", "__DRIVES__" to "@($arg)")
        // 脚本内部有 8 秒总预算 + 每条 SCSI 5 秒超时；这里给 20 秒，宁可放弃识别也别卡住列表
        val out = runCatching { withContext(Dispatchers.IO) { fs.exec(cmd, 20_000) } }.getOrNull()
            ?: return drives
        val byLetter = runCatching { parseMdisc(out) }.getOrNull().orEmpty()
        if (byLetter.isEmpty()) return drives
        return drives.map { d ->
            val hit = byLetter[d.letter.uppercase()] ?: return@map d
            if (hit.mdisc) {
                // 用户没有千年盘可测，真插上时日志里能直接看到厂商 ID，方便判断是真空盘还是误判
                DebugLog.ev(context, "M-DISC 识别：${d.letter}: ${hit.mid}")
            }
            d.copy(isMdisc = hit.mdisc)
        }
    }

    /** 第三段（mdisc.ps1）的返回。 */
    private class MdiscHit(val mdisc: Boolean, val mid: String)

    private fun parseMdisc(json: String): Map<String, MdiscHit> {
        val arr = JSONArray(jsonText(json))
        val map = HashMap<String, MdiscHit>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            map[o.optString("letter", "").uppercase()] = MdiscHit(
                mdisc = o.optBoolean("mdisc", false),
                mid = o.optString("mid", ""),
            )
        }
        return map
    }

    /** 第四段：只对「物理介质是 CD」的光驱读 TOC（数据 CD 也会读，结果为空）。 */
    private suspend fun attachTracks(
        context: Context,
        fs: SftpFileSystem,
        drives: List<DriveInfo>,
    ): List<DriveInfo> {
        // 空光驱/坏光驱的卷标读不到，不能再靠 "Audio CD" 卷标决定要不要读 TOC；
        // 只要 SCSI 说是 CD 就问一次 TOC，数据 CD 会返回 audio=false、tracks=[]。
        val letters = drives
            .filter { it.isOptical && it.optical == OpticalMedia.CD && it.tracks.isEmpty() }
            .map { it.letter }
        if (letters.isEmpty()) return drives
        val byLetter = loadTracks(context, fs, letters) ?: return drives
        return drives.map { d ->
            val t = byLetter[d.letter.uppercase()] ?: return@map d
            d.copy(audioCd = t.first, tracks = t.second)
        }
    }

    private fun parseDrives(json: String): List<DriveInfo> {
        val arr = JSONArray(jsonText(json))
        val list = ArrayList<DriveInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val driveType = o.optInt("driveType", 0)
            val kind = when (o.optString("kind", "")) {
                "ssd" -> DriveKind.SSD
                "hdd" -> DriveKind.HDD
                "usb" -> DriveKind.USB
                "floppy" -> DriveKind.FLOPPY
                "optical" -> DriveKind.OPTICAL
                "disk" -> if (driveType == 2) DriveKind.FLOPPY else DriveKind.HDD
                else -> DriveKind.UNKNOWN
            }
            val videoTs = o.optBoolean("videoTs", false)
            // 第一段只能按容量猜 CD/DVD/BD，第二段会用 SCSI 的 profile 覆盖掉
            val media = when (o.optString("media", "")) {
                "cd" -> OpticalMedia.CD
                "dvd" -> if (videoTs) OpticalMedia.DVD_VIDEO else OpticalMedia.DVD_DATA
                "bd" -> OpticalMedia.BD
                else -> OpticalMedia.NONE
            }
            list += DriveInfo(
                letter = o.optString("letter", ""),
                label = o.optString("label", ""),
                kind = kind,
                optical = media,
                sizeBytes = o.optLong("sizeBytes", 0),
                freeBytes = o.optLong("freeBytes", 0),
                busType = o.optString("bus", ""),
                videoTs = videoTs,
            )
        }
        return list
    }

    /** 第二段的返回：letter → 物理介质 + 视频 DVD 标记。 */
    private fun parseOptical(json: String): Map<String, OpticalProbe> {
        val arr = JSONArray(jsonText(json))
        val map = HashMap<String, OpticalProbe>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val media = when (o.optString("media", "")) {
                "cd" -> OpticalMedia.CD
                "dvd" -> OpticalMedia.DVD_DATA
                "bd" -> OpticalMedia.BD
                "empty" -> OpticalMedia.NONE
                else -> continue
            }
            map[o.optString("letter", "").uppercase()] = OpticalProbe(
                media = media,
                videoTs = o.optBoolean("videoTs", false),
                label = o.optString("label", ""),
            )
        }
        return map
    }

    /** 第三段的返回：letter → (是否纯音频轨, 音轨表)。 */
    private fun parseTracks(json: String): Map<String, Pair<Boolean, List<CdTrack>>> {
        val arr = JSONArray(jsonText(json))
        val map = HashMap<String, Pair<Boolean, List<CdTrack>>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val tracks = ArrayList<CdTrack>()
            val arrTracks = o.optJSONArray("tracks")
            if (arrTracks != null) {
                for (j in 0 until arrTracks.length()) {
                    val t = arrTracks.optJSONObject(j) ?: continue
                    val sectors = t.optLong("sectors", 0)
                    if (sectors <= 0) continue
                    tracks += CdTrack(
                        number = t.optInt("n", j + 1),
                        startLba = t.optLong("lba", 0),
                        sectors = sectors,
                    )
                }
            }
            map[o.optString("letter", "").uppercase()] = o.optBoolean("audio", false) to tracks
        }
        return map
    }

    /** 远端可能先吐几行进度/警告，取第一行以 [ 开头的。 */
    private fun jsonText(stdout: String): String =
        stdout.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("[") }
            ?: throw IllegalStateException("PC 没返回 JSON：${stdout.take(200)}")
}
