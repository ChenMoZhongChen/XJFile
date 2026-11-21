package com.cmzc.xj_file.app.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.cmzc.xj_file.app.core.cd.WavHeader
import com.cmzc.xj_file.app.core.image.AudioInfo
import com.cmzc.xj_file.app.core.image.AudioMetadata
import com.cmzc.xj_file.app.core.playback.PlaybackEngine
import com.cmzc.xj_file.app.core.playback.PlaybackSource
import com.cmzc.xj_file.app.core.playback.ViewerPage
import kotlinx.coroutines.delay

/**
 * 音频页（没有画面，所以不用 PlayerView）。
 *
 * 显示封面 + 标签 + 进度条 + 播放控制。所有信息都从文件内嵌标签里读；
 * 读不到就退回「文件名 + 音符图标」——绝不能因为标签缺失就出现大片空白。
 *
 * [tracks] 非空时在控制条下面接一段可滚动的播放列表：
 * 音频 CD 是 TOC 里的每条音轨；普通音频是这个文件夹里的全部音频文件。
 * 用户不用来回点「下一首」，点列表任意一项就能直接跳过去。
 */
@OptIn(UnstableApi::class)
@Composable
fun AudioPlayerSurface(
    player: ExoPlayer,
    page: ViewerPage,
    canPrev: Boolean,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    tracks: List<ViewerPage> = emptyList(),
    currentIndex: Int = 0,
    onSelectTrack: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    // 音频 CD 走的是「远端光驱流」：没有容器、没有内嵌标签，读标签只会白等一次超时。
    val isCd = page.source is PlaybackSource.Cdda
    val withTrackList = tracks.isNotEmpty()
    var info by remember(page.file.path) { mutableStateOf<AudioInfo?>(null) }
    var position by remember(page.file.path) { mutableLongStateOf(0L) }
    var duration by remember(page.file.path) { mutableLongStateOf(0L) }
    var playing by remember(page.file.path) { mutableStateOf(false) }
    // 拖动进度条时先跟手，松手才真 seek，否则每帧都在读网络
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(page.file.path) {
        info = if (isCd) null else AudioMetadata.load(context, page)
    }

    LaunchedEffect(player, page.file.path) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player.addListener(listener)
        playing = player.isPlaying
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.takeIf { it > 0 } ?: 0L
            delay(400)
        }
    }

    val shownPosition = if (dragging) (dragFraction * duration).toLong() else position

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = if (withTrackList) 20.dp else 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 有曲目列表时版面要收紧：上面给正在播的那首，下面留给列表。
        // 顶栏高度 = 状态栏 inset + 48dp，所以这里不能用固定值，否则封面会被顶栏压住。
        if (withTrackList) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(52.dp))
        } else {
            Spacer(Modifier.weight(0.7f))
        }

        // 封面：有就用，没有就一个音符占位
        Box(
            modifier = Modifier
                .fillMaxWidth(if (withTrackList) 0.42f else 0.72f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF1C1C1C)),
            contentAlignment = Alignment.Center
        ) {
            val cover = info?.cover
            if (cover != null) {
                Image(
                    bitmap = cover.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = Color(0xFF5A5A5A),
                    modifier = Modifier.size(if (withTrackList) 64.dp else 96.dp)
                )
            }
        }

        Spacer(Modifier.height(if (withTrackList) 16.dp else 28.dp))

        Text(
            text = info?.title?.takeIf { it.isNotBlank() } ?: page.file.name,
            color = Color.White,
            fontSize = if (withTrackList) 17.sp else 19.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val subtitle = if (isCd) "音频 CD" else listOfNotNull(
            info?.artist?.takeIf { it.isNotBlank() },
            info?.album?.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
        if (subtitle.isNotEmpty()) {
            Spacer(Modifier.height(if (withTrackList) 4.dp else 6.dp))
            Text(
                text = subtitle,
                color = Color(0xFF9A9A9A),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(if (withTrackList) 12.dp else 26.dp))

        Slider(
            value = if (duration > 0) (shownPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f,
            onValueChange = { fraction ->
                dragging = true
                dragFraction = fraction
            },
            onValueChangeFinished = {
                if (duration > 0) player.seekTo((dragFraction * duration).toLong())
                dragging = false
            },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color(0xFF3A3A3A)
            )
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(shownPosition), color = Color(0xFF9A9A9A), fontSize = 12.sp)
            Text(formatTime(duration), color = Color(0xFF9A9A9A), fontSize = 12.sp)
        }

        Spacer(Modifier.height(if (withTrackList) 4.dp else 10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrev, enabled = canPrev) {
                Icon(
                    Icons.Filled.SkipPrevious, "上一个",
                    tint = if (canPrev) Color.White else Color(0x55FFFFFF),
                    modifier = Modifier.size(if (withTrackList) 30.dp else 36.dp)
                )
            }
            Spacer(Modifier.size(if (withTrackList) 14.dp else 18.dp))
            Box(
                modifier = Modifier
                    .size(if (withTrackList) 62.dp else 72.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF2A2A2A)),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = {
                    if (player.isPlaying || player.playWhenReady) {
                        player.pause()
                    } else {
                        // 通知栏「停止」后播放器停在 IDLE，直接 play() 不会重新准备数据源；
                        // 交给 PlaybackEngine：它负责 prepare 和重新拉起前台服务。
                        PlaybackEngine.play()
                    }
                }) {
                    Icon(
                        imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(if (withTrackList) 34.dp else 40.dp)
                    )
                }
            }
            Spacer(Modifier.size(if (withTrackList) 14.dp else 18.dp))
            IconButton(onClick = onNext, enabled = canNext) {
                Icon(
                    Icons.Filled.SkipNext, "下一个",
                    tint = if (canNext) Color.White else Color(0x55FFFFFF),
                    modifier = Modifier.size(if (withTrackList) 30.dp else 36.dp)
                )
            }
        }

        if (withTrackList) {
            Spacer(Modifier.height(10.dp))
            TrackList(
                tracks = tracks,
                currentIndex = currentIndex,
                currentPlaying = playing,
                onSelect = onSelectTrack,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.height(12.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * 播放列表（音频 CD 的曲目 / 普通音频的文件夹音频列表）。
 *
 * CD 的时长用音轨的 PCM 字节数算：一条轨在包装成 wav 时大小是固定的
 * （16bit/44.1kHz/立体声 = 176400 字节/秒），所以文件大小减掉 44 字节头再除
 * 就是秒数 —— 和播放页顶部的总时长完全一致。
 *
 * 普通音频的时长从容器标签里读，且**按行懒加载**：文件夹里可能几十上百首，
 * 一进来就全读标签会卡；只给真正滚进视野的行读，读完缓存。
 */
@Composable
private fun TrackList(
    tracks: List<ViewerPage>,
    currentIndex: Int,
    currentPlaying: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    // path → durationMs；LazyColumn 滚出再滚回来不重复读标签
    val durationCache = remember { mutableStateMapOf<String, Long>() }

    // 切歌后自动把正在播的那首滚进视野，几十首的文件夹也不用自己找
    LaunchedEffect(currentIndex) {
        if (currentIndex in tracks.indices) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    LazyColumn(modifier = modifier.fillMaxWidth(), state = listState) {
        itemsIndexed(tracks) { index, item ->
            val current = index == currentIndex
            val isCd = item.source is PlaybackSource.Cdda
            // 只有普通音频需要读容器标签；CD 用 TOC 算，不能去打开一条裸 PCM 流
            LaunchedEffect(item.file.path, isCd) {
                if (!isCd && durationCache[item.file.path] == null) {
                    AudioMetadata.duration(context, item)?.let { durationCache[item.file.path] = it }
                }
            }
            val durationMs = if (isCd) cdTrackDurationMs(item) else durationCache[item.file.path]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (current) Color(0xFF232323) else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 10.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = (index + 1).toString().padStart(2, '0'),
                    color = if (current) Color(0xFF8CE5AB) else Color(0xFF6E6E6E),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(28.dp)
                )
                Text(
                    text = item.file.name,
                    color = if (current) Color.White else Color(0xFFCFCFCF),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (current) {
                    Icon(
                        imageVector = if (currentPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color(0xFF8CE5AB),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    // 普通音频的标签还没读出来（或读不到）时先显示 --:--，不能拿文件大小硬算
                    text = durationMs?.let { formatTime(it) } ?: "--:--",
                    color = if (current) Color(0xFFBDBDBD) else Color(0xFF8A8A8A),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/** 音频 CD 一条轨的时长（TOC 的扇区数 → 毫秒）。 */
private fun cdTrackDurationMs(item: ViewerPage): Long {
    val pcmBytes = (item.file.size - WavHeader.SIZE).coerceAtLeast(0L)
    return pcmBytes * 1000L / (WavHeader.SAMPLE_RATE * WavHeader.CHANNELS * WavHeader.BITS_PER_SAMPLE / 8)
}

/** 毫秒 → m:ss（超过一小时才显示 h:mm:ss）。 */
private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}
