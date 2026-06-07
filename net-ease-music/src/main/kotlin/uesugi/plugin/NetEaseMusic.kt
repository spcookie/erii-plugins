@file:Definition(pluginId = "net-ease-music", version = "0.0.1", description = "网易云音乐插件")

package uesugi.plugin

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uesugi.common.BotManage
import uesugi.common.ChatMessage
import uesugi.spi.annotation.*

private val log = KotlinLogging.logger {}
private var MUSIC_API_BASE: String? = null

private suspend fun ensureApiBase() {
    if (MUSIC_API_BASE == null) {
        MUSIC_API_BASE = useConfig()().getString("api-base")
    }
}

// ========== Tool ==========

@ChatMessage
@LLMTool
@LLMDesc("当用户想要搜索或点播音乐时，调用此 tool 搜索音乐并发送音乐")
suspend fun searchMusic(keyword: String, limit: Int = 5): String? {
    log.info { "Search music keyword: $keyword" }
    if (keyword.isBlank()) {
        return "未提取到关键词"
    }

    ensureApiBase()

    var limitVal = limit
    if (limitVal > 5) limitVal = 5

    val musicCards = search(keyword, limitVal)
    log.info { "Found ${musicCards.size} music cards for keyword: $keyword" }

    return sendMusicCards(musicCards)
}

// ========== Private helpers ==========

private suspend fun search(keyword: String, limit: Int = 5): List<MusicCardResult> {
    return withContext(Dispatchers.IO) {
        try {
            val http = useHttp()
            val response = http.get("$MUSIC_API_BASE/search") {
                parameter("keywords", keyword)
                parameter("limit", limit)
                parameter("type", 1)
            }

            val result = response.body<MusicSearchResult>()

            val songs = result.result?.songs
            if (songs.isNullOrEmpty()) {
                return@withContext emptyList()
            }

            songs.map { song ->
                val musicUrl = getMusicUrl(song.id)
                MusicCardResult.fromSong(song, musicUrl)
            }
        } catch (e: Exception) {
            log.error(e) { "搜索音乐失败: ${e.message}" }
            emptyList()
        }
    }
}

private suspend fun getMusicUrl(songId: Long): String? {
    return withContext(Dispatchers.IO) {
        try {
            val http = useHttp()
            val response = http.get("$MUSIC_API_BASE/song/url") {
                parameter("id", songId)
            }

            val result = response.body<MusicUrlResult>()
            result.data?.firstOrNull()?.url
        } catch (e: Exception) {
            log.error(e) { "获取音乐URL失败: ${e.message}" }
            null
        }
    }
}

private suspend fun sendMusicCards(musicCards: List<MusicCardResult>): String? {
    val meta = useToolMeta().value
    val config = useConfig()
    val configKey = BotManage.getConfigKey(meta.botId)
    val cfg = config().getConfig("onebot.$configKey")
    val httpUrl = cfg.getString("http-url")
    val token = cfg.getString("token")!!.let { config.findEnv(it)!! }
    val http = useHttp()

    for (cardResult in musicCards) {
        try {
            http.post("$httpUrl/send_msg") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(
                    mapOf(
                        "message_type" to "group",
                        "group_id" to meta.groupId,
                        "message" to listOf(
                            mapOf(
                                "type" to "music",
                                "data" to mapOf(
                                    "type" to "163",
                                    "id" to cardResult.id
                                )
                            )
                        )
                    )
                )
            }
        } catch (e: Exception) {
            log.error(e) { "发送音乐卡片失败: ${e.message}" }
            return "发送音乐卡片失败: ${e.message}"
        }
    }
    return "已发送音乐卡片"
}

// ========== Data classes ==========

@JsonIgnoreProperties(ignoreUnknown = true)
data class MusicSearchResult(
    @field:JsonProperty("result") val result: SearchResult? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchResult(
    @field:JsonProperty("songs") val songs: List<Song>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Song(
    @field:JsonProperty("id") val id: Long = 0,
    @field:JsonProperty("name") val name: String = "",
    @field:JsonProperty("ar") val artists: List<Artist>? = null,
    @field:JsonProperty("al") val album: Album? = null,
    @field:JsonProperty("dt") val duration: Long = 0
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Artist(
    @field:JsonProperty("name") val name: String = "",
    @field:JsonProperty("id") val id: Long? = null,
    @field:JsonProperty("tns") val tns: List<String>? = null,
    @field:JsonProperty("alias") val alias: List<String>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Album(
    @field:JsonProperty("name") val name: String = "",
    @field:JsonProperty("picUrl") val picUrl: String = "",
    @field:JsonProperty("id") val id: Long? = null,
    @field:JsonProperty("pic") val pic: Long? = null,
    @field:JsonProperty("pic_str") val picStr: String? = null,
    @field:JsonProperty("tns") val tns: List<String>? = null,
    @field:JsonProperty("publishTime") val publishTime: Long? = null
)

data class MusicCardResult(
    val id: Long,
    val title: String,
    val summary: String,
    val jumpUrl: String,
    val pictureUrl: String,
    val musicUrl: String,
    val brief: String
) {
    companion object {
        private const val NETEASE_BASE_URL = "https://music.163.com"

        fun fromSong(song: Song, musicUrl: String? = null): MusicCardResult {
            val artists = song.artists?.joinToString("/") { it.name } ?: "未知歌手"
            val musicUrlFinal = musicUrl ?: "$NETEASE_BASE_URL/song/media/outer/url?id=${song.id}"

            return MusicCardResult(
                id = song.id,
                title = song.name,
                summary = artists,
                jumpUrl = "$NETEASE_BASE_URL/song/${song.id}/",
                pictureUrl = song.album?.picUrl ?: "",
                musicUrl = musicUrlFinal,
                brief = "[分享]${song.name}"
            )
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class MusicUrlResult(
    @field:JsonProperty("code") val code: Int = 0,
    @field:JsonProperty("data") val data: List<MusicUrlData>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MusicUrlData(
    @field:JsonProperty("id") val id: Long = 0,
    @field:JsonProperty("url") val url: String? = null
)
