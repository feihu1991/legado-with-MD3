package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.net.Uri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.CharacterVoice
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.mimo.MiMoTtsApi
import io.legado.app.model.ReadBook
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.File

/**
 * MiMo 多角色朗读服务
 */
@SuppressLint("UnsafeOptInUsageError")
class MiMoReadAloudService : BaseReadAloudService(),
    Player.Listener {

    companion object {
        const val PREF_KEY_MIMO_API_KEY = "mimo_tts_api_key"
        const val PREF_KEY_MIMO_BASE_URL = "mimo_tts_base_url"
    }

    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).build()
    }

    private val ttsFolderPath: String by lazy {
        val baseDir = externalCacheDir ?: cacheDir
        baseDir.absolutePath + File.separator + "mimoTTS" + File.separator
    }

    private val cache by lazy {
        val baseDir = externalCacheDir ?: cacheDir
        SimpleCache(
            File(baseDir, "mimoTTS_cache"),
            LeastRecentlyUsedCacheEvictor(256 * 1024 * 1024),
            StandaloneDatabaseProvider(appCtx)
        )
    }

    private var downloadTask: Coroutine<*>? = null
    private var playIndexJob: Job? = null
    private var playErrorNo = 0
    private val downloadTaskActiveLock = Mutex()

    // 当前章节的角色音色缓存
    private var chapterVoiceMap: Map<String, CharacterVoice> = emptyMap()

    override fun onCreate() {
        super.onCreate()
        exoPlayer.addListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadTask?.cancel()
        exoPlayer.release()
        cache.release()
        Coroutine.async {
            removeCacheFile()
        }
    }

    override fun play() {
        pageChanged = false
        exoPlayer.stop()
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
        } else {
            super.play()
            loadChapterVoices()
            downloadAndPlayAudios()
        }
    }

    override fun playStop() {
        exoPlayer.stop()
        playIndexJob?.cancel()
    }

    private fun loadChapterVoices() {
        val book = ReadBook.book ?: return
        val voices = appDb.characterVoiceDao.getByBookId(book.bookUrl.hashCode().toLong())
        chapterVoiceMap = voices.associateBy { it.characterName }
    }

    private fun getVoiceForText(text: String): CharacterVoice {
        // 默认使用旁白音色
        val narrator = chapterVoiceMap["旁白"]
            ?: chapterVoiceMap.values.firstOrNull { it.isNarrator }
            ?: CharacterVoice(
                bookId = 0,
                characterName = "旁白",
                isNarrator = true
            )

        // 检查是否是对话（包含引号）
        val dialogPattern = Regex("[\"「『【（(].*[\"」』】）)]")
        if (!dialogPattern.containsMatchIn(text)) {
            return narrator
        }

        // 尝试从文本中匹配已知角色名
        for ((name, voice) in chapterVoiceMap) {
            if (name != "旁白" && text.contains(name)) {
                return voice
            }
        }

        return narrator
    }

    private fun updateNextPos() {
        readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
        paragraphStartPos = 0
        if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
        } else {
            nextChapter()
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun downloadAndPlayAudios() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val apiKey = getApiKey()
                val baseUrl = getBaseUrl()

                contentList.forEachIndexed { index, content ->
                    ensureActive()
                    if (index < nowSpeak) return@forEachIndexed

                    var text = content
                    if (paragraphStartPos > 0 && index == nowSpeak) {
                        text = text.substring(paragraphStartPos)
                    }

                    val fileName = md5SpeakFileName(text)
                    val speakText = text.replace(AppPattern.notReadAloudRegex, "")

                    if (speakText.isEmpty()) {
                        createSilentSound(fileName)
                    } else if (!hasSpeakFile(fileName)) {
                        runCatching {
                            val voiceConfig = getVoiceForText(text)
                            val audioData = MiMoTtsApi.synthesize(
                                text = speakText,
                                voiceConfig = voiceConfig,
                                apiKey = apiKey,
                                baseUrl = baseUrl
                            )
                            createSpeakFile(fileName, audioData)
                        }.onFailure {
                            when (it) {
                                is CancellationException -> Unit
                                else -> {
                                    AppLog.put("MiMo TTS合成出错: ${it.localizedMessage}", it)
                                    createSilentSound(fileName)
                                }
                            }
                        }
                    }

                    val file = getSpeakFile(fileName)
                    val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                    launch(Dispatchers.Main) {
                        exoPlayer.addMediaItem(mediaItem)
                    }
                }
            }
        }.onError {
            AppLog.put("MiMo朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    private fun getApiKey(): String {
        return appCtx.getSharedPreferences("mimo_tts", 0)
            .getString(PREF_KEY_MIMO_API_KEY, "")
            ?: throw NoStackTraceException("请先配置 MiMo API Key")
    }

    private fun getBaseUrl(): String {
        return appCtx.getSharedPreferences("mimo_tts", 0)
            .getString(PREF_KEY_MIMO_BASE_URL, "https://api.xiaomimimo.com/v1")
            ?: "https://api.xiaomimimo.com/v1"
    }

    private fun md5SpeakFileName(content: String): String {
        val bookId = ReadBook.book?.bookUrl ?: ""
        return MD5Utils.md5Encode16("mimo_${bookId}_$content")
    }

    private fun hasSpeakFile(name: String): Boolean {
        return FileUtils.exist("${ttsFolderPath}$name.mp3")
    }

    private fun getSpeakFile(name: String): File {
        return File("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String, data: ByteArray) {
        FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3").outputStream().use { out ->
            ByteArrayInputStream(data).use { it.copyTo(out) }
        }
    }

    private fun createSilentSound(fileName: String) {
        val file = FileUtils.createFileIfNotExist("${ttsFolderPath}$fileName.mp3")
        file.writeBytes(resources.openRawResource(R.raw.silent_sound).readBytes())
    }

    private fun removeCacheFile() {
        val keepTime = io.legado.app.ui.config.readConfig.ReadTtsConfig.audioCacheCleanTime
        if (keepTime == 0L) {
            FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
                FileUtils.delete(it.absolutePath)
            }
        } else {
            FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
                if (System.currentTimeMillis() - it.lastModified() > keepTime) {
                    FileUtils.delete(it.absolutePath)
                }
            }
        }
    }

    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        kotlin.runCatching {
            playIndexJob?.cancel()
            exoPlayer.pause()
        }
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        kotlin.runCatching {
            if (pageChanged) {
                play()
            } else {
                exoPlayer.play()
                upPlayPos()
            }
        }
    }

    private fun upPlayPos() {
        playIndexJob?.cancel()
        val textChapter = textChapter ?: return
        playIndexJob = lifecycleScope.launch {
            upTtsProgress(readAloudNumber + 1)
            if (exoPlayer.duration <= 0) return@launch
            val speakTextLength = contentList[nowSpeak].length
            if (speakTextLength <= 0) return@launch
            val sleep = exoPlayer.duration / speakTextLength
            val start = speakTextLength * exoPlayer.currentPosition / exoPlayer.duration
            for (i in start..contentList[nowSpeak].length) {
                if (pageIndex + 1 < textChapter.pageSize
                    && readAloudNumber + i > textChapter.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage()
                    upTtsProgress(readAloudNumber + i.toInt())
                }
                delay(sleep)
            }
        }
    }

    override fun upSpeechRate(reset: Boolean) {
        downloadTask?.cancel()
        exoPlayer.stop()
        downloadAndPlayAudios()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        when (playbackState) {
            Player.STATE_READY -> {
                if (pause) return
                exoPlayer.play()
                upPlayPos()
            }
            Player.STATE_ENDED -> {
                playErrorNo = 0
                updateNextPos()
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            }
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        when (reason) {
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED -> {
                if (!timeline.isEmpty && exoPlayer.playbackState == Player.STATE_IDLE) {
                    exoPlayer.prepare()
                }
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            playErrorNo = 0
        }
        updateNextPos()
        upPlayPos()
        upMediaMetadata(showContent = true)
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        AppLog.put("MiMo朗读错误\n${contentList[nowSpeak]}", error)
        playErrorNo++
        if (playErrorNo >= 5) {
            toastOnUi("MiMo朗读连续5次错误, 已暂停")
            pauseReadAloud()
        } else {
            if (exoPlayer.hasNextMediaItem()) {
                exoPlayer.seekToNextMediaItem()
                exoPlayer.prepare()
            } else {
                exoPlayer.clearMediaItems()
                updateNextPos()
            }
        }
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<MiMoReadAloudService>(actionStr)
    }
}
