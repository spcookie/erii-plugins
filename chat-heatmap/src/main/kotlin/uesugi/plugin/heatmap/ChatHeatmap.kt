@file:Definition(pluginId = "chat-heatmap", version = "0.0.1", description = "聊天热力图插件")

package uesugi.plugin.heatmap

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import uesugi.common.data.HistoryTable
import uesugi.common.data.MessageType
import uesugi.common.toolkit.BrowserScraper
import uesugi.common.toolkit.BrowserScraperHolder
import uesugi.onebot.sdk.client.api.sendGroupMsg
import uesugi.onebot.sdk.message.buildMessage
import uesugi.spi.Meta
import uesugi.spi.annotation.*
import java.util.*
import kotlin.time.Duration.Companion.days

private val log = KotlinLogging.logger {}

// ========== Color Schemes ==========

private data class ColorScheme(val name: String, val colors: List<String>)

private val COLOR_SCHEMES = listOf(
    ColorScheme("github", listOf("#ebedf0", "#9be9a8", "#40c463", "#30a14e", "#216e39")),
    ColorScheme("ocean", listOf("#ebedf0", "#a5d8ff", "#58a6ff", "#1f77b4", "#0c2d48")),
    ColorScheme("sunset", listOf("#ebedf0", "#ffd8a8", "#f0883e", "#cc6b2c", "#7a3b1e")),
    ColorScheme("purple", listOf("#ebedf0", "#d4b8ff", "#9e6ad6", "#7146a3", "#462868")),
    ColorScheme("rose", listOf("#ebedf0", "#ffb8c8", "#e06c8f", "#b34068", "#702845"))
)

// ========== Template Engine ==========

private val pluginClassLoader = object {}.javaClass.classLoader

private val templateEngine by lazy {
    val resolver = ClassLoaderTemplateResolver(pluginClassLoader).apply {
        prefix = ""
        suffix = ""
        templateMode = TemplateMode.HTML
        characterEncoding = "UTF-8"
        isCacheable = true
    }
    TemplateEngine().apply { setTemplateResolver(resolver) }
}

// ========== Server Route ==========

@Volatile
private var serverRouteReady = false
private val serverRouteLock = Any()

private suspend fun ensureServerRoute() {
    if (serverRouteReady) return
    val capturedMem = useMem()
    val server = useServer()
    synchronized(serverRouteLock) {
        if (serverRouteReady) return
        serverRouteReady = true
    }
    server.route {
        val loader = pluginClassLoader
        get("/static/d3.v7.min.js") {
            call.respondBytes(
                loader.getResourceAsStream("static/d3.v7.min.js")!!.readBytes(),
                ContentType.Application.JavaScript
            )
        }
        get("/static/cal-heatmap.min.js") {
            call.respondBytes(
                loader.getResourceAsStream("static/cal-heatmap.min.js")!!.readBytes(),
                ContentType.Application.JavaScript
            )
        }
        get("/static/cal-heatmap.css") {
            call.respondBytes(loader.getResourceAsStream("static/cal-heatmap.css")!!.readBytes(), ContentType.Text.CSS)
        }
        get("/heatmap/{id}") {
            val id = call.parameters["id"]!!
            val html = capturedMem.get(id)
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
suspend fun heatmapCmd(meta: Meta) {
    log.info { "/heatmap triggered by ${meta.senderId} in group ${meta.groupId}" }
    generateAndSendHeatmap(meta, isGroup = false)
}

@Cmd(name = "heatmap-all", alias = ["热力图全群"], toolSets = ["heatmap"])
suspend fun heatmapAllCmd(meta: Meta) {
    log.info { "/heatmap-all triggered by ${meta.senderId} in group ${meta.groupId}" }
    generateAndSendHeatmap(meta, isGroup = true)
}

// ========== Tools (LLM) ==========

@LLMTool(set = "heatmap")
@LLMDesc("当用户想查看自己或\"我\"的聊天活跃度、发言统计、热力图时调用，发送一张个人发言热力图")
suspend fun getMyHeatmap(): String {
    val meta = useToolMeta().value
    generateAndSendHeatmap(meta, isGroup = false)
    return "热力图已生成"
}

@LLMTool(set = "heatmap")
@LLMDesc("当用户想查看全群或\"大家\"的聊天活跃度、发言统计时调用，发送一张全群发言热力图")
suspend fun getGroupHeatmap(): String {
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

    log.info { "Heatmap data loaded: totalWords=${data.totalWords}, days=${data.dailyCounts.size}, isGroup=$isGroup" }

    val scheme = COLOR_SCHEMES.random()
    val html = buildHtml(data, scheme)

    ensureServerRoute()

    val id = UUID.randomUUID().toString()
    useMem().set(id, html)

    val screenshot = withContext(Dispatchers.IO) {
        takeScreenshot(id)
    }

    useMem().delete(id)

    if (screenshot != null) {
        val base64 = Base64.getEncoder().encodeToString(screenshot)
        meta.roledBot.refBot.sendGroupMsg(groupIdLong, buildMessage { image("base64://$base64") })
        log.info { "Heatmap sent to group $groupId" }
    } else {
        log.warn { "Screenshot failed" }
    }
}

private suspend fun takeScreenshot(id: String): ByteArray? {
    val url = URLBuilder().takeFrom(useServer().url.buildString()).apply {
        appendPathSegments("heatmap", id)
    }.buildString()
    return try {
        BrowserScraperHolder.getInstance().takeFullScreenshot(
            url = url,
            width = 780,
            height = 640,
            quality = 100,
            type = BrowserScraper.ScreenshotType.JPEG,
            waitForNetworkIdle = true
        )
    } catch (e: Exception) {
        log.error(e) { "Screenshot failed: ${e.message}" }
        null
    }
}

private suspend fun loadHeatmapData(groupId: String, userId: String): HeatmapData {
    val database = useDatabase()
    val now = kotlin.time.Clock.System.now()
    val halfYearAgo = now - 183.days
    val timeZone = TimeZone.currentSystemDefault()
    val startLDT = halfYearAgo.toLocalDateTime(timeZone)
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
        "${it.createdAt.year}-${pad(it.createdAt.month.number)}-${pad(it.createdAt.day)}"
    }.mapValues { (_, msgs) -> msgs.sumOf { it.content?.length ?: 0 } }

    val totalWords = dailyCounts.values.sum()
    val todayKey = "${endLDT.year}-${pad(endLDT.month.number)}-${pad(endLDT.day)}"
    val todayWords = dailyCounts[todayKey] ?: 0

    val allUserWordCounts = allRecords.groupBy { it.userId }
        .mapValues { (_, msgs) -> msgs.sumOf { it.content?.length ?: 0 } }
        .entries.sortedByDescending { it.value }

    val rank = allUserWordCounts.indexOfFirst { it.key == userId } + 1
    val totalUsers = allUserWordCounts.size
    val nickname = allRecords.firstOrNull { it.userId == userId }?.nick ?: "用户"

    return HeatmapData(
        nickname, dailyCounts, totalWords, todayWords, rank, totalUsers,
        "${startLDT.year}/${pad(startLDT.month.number)}", "${endLDT.year}/${pad(endLDT.month.number)}", false
    )
}

private suspend fun loadGroupHeatmapData(groupId: String): HeatmapData {
    val database = useDatabase()
    val now = kotlin.time.Clock.System.now()
    val halfYearAgo = now - 183.days
    val timeZone = TimeZone.currentSystemDefault()
    val startLDT = halfYearAgo.toLocalDateTime(timeZone)
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
        "${it.createdAt.year}-${pad(it.createdAt.month.number)}-${pad(it.createdAt.day)}"
    }.mapValues { (_, msgs) -> msgs.sumOf { it.content?.length ?: 0 } }

    val totalWords = dailyCounts.values.sum()
    val todayKey = "${endLDT.year}-${pad(endLDT.month.number)}-${pad(endLDT.day)}"
    val todayWords = dailyCounts[todayKey] ?: 0

    return HeatmapData(
        "全群", dailyCounts, totalWords, todayWords, 0, allRecords.map { it.userId }.distinct().size,
        "${startLDT.year}/${pad(startLDT.month.number)}", "${endLDT.year}/${pad(endLDT.month.number)}", true
    )
}

// ========== HTML Builder ==========

private fun buildHtml(data: HeatmapData, scheme: ColorScheme): String {
    val colorsJson = scheme.colors.joinToString(", ") { "\"$it\"" }.let { "[$it]" }

    val dailyCountsJson = buildString {
        append("{")
        data.dailyCounts.entries.joinTo(this) { (date, count) -> "\"$date\":$count" }
        append("}")
    }

    val context = Context().apply {
        setVariable("staticCss", "../static/cal-heatmap.css")
        setVariable("staticD3", "../static/d3.v7.min.js")
        setVariable("staticCalHeatmap", "../static/cal-heatmap.min.js")
        setVariable("nickname", data.nickname)
        setVariable("startDate", data.startDate)
        setVariable("endDate", data.endDate)
        setVariable("startDateJs", data.startDate.replace("/", "-"))
        setVariable("endDateJs", data.endDate.replace("/", "-"))
        setVariable("totalWords", formatNum(data.totalWords))
        setVariable("todayWords", formatNum(data.todayWords))
        setVariable("isGroup", data.isGroup)
        setVariable("rank", data.rank)
        setVariable("totalUsers", data.totalUsers)
        setVariable("colors", colorsJson)
        setVariable("dailyCounts", dailyCountsJson)
    }

    return templateEngine.process("heatmap-template.html", context)
}

private fun formatNum(n: Int): String = if (n >= 10000) "${"%.1f".format(n / 10000.0)}万" else n.toString()

private fun pad(n: Int): String = n.toString().padStart(2, '0')
