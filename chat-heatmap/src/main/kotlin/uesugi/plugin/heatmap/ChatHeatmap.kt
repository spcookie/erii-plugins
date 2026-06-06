@file:Definition(pluginId = "chat-heatmap", version = "0.0.1", description = "聊天热力图插件")

package uesugi.plugin.heatmap

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import uesugi.common.data.HistoryRecord
import uesugi.common.data.HistoryTable
import uesugi.common.toolkit.BrowserScraper
import uesugi.common.toolkit.BrowserScraperHolder
import uesugi.onebot.sdk.client.api.sendGroupMsg
import uesugi.onebot.sdk.message.buildMessage
import uesugi.spi.Kv
import uesugi.spi.Meta
import uesugi.spi.annotation.*
import uesugi.spi.isAdmin
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.days

private val REFERENCE_BLOCK = Regex(
    "---REFERENCE MESSAGE START---.*?---REFERENCE MESSAGE END---",
    RegexOption.DOT_MATCHES_ALL
)

private fun HistoryRecord.effectiveLength(): Int =
    content?.replace(REFERENCE_BLOCK, "")?.length ?: 0

private val log = KotlinLogging.logger {}

private data class ColorScheme(val name: String, val colors: List<String>)

private val COLOR_SCHEMES = listOf(
    ColorScheme("github", listOf("#ebedf0", "#9be9a8", "#40c463", "#30a14e", "#216e39")),
    ColorScheme("ocean", listOf("#ebedf0", "#a5d8ff", "#58a6ff", "#1f77b4", "#0c2d48")),
    ColorScheme("sunset", listOf("#ebedf0", "#ffd8a8", "#f0883e", "#cc6b2c", "#7a3b1e")),
    ColorScheme("purple", listOf("#ebedf0", "#d4b8ff", "#9e6ad6", "#7146a3", "#462868")),
    ColorScheme("rose", listOf("#ebedf0", "#ffb8c8", "#e06c8f", "#b34068", "#702845"))
)

private val pluginClassLoader = object {}.javaClass.classLoader

private val htmlCache = ConcurrentHashMap<String, String>()

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

@Volatile
private var serverRouteReady = false
private val serverRouteLock = Any()

@Volatile
private var serverBaseUrl: String? = null

private suspend fun ensureServerRoute() {
    if (serverRouteReady) return
    val server = useServer()
    synchronized(serverRouteLock) {
        if (serverRouteReady) return
        serverBaseUrl = server.url.buildString()
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
                call.respondBytes(
                    loader.getResourceAsStream("static/cal-heatmap.css")!!.readBytes(),
                    ContentType.Text.CSS
                )
            }
            get("/heatmap/{id}") {
                val id = call.parameters["id"]!!
                val html = htmlCache.remove(id)
                if (html != null) call.respondText(html, ContentType.Text.Html)
                else call.respondText("Not found", ContentType.Text.Plain)
            }
        }
        serverRouteReady = true
    }
}

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

@Cmd(name = "heatmap", alias = ["热力图"], toolSets = ["heatmap"])
suspend fun heatmapCmd(meta: Meta) {
    log.info { "/heatmap triggered by ${meta.senderId} in group ${meta.groupId}" }

    val arg = extractHeatmapArg(meta)
    val targetUserId = if (arg != null) {
        val foundUserId = findUserIdByNickname(meta.groupId, arg) ?: run {
            log.info { "/heatmap with arg '$arg': user not found" }
            return
        }
        if (foundUserId != meta.senderId && !meta.isAdmin()) {
            log.info { "/heatmap with arg '$arg' (userId=$foundUserId) denied for non-admin ${meta.senderId}" }
            return
        }
        foundUserId
    } else {
        meta.senderId ?: return
    }

    generateAndSendHeatmap(meta, isGroup = false, targetUserId = targetUserId)
}

@Cmd(name = "heatmap-all", alias = ["热力图全群"], toolSets = ["heatmap-all"])
suspend fun heatmapAllCmd(meta: Meta) {
    log.info { "/heatmap-all triggered by ${meta.senderId} in group ${meta.groupId}" }
    generateAndSendHeatmap(meta, isGroup = true)
}

@Cmd(name = "heatmap-reset")
suspend fun heatmapResetCmd(meta: Meta) {
    if (!meta.isAdmin()) {
        log.info { "/heatmap-reset denied for non-admin ${meta.senderId} in group ${meta.groupId}" }
        return
    }
    val kv = useKv()
    val keysMeta = kv.get(CACHE_KEYS_META)
    if (keysMeta != null) {
        for (key in keysMeta.split(",")) {
            if (key.isNotEmpty()) {
                kv.delete(key)
                log.info { "Deleted heatmap cache key: $key" }
            }
        }
        kv.delete(CACHE_KEYS_META)
    }
    log.info { "/heatmap-reset executed by admin ${meta.senderId}, all cache cleared" }
}

@LLMTool(set = "heatmap")
@LLMDesc("当用户想查看热力图或自己的聊天活跃度、发言统计、热力图时调用，发送一张个人发言热力图。可以指定昵称参数查看特定成员的热力图")
suspend fun getMyHeatmap(nickname: String? = null): String {
    val meta = useToolMeta().value
    val targetUserId = if (nickname != null) {
        val foundUserId = findUserIdByNickname(meta.groupId, nickname)
            ?: return "未找到昵称 '$nickname' 对应的成员"
        if (foundUserId != meta.senderId && !meta.isAdmin()) {
            return "无权查看其他成员的热力图，需要管理员权限"
        }
        foundUserId
    } else {
        meta.senderId ?: return "无法获取当前用户ID"
    }
    generateAndSendHeatmap(meta, isGroup = false, targetUserId = targetUserId)
    return if (nickname != null) "$nickname 的热力图已生成" else "热力图已生成"
}

@LLMTool(set = "heatmap-all")
@LLMDesc("当用户想明确提到查看热力图全群的聊天活跃度、发言统计时调用，发送一张全群发言热力图")
suspend fun getGroupHeatmap(): String {
    val meta = useToolMeta().value
    generateAndSendHeatmap(meta, isGroup = true)
    return "全群热力图已生成"
}

private fun extractHeatmapArg(meta: Meta): String? {
    val input = meta.input ?: return null
    val withoutSlash = input.removePrefix("/")
    val matched = (listOf("heatmap") + listOf("热力图")).firstOrNull {
        withoutSlash.startsWith(it)
    } ?: "heatmap"
    return withoutSlash.removePrefix(matched).trim().takeIf { it.isNotBlank() }
}

private suspend fun findUserIdByNickname(groupId: String, nickname: String): String? {
    val database = useDatabase()
    val records = database.getHistory {
        HistoryTable.selectAll().where {
            (HistoryTable.groupId eq groupId) and (HistoryTable.nick eq nickname)
        }.orderBy(HistoryTable.createdAt to SortOrder.DESC).limit(1)
    }
    return records.firstOrNull()?.userId
}

private suspend fun generateAndSendHeatmap(meta: Meta, isGroup: Boolean, targetUserId: String? = null) {
    val groupId = meta.groupId
    val userId = targetUserId ?: meta.senderId ?: return
    val groupIdLong = groupId.toLong()

    val data = if (isGroup) loadGroupHeatmapData(groupId) else loadHeatmapData(groupId, userId)

    log.info { "Heatmap data loaded: totalWords=${data.totalWords}, days=${data.dailyCounts.size}, isGroup=$isGroup" }

    val scheme = COLOR_SCHEMES.random()
    val html = try {
        buildHtml(data, scheme)
    } catch (e: Exception) {
        log.error(e) { "buildHtml failed: ${e.message}, data=${data}" }
        return
    }

    ensureServerRoute()

    val id = UUID.randomUUID().toString()
    htmlCache[id] = html

    val screenshot = withContext(Dispatchers.IO) {
        takeScreenshot(id)
    }

    htmlCache.remove(id)

    if (screenshot != null) {
        val base64 = Base64.getEncoder().encodeToString(screenshot)
        meta.roledBot.refBot.sendGroupMsg(groupIdLong, buildMessage { image("base64://$base64") })
        log.info { "Heatmap sent to group $groupId" }
    } else {
        log.warn { "Screenshot failed" }
    }
}

private fun takeScreenshot(id: String): ByteArray? {
    val url = URLBuilder().takeFrom(serverBaseUrl!!).apply {
        appendPathSegments("heatmap", id)
    }.buildString()
    log.info { "Screenshot URL: $url" }
    return try {
        BrowserScraperHolder.getInstance().takeFullScreenshot(
            url = url,
            width = 640,
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

private const val CACHE_KEYS_META = "heatmap:keys"

private fun cacheKeyPersonal(groupId: String, userId: String) = "heatmap:p:$groupId:$userId"
private fun cacheKeyGroup(groupId: String) = "heatmap:g:$groupId"

private data class TimeWindow(
    val startLDT: LocalDateTime,
    val endLDT: LocalDateTime,
    val todayKey: String,
    val yesterdayKey: String
)

private fun timeWindow(): TimeWindow {
    val timeZone = TimeZone.currentSystemDefault()
    val now = kotlin.time.Clock.System.now()
    val halfYearAgo = now - 183.days
    val endLDT = now.toLocalDateTime(timeZone)
    val startLDT = halfYearAgo.toLocalDateTime(timeZone)
    val todayLDT = now.toLocalDateTime(timeZone)
    val yesterdayLDT = now.minus(1.days).toLocalDateTime(timeZone)
    return TimeWindow(
        startLDT = startLDT,
        endLDT = endLDT,
        todayKey = "${todayLDT.year}-${pad(todayLDT.month.number)}-${pad(todayLDT.day)}",
        yesterdayKey = "${yesterdayLDT.year}-${pad(yesterdayLDT.month.number)}-${pad(yesterdayLDT.day)}"
    )
}

private suspend fun queryHalfYearRecords(
    database: uesugi.spi.Database,
    groupId: String,
    window: TimeWindow
): List<HistoryRecord> =
    database.getHistory {
        HistoryTable.selectAll().where {
            (HistoryTable.groupId eq groupId) and
                    (HistoryTable.createdAt greaterEq window.startLDT) and
                    (HistoryTable.createdAt lessEq window.endLDT)
        }
    }

private suspend fun queryTodayRecords(
    database: uesugi.spi.Database,
    groupId: String,
    endLDT: LocalDateTime
): List<HistoryRecord> {
    val todayStart = LocalDateTime(endLDT.year, endLDT.month, endLDT.day, 0, 0)
    return database.getHistory {
        HistoryTable.selectAll().where {
            (HistoryTable.groupId eq groupId) and
                    (HistoryTable.createdAt greaterEq todayStart)
        }
    }
}

private fun List<HistoryRecord>.foldDailyCounts(): Map<String, Int> =
    fold(mutableMapOf()) { acc, r ->
        val dk = "${r.createdAt.year}-${pad(r.createdAt.month.number)}-${pad(r.createdAt.day)}"
        acc[dk] = (acc[dk] ?: 0) + r.effectiveLength()
        acc
    }

private fun List<HistoryRecord>.foldUserWordCounts(): Map<String, Int> =
    fold(mutableMapOf()) { acc, r ->
        acc[r.userId] = (acc[r.userId] ?: 0) + r.effectiveLength()
        acc
    }

private fun serializeCounts(counts: Map<String, Int>): String =
    counts.entries.joinToString(",") { "${it.key}=${it.value}" }

private fun deserializeCounts(str: String): Map<String, Int> {
    if (str.isEmpty()) return emptyMap()
    return str.split(",").associate { part ->
        val (date, count) = part.split("=", limit = 2)
        date to count.toInt()
    }
}

private suspend fun registerCacheKey(kv: Kv, key: String) {
    val existing = kv.get(CACHE_KEYS_META) ?: ""
    val keys = existing.split(",").toMutableSet()
    if (keys.add(key)) {
        kv.set(CACHE_KEYS_META, keys.joinToString(","))
    }
}

private suspend fun loadHeatmapData(groupId: String, userId: String): HeatmapData {
    val database = useDatabase()
    val kv = useKv()
    val cacheKey = cacheKeyPersonal(groupId, userId)
    val window = timeWindow()

    val cached = kv.get(cacheKey)
    if (cached != null) {
        try {
            val parts = cached.split("\n", limit = 4)
            if (parts.size >= 4 && parts[0] == window.yesterdayKey) {
                val cachedNickname = parts[1]
                val cachedDaily = deserializeCounts(parts[2])
                val cachedUsers = deserializeCounts(parts[3])

                val todayRecords = queryTodayRecords(database, groupId, window.endLDT)

                val mergedDaily = cachedDaily.toMutableMap()
                for (r in todayRecords.filter { it.userId == userId }) {
                    val dk = "${r.createdAt.year}-${pad(r.createdAt.month.number)}-${pad(r.createdAt.day)}"
                    mergedDaily[dk] = (mergedDaily[dk] ?: 0) + (r.effectiveLength())
                }

                val mergedUsers = cachedUsers.toMutableMap()
                for (r in todayRecords) {
                    mergedUsers[r.userId] = (mergedUsers[r.userId] ?: 0) + (r.effectiveLength())
                }

                val totalWords = mergedDaily.values.sum()
                val todayWords = mergedDaily[window.todayKey] ?: 0
                val targetWords = mergedUsers[userId] ?: 0
                val rank = mergedUsers.values.count { it > targetWords } + 1
                val nickname = todayRecords.lastOrNull { it.userId == userId }?.nick ?: cachedNickname

                val cacheDaily = mergedDaily.filterKeys { it != window.todayKey }
                val cacheValue =
                    "${window.todayKey}\n${nickname}\n${serializeCounts(cacheDaily)}\n${serializeCounts(mergedUsers)}"
                kv.set(cacheKey, cacheValue)
                log.info { "Heatmap cache hit for $userId, loaded ${todayRecords.size} today records" }

                return HeatmapData(
                    nickname, mergedDaily, totalWords, todayWords, rank, mergedUsers.size,
                    "${window.startLDT.year}/${pad(window.startLDT.month.number)}",
                    "${window.endLDT.year}/${pad(window.endLDT.month.number)}", false
                )
            }
        } catch (e: Exception) {
            log.warn(e) { "Cache parse failed for $cacheKey, falling back to full load" }
        }
    }

    val allRecords = queryHalfYearRecords(database, groupId, window)
    val userRecords = allRecords.filter { it.userId == userId }

    val dailyCounts = userRecords.foldDailyCounts()
    val totalWords = dailyCounts.values.sum()
    val todayWords = dailyCounts[window.todayKey] ?: 0

    val allUserWordCounts = allRecords.foldUserWordCounts()
    val targetWords = allUserWordCounts[userId] ?: 0
    val rank = allUserWordCounts.values.count { it > targetWords } + 1
    val totalUsers = allUserWordCounts.size
    val nickname = allRecords.lastOrNull { it.userId == userId }?.nick ?: "你"

    try {
        val cacheDaily = dailyCounts.filterKeys { it != window.todayKey }
        val cacheValue =
            "${window.todayKey}\n${nickname}\n${serializeCounts(cacheDaily)}\n${serializeCounts(allUserWordCounts)}"
        kv.set(cacheKey, cacheValue)
        registerCacheKey(kv, cacheKey)
        log.info { "Heatmap cache stored for $userId, ${cacheDaily.size} days" }
    } catch (e: Exception) {
        log.warn(e) { "Failed to store cache for $cacheKey" }
    }

    return HeatmapData(
        nickname, dailyCounts, totalWords, todayWords, rank, totalUsers,
        "${window.startLDT.year}/${pad(window.startLDT.month.number)}",
        "${window.endLDT.year}/${pad(window.endLDT.month.number)}", false
    )
}

private suspend fun loadGroupHeatmapData(groupId: String): HeatmapData {
    val database = useDatabase()
    val kv = useKv()
    val cacheKey = cacheKeyGroup(groupId)
    val window = timeWindow()

    val cached = kv.get(cacheKey)
    if (cached != null) {
        try {
            val parts = cached.split("\n", limit = 3)
            if (parts.size >= 3 && parts[0] == window.yesterdayKey) {
                val cachedTotalUsers = parts[1].toInt()
                val cachedDaily = deserializeCounts(parts[2])

                val todayRecords = queryTodayRecords(database, groupId, window.endLDT)

                val mergedDaily = cachedDaily.toMutableMap()
                for (r in todayRecords) {
                    val dk = "${r.createdAt.year}-${pad(r.createdAt.month.number)}-${pad(r.createdAt.day)}"
                    mergedDaily[dk] = (mergedDaily[dk] ?: 0) + (r.effectiveLength())
                }

                val totalWords = mergedDaily.values.sum()
                val todayWords = mergedDaily[window.todayKey] ?: 0
                val totalUsers = maxOf(cachedTotalUsers, todayRecords.map { it.userId }.distinct().size)

                val cacheDaily = mergedDaily.filterKeys { it != window.todayKey }
                kv.set(cacheKey, "${window.todayKey}\n${totalUsers}\n${serializeCounts(cacheDaily)}")
                log.info { "Group heatmap cache hit for $groupId, loaded ${todayRecords.size} today records" }

                return HeatmapData(
                    "全群", mergedDaily, totalWords, todayWords, 0, totalUsers,
                    "${window.startLDT.year}/${pad(window.startLDT.month.number)}",
                    "${window.endLDT.year}/${pad(window.endLDT.month.number)}", true
                )
            }
        } catch (e: Exception) {
            log.warn(e) { "Group cache parse failed for $cacheKey, falling back to full load" }
        }
    }

    val allRecords = queryHalfYearRecords(database, groupId, window)

    val dailyCounts = allRecords.foldDailyCounts()
    val totalWords = dailyCounts.values.sum()
    val todayWords = dailyCounts[window.todayKey] ?: 0
    val totalUsers = allRecords.map { it.userId }.distinct().size

    try {
        val cacheDaily = dailyCounts.filterKeys { it != window.todayKey }
        kv.set(cacheKey, "${window.todayKey}\n${totalUsers}\n${serializeCounts(cacheDaily)}")
        registerCacheKey(kv, cacheKey)
        log.info { "Group heatmap cache stored for $groupId, ${cacheDaily.size} days" }
    } catch (e: Exception) {
        log.warn(e) { "Failed to store group cache for $cacheKey" }
    }

    return HeatmapData(
        "全群", dailyCounts, totalWords, todayWords, 0, totalUsers,
        "${window.startLDT.year}/${pad(window.startLDT.month.number)}",
        "${window.endLDT.year}/${pad(window.endLDT.month.number)}", true
    )
}

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
        setVariable("startDateJs", "${data.startDate.replace("/", "-")}-01")
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
