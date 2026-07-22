@file:Definition(pluginId = "seeddream", version = "0.0.1", description = "文生图/图生图插件")

package uesugi.plugin

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.params.LLMParams
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.fasterxml.jackson.databind.JsonNode
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import uesugi.common.ChatMessage
import uesugi.common.LLMModelChoice
import uesugi.common.data.HistoryTable
import uesugi.common.data.MessageType
import uesugi.common.data.ResourceTable
import uesugi.onebot.core.message.buildMessage
import uesugi.onebot.sdk.client.api.sendGroupMsg
import uesugi.spi.Meta
import uesugi.spi.annotation.*
import uesugi.spi.sendAgent
import java.net.URL
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

private val log = KotlinLogging.logger {}

private const val IMAGE_CREATE_MATCHER = """
        当用户的核心意图是【让 AI 生产/制作/定制一张图片】时，优先归类为此项。
        
        【优先级规则】：
        如果用户的意图同时涉及"涩图/R18"和"生成/画"，
        必须优先归类为 IMAGE_CREATE（视为"创作R18图片"），而不是 REQUEST_R18_IMAGE。
        
        判定逻辑 - 满足以下任一情况即可：
        
        1. 显式创作指令（动词驱动）：
           - 包含明确的生产类动词："画"、"生成"、"捏"、"绘制"、"制作"、"咏唱"。
           - 例："画一只猫"、"生成赛博朋克背景"、"帮我捏个头像"。
        
        2. 隐式定制意图（描述驱动）：
           - 用户使用了"想要一张图"、"整一张图"等模糊动词，但**紧跟了具体的画面描述**。
           - 这里的"描述"通常包含：外貌特征、动作、场景、风格、构图。
           - 例："给我一张[白发、红瞳、拿着武士刀]的图片"（因为描述具体，视为要求生成）。
           - 例："想要一张[初音未来在吃汉堡]的图"（场景具体，视为要求生成）。
        
        边界区分（Make vs Get）：
        - 场景 A（生成）：用户提供了画面配方/Prompt。
          "整一张黑丝御姐的图" -> IMAGE_CREATE（偏向定制需求）。
          "画个涩图" -> IMAGE_CREATE（动词是画）。
        
        - 场景 B（索取）：用户仅仅是在翻找库存。
          "发点黑丝御姐" -> REQUEST_R18_IMAGE / CHAT（意图是看现成的）。
          "来点涩图" -> REQUEST_R18_IMAGE（意图是看现成的）。
          "有没有美图" -> CHAT（询问库存）。
        
        总结：只要感觉用户是把 AI 当作【画师/生成器】使用，就选此项。
    """

private val IMAGE_COUNT_PROMPT = """
        你负责判断用户希望一次生成多少张图片。
        只根据用户最新一条提示词判断，返回 1 到 4 之间的整数：
        - 用户明确要求数量时，按要求返回，但最多为 4。
        - 用户使用“多张”“几张”“一组”等未明确数量的表达时，返回 4。
        - 用户未表达多图意图时，返回 1。
        只输出结构化结果中的 imageCount 字段，不要改写或扩展提示词。
    """.trimIndent()

private var token: String? = null

private suspend fun ensureToken() {
    if (token == null) {
        val config = useConfig()
        token = config.findEnv(config().getString("token"))!!
    }
}

// ========== Route handler ==========

@Route(
    key = "IMAGE_CREATE",
    desc = IMAGE_CREATE_MATCHER,
    toolSets = ["seeddream"]
)
suspend fun seeddreamRoute(meta: Meta) {
    generateImage(meta)
        .onLeft { error ->
            log.warn { "生成图片失败: $error" }
            meta.sendAgent("生成图片失败，请直接告诉用户失败原因。错误: $error")
        }
        .onRight { images ->
            sendImages(meta, images)
        }
}

// ========== Tool ==========

@ChatMessage
@LLMTool(set = "seeddream")
@LLMDesc("生成图片并发送")
suspend fun imageCreateSend(): String {
    val meta = useToolMeta().value
    val resource = generateImage(meta)
    resource.onRight { images ->
        sendImages(meta, images)
    }
    return "ok"
}

// ========== Private helpers ==========

suspend fun generateImage(meta: Meta): Either<String, List<ByteArray>> {
    ensureToken()

    val database = useDatabase()
    val llm = useLLM()
    val http = useHttp()

    val records = database.getHistory {
        (HistoryTable leftJoin ResourceTable).selectAll()
            .where {
                HistoryTable.groupId eq meta.groupId and
                        (HistoryTable.userId inList listOf(meta.senderId!!, meta.botId))
            }
            .orderBy(
                HistoryTable.createdAt to SortOrder.DESC,
                HistoryTable.id to SortOrder.DESC
            )
            .limit(4)
    }

    val latestRecord = records.firstOrNull()
        ?: return "No history found for SeedDream".left()
    val latestPrompt = latestRecord.content?.takeIf { it.isNotBlank() }
        ?: return "Latest history has no prompt".left()

    val countPrompt = prompt("seed_dream_image_count", LLMParams(maxTokens = 64)) {
        system(IMAGE_COUNT_PROMPT)
        user(latestPrompt)
    }

    val imageCount = llm.executeStructured<SeedDreamImageCount>(countPrompt, LLMModelChoice.Pro)
        .getOrThrow()
        .data
        .imageCount
        .coerceIn(1, 4)

    val referenceRecords = selectContiguousReferences(records) {
        it.messageType == MessageType.IMAGE
    }

    val images = referenceRecords.mapNotNull { record ->
        val resource = record.resource ?: return@mapNotNull null
        val bytes = resource.bytes ?: return@mapNotNull null
        val base64 = Base64.getEncoder().encodeToString(bytes)
        val mimeType = getMimeType(resource.fileName)
        "data:$mimeType;base64,$base64"
    }

    val requestBody = buildSeedDreamRequestBody(
        model = useConfig()().getString("model"),
        prompt = latestPrompt,
        images = images,
        imageCount = imageCount
    )

    log.debug {
        "SeedDream Request: prompt=$latestPrompt, referenceImages=${images.size}, imageCount=$imageCount"
    }

    val node: JsonNode = retryWithBackoff(3) {
        http.post("https://ark.cn-beijing.volces.com/api/v3/images/generations") {
            contentType(ContentType.Application.Json)
            bearerAuth(token!!)
            header("Accept", "application/json")
            header("User-Agent", "Erii/1.0")
            header("Connection", "close")
            setBody(requestBody)
        }.body()
    }

    if (node.has("error")) {
        log.warn { "SeedDream Error: $node" }
        return node.toString().left()
    }

    val urls = node.path("data")
        .mapNotNull { it.path("url").textValue() }

    if (urls.isEmpty()) {
        return "No URL returned by SeedDream".left()
    }

    val generatedImages = urls.map { downloadImage(it) }
    return generatedImages.right()
}

private suspend fun downloadImage(url: String): ByteArray {
    val connection = withContext(Dispatchers.IO) {
        URL(url).openConnection()
    }
        .apply {
            connectTimeout = 20_000
            readTimeout = 60_000
        }
    return withContext(Dispatchers.IO) {
        connection.getInputStream()
    }
        .use { input ->
            input.readBytes()
        }
}

private suspend fun sendImages(meta: Meta, images: List<ByteArray>) {
    images.forEach { imageBytes ->
        val base64 = Base64.getEncoder().encodeToString(imageBytes)
        meta.roledBot.refBot.sendGroupMsg(
            meta.groupId.toLong(),
            buildMessage { image("base64://$base64") }
        )
    }
}


private suspend fun <T> retryWithBackoff(maxAttempts: Int, block: suspend () -> T): T {
    repeat(maxAttempts - 1) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            log.warn { "SeedDream HTTP call failed (attempt ${attempt + 1}): ${e.message}" }
            delay((1000L * (attempt + 1)).milliseconds)
        }
    }
    return block()
}

private fun getMimeType(fileName: String): String {
    return when (val type = fileName.substringAfterLast(".")) {
        "png" -> "image/png"
        "jpg", "jpeg", "gif" -> "image/jpeg"
        else -> throw IllegalArgumentException("Unsupported image type: $type")
    }
}

// ========== Data classes ==========

@Serializable
data class SeedDreamImageCount(val imageCount: Int)
