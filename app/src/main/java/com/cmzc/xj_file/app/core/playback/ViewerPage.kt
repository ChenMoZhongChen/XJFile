package com.cmzc.xj_file.app.core.playback

import com.cmzc.xj_file.app.core.fs.RemoteFile

/**
 * 查看页里的一项：文件本身 + 已经造好的数据源。
 *
 * 数据源很轻（只是 fs 引用 + 路径），所以整个目录的项可以一次全建出来，
 * 翻页时不用再去 ViewModel 里问「这一项怎么读」。
 */
data class ViewerPage(
    val file: RemoteFile,
    val source: PlaybackSource,
)
