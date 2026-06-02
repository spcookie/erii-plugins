@file:Definition(pluginId = "chat-heatmap", version = "0.0.1", description = "聊天热力图插件")

package uesugi.plugin.heatmap

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.selectAll
import uesugi.common.data.HistoryTable
import uesugi.common.data.MessageType
import uesugi.common.toolkit.BrowserScraper
import uesugi.common.toolkit.BrowserScraperHolder
import uesugi.onebot.sdk.client.api.sendGroupMsg
import uesugi.onebot.sdk.message.buildMessage
import uesugi.spi.Meta
import uesugi.spi.annotation.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.days

private val log = KotlinLogging.logger {}

// ========== Color Schemes ==========

private data class ColorScheme(val name: String, val colors: List<String>)

private val COLOR_SCHEMES = listOf(
    ColorScheme("github", listOf("#161b22", "#0e4429", "#006d32", "#26a641", "#39d353")),
    ColorScheme("ocean", listOf("#161b22", "#0c2d48", "#14517a", "#1f77b4", "#58a6ff")),
    ColorScheme("sunset", listOf("#161b22", "#3d1c00", "#7a3b1e", "#cc6b2c", "#f0883e")),
    ColorScheme("purple", listOf("#161b22", "#1f1438", "#462868", "#7146a3", "#9e6ad6")),
    ColorScheme("rose", listOf("#161b22", "#3d1a2a", "#702845", "#b34068", "#e06c8f"))
)

// ========== Static Resource Cache ==========

private class HeatmapResources(val d3js: String, val calHeatmapJs: String, val calHeatmapCss: String)

private val heatmapRes by lazy {
    val loader = Thread.currentThread().contextClassLoader
    HeatmapResources(
        d3js = loader.getResourceAsStream("static/d3.v7.min.js")!!.readBytes().toString(Charsets.UTF_8),
        calHeatmapJs = loader.getResourceAsStream("static/cal-heatmap.min.js")!!.readBytes().toString(Charsets.UTF_8),
        calHeatmapCss = loader.getResourceAsStream("static/cal-heatmap.css")!!.readBytes().toString(Charsets.UTF_8)
    )
}

private val htmlTemplate by lazy {
    Thread.currentThread().contextClassLoader
        .getResourceAsStream("heatmap-template.html")!!
        .readBytes().toString(Charsets.UTF_8)
}

// ========== Server Route ==========

private val heatmapCache = ConcurrentHashMap<String, String>()
@Volatile private var serverRouteReady = false
private val serverRouteLock = Any()

private val externalUrl: String
    get() = System.getProperty("browser.external-url") ?: "hostmachine"

private suspend fun ensureServerRoute() {
    if (serverRouteReady) return
    val server = useServer()
    synchronized(serverRouteLock) {
        if (serverRouteReady) return
        serverRouteReady = true
    }
    server.route {
        get("/heatmap/{id}") {
            val id = call.parameters["id"]!!
            val html = heatmapCache[id]
            if (html != null) call.respondText(html, ContentType.Text.Html)
            else call.respondText("Not found", ContentType.Text.Plain)
        }
    }
}

// ========== Data Model ==========

private data class HeatmapData(
    val nickname: String,
    val dailyCounts: Map<String, Int>,
    val totalWords: Int,
    val todayWords: Int,
    val rank: Int,
    val totalUsers: Int,
    val startDate: String,
    val endDate: String,
    val isGroup: Boolean
)

// ========== Commands ==========

@Cmd(name = "heatmap", alias = ["热力图"], toolSets = ["heatmap"])
suspend fun heatmapCmd(args: List<String>, meta: Meta) {
    log.info { "/heatmap triggered by ${meta.senderId} in group ${meta.groupId}" }
    generateAndSendHeatmap(meta, isGroup = false)
}

@Cmd(name = "heatmap-all", alias = ["热力图全群"], toolSets = ["heatmap"])
suspend fun heatmapAllCmd(args: List<String>, meta: Meta) {
    log.info { "/heatmap-all triggered by ${meta.senderId} in group ${meta.groupId}" }
    generateAndSendHeatmap(meta, isGroup = true)
}

// ========== Tools (LLM) ==========

@Tool(set = "heatmap")
@LLMDescription("当用户想查看自己或\"我\"的聊天活跃度、发言统计、热力图时调用，发送一张个人发言热力图")
suspend fun getMyHeatmap(): String? {
    val meta = useToolMeta().value
    generateAndSendHeatmap(meta, isGroup = false)
    return "热力图已生成"
}

@Tool(set = "heatmap")
@LLMDescription("当用户想查看全群或\"大家\"的聊天活跃度、发言统计时调用，发送一张全群发言热力图")
suspend fun getGroupHeatmap(): String? {
    val meta = useToolMeta().value
    generateAndSendHeatmap(meta, isGroup = true)
    return "全群热力图已生成"
}

// ========== Core Logic ==========

private suspend fun generateAndSendHeatmap(meta: Meta, isGroup: Boolean) {
    val groupId = meta.groupId
    val userId = meta.senderId ?: return
    val groupIdLong = groupId.toLong()

    val data = if (isGroup) loadGroupHeatmapData(groupId) else loadHeatmapData(groupId, userId)

    val scheme = COLOR_SCHEMES.random()
    val html = buildHtml(data, scheme)

    ensureServerRoute()

    val screenshot = withContext(Dispatchers.IO) {
        takeScreenshot(html)
    }

    if (screenshot != null) {
        val base64 = Base64.getEncoder().encodeToString(screenshot)
        meta.roledBot.refBot.sendGroupMsg(groupIdLong, buildMessage { image("base64://$base64") })
        log.info { "Heatmap sent to group $groupId" }
    } else {
        log.warn { "Screenshot failed" }
    }
}

private suspend fun loadHeatmapData(groupId: String, userId: String): HeatmapData {
    val database = useDatabase()
    val now = Clock.System.now()
    val oneYearAgo = now - 365.days
    val timeZone = TimeZone.currentSystemDefault()
    val startLDT = oneYearAgo.toLocalDateTime(timeZone)
    val endLDT = now.toLocalDateTime(timeZone)

    val allRecords = database.getHistory {
        HistoryTable.selectAll().where {
            (HistoryTable.groupId eq groupId) and
                    (HistoryTable.createdAt greaterEq startLDT) and
                    (HistoryTable.createdAt lessEq endLDT) and
                    (HistoryTable.messageType eq MessageType.TEXT)
        }
    }

    val userRecords = allRecords.filter { it.userId == userId }

    val dailyCounts = userRecords.groupBy {
        "${it.createdAt.year}-${pad(it.createdAt.monthNumber)}-${pad(it.createdAt.dayOfMonth)}"
    }.mapValues { (_, msgs) -> msgs.sumOf { it.content?.length ?: 0 } }

    val totalWords = dailyCounts.values.sum()
    val todayKey = "${endLDT.year}-${pad(endLDT.monthNumber)}-${pad(endLDT.dayOfMonth)}"
    val todayWords = dailyCounts[todayKey] ?: 0

    val allUserWordCounts = allRecords.groupBy { it.userId }
        .mapValues { (_, msgs) -> msgs.sumOf { it.content?.length ?: 0 } }
        .entries.sortedByDescending { it.value }

    val rank = allUserWordCounts.indexOfFirst { it.key == userId } + 1
    val totalUsers = allUserWordCounts.size
    val nickname = allRecords.firstOrNull { it.userId == userId }?.nick ?: "用户"

    return HeatmapData(nickname, dailyCounts, totalWords, todayWords, rank, totalUsers,
        "${startLDT.year}/${pad(startLDT.monthNumber)}", "${endLDT.year}/${pad(endLDT.monthNumber)}", false)
}

private suspend fun loadGroupHeatmapData(groupId: String): HeatmapData {
    val database = useDatabase()
    val now = Clock.System.now()
    val oneYearAgo = now - 365.days
    val timeZone = TimeZone.currentSystemDefault()
    val startLDT = oneYearAgo.toLocalDateTime(timeZone)
    val endLDT = now.toLocalDateTime(timeZone)

    val allRecords = database.getHistory {
        HistoryTable.selectAll().where {
            (HistoryTable.groupId eq groupId) and
                    (HistoryTable.createdAt greaterEq startLDT) and
                    (HistoryTable.createdAt lessEq endLDT) and
                    (HistoryTable.messageType eq MessageType.TEXT)
        }
    }

    val dailyCounts = allRecords.groupBy {
        "${it.createdAt.year}-${pad(it.createdAt.monthNumber)}-${pad(it.createdAt.dayOfMonth)}"
    }.mapValues { (_, msgs) -> msgs.sumOf { it.content?.length ?: 0 } }

    val totalWords = dailyCounts.values.sum()
    val todayKey = "${endLDT.year}-${pad(endLDT.monthNumber)}-${pad(endLDT.dayOfMonth)}"
    val todayWords = dailyCounts[todayKey] ?: 0

    return HeatmapData("全群", dailyCounts, totalWords, todayWords, 0, allRecords.map { it.userId }.distinct().size,
        "${startLDT.year}/${pad(startLDT.monthNumber)}", "${endLDT.year}/${pad(endLDT.monthNumber)}", true)
}

private fun takeScreenshot(html: String): ByteArray? {
    val id = UUID.randomUUID().toString()
    heatmapCache[id] = html
    return try {
        BrowserScraperHolder.getInstance().takeFullScreenshot(
            url = "http://$externalUrl:8888/plugin/chat-heatmap/heatmap/$id",
            width = 860,
            height = 640,
            quality = 90,
            type = BrowserScraper.ScreenshotType.JPEG,
            waitForNetworkIdle = true
        )
    } catch (e: Exception) {
        log.error(e) { "Screenshot failed: ${e.message}" }
        null
    } finally {
        heatmapCache.remove(id)
    }
}

// ========== HTML Builder ==========

private fun buildHtml(data: HeatmapData, scheme: ColorScheme): String {
    val r = heatmapRes
    val colorsJson = scheme.colors.joinToString(", ") { "\"$it\"" }.let { "[$it]" }

    val dailyCountsJson = buildString {
        append("{")
        data.dailyCounts.entries.joinTo(this) { (date, count) -> "\"$date\":$count" }
        append("}")
    }

    val rankSection = if (!data.isGroup) """
            <div class="stat-item">
              <span class="label">群内排名</span>
              <span class="rank-note" id="rankValue">群内<em>#${data.rank}</em> · 共 ${data.totalUsers} 人</span>
            </div>""" else """
            <div class="stat-item">
              <span class="label">活跃成员</span>
              <span class="rank-note">共 <em>${data.totalUsers}</em> 人参与</span>
            </div>"""

    return htmlTemplate
        .replace("{{calHeatmapCss}}", r.calHeatmapCss)
        .replace("{{d3js}}", r.d3js)
        .replace("{{calHeatmapJs}}", r.calHeatmapJs)
        .replace("{{nickname}}", data.nickname)
        .replace("{{startDate}}", data.startDate)
        .replace("{{endDate}}", data.endDate)
        .replace("{{startDateJs}}", data.startDate.replace("/", "-"))
        .replace("{{endDateJs}}", data.endDate.replace("/", "-"))
        .replace("{{totalWords}}", formatNum(data.totalWords))
        .replace("{{todayWords}}", formatNum(data.todayWords))
        .replace("{{rankSection}}", rankSection)
        .replace("{{colors}}", colorsJson)
        .replace("{{dailyCounts}}", dailyCountsJson)
}

private fun formatNum(n: Int): String = if (n >= 10000) "${"%.1f".format(n / 10000.0)}万" else n.toString()

private fun pad(n: Int): String = n.toString().padStart(2, '0')
