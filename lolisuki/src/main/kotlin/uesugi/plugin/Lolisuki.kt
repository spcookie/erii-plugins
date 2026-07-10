@file:Definition(pluginId = "lolisuki", version = "0.0.1", description = "二次元涩图插件")

package uesugi.plugin

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.params.LLMParams
import com.fasterxml.jackson.databind.JsonNode
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.atomicfu.AtomicBoolean
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import uesugi.common.ChatMessage
import uesugi.common.ChatToolSet
import uesugi.common.LLMModelChoice
import uesugi.common.event.PSFeature
import uesugi.common.toolkit.calcHumanTypingDelay
import uesugi.onebot.core.message.buildMessage
import uesugi.onebot.sdk.client.api.sendGroupMsg
import uesugi.spi.*
import uesugi.spi.EmptyConfig.plus
import uesugi.spi.annotation.*
import java.net.URL
import java.util.*
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

private val log = KotlinLogging.logger {}

private val scope =
    CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName("Lolisuki") + CoroutineExceptionHandler { _, exception ->
        log.error(exception) { "Lolisuki error: ${exception.message}" }
    })

private const val LOLISUKI_MATCHER = """
        仅当用户【明确提出图片请求】时，才选择此分类。
        必须【同时满足】以下两个条件，缺一不可：
        
        条件一：图片内容明确指向【成人 / R18】
        例如：
        - "涩图"
        - "R18"
        - "黄图"
        - "色图"
        
        强制排除规则：
        - 如果内容仅包含成人、性、下流、攻击性、辱骂、玩梗等文字
        - 但【没有任何索要图片的意图或行为】
        则【绝对不要】归类为 REQUEST_R18_IMAGE。
        这种情况应优先归类为 CHAT。
    """

// ========== Route handler ==========

@Route(
    path = "REQUEST_R18_IMAGE",
    method = LOLISUKI_MATCHER,
    toolSets = ["lolisuki"]
)
suspend fun lolisukiRoute(meta: Meta) {
    val image = getImage(meta)

    val state = atomic(false)
    val groupId = meta.groupId.toLong()

    fun send() {
        if (!state.value) {
            state.value = true
            if (image != null) {
                log.info { "由于图片未使用 Agent Tool 发送，尝试直接发送" }
                scope.launch {
                    val base64 = Base64.getEncoder().encodeToString(image)
                    meta.roledBot.refBot.sendGroupMsg(groupId, buildMessage { image("base64://$base64") })
                    log.info { "图片直接发送成功" }
                }
            } else {
                log.warn { "未获取到图片，直接发送失败" }
            }
        }
    }

    meta.sendAgent(
        input = "加入群聊天，你已经获取到了一张涩图，你需要调用工具发送一张涩图给群友。",
        ToolSetBuilder {
            tool {
                ImageTool(
                    image,
                    groupId,
                    it,
                    state
                )
            }
        } + Feature(PSFeature.GRAB or PSFeature.FALLBACK)
    ) {
        runCompletion { send() }
        callCompletion { send() }
        callFallback { send() }
        scope
    }
}

// ========== Tool ==========

@ChatMessage
@LLMTool(set = "lolisuki")
@LLMDesc("发送一张涩图")
suspend fun sendSexImage(): String {
    val meta = useToolMeta().value
    val resource = getImage(meta)
    if (resource != null) {
        val base64 = Base64.getEncoder().encodeToString(resource)
        meta.roledBot.refBot.sendGroupMsg(
            meta.groupId.toLong(),
            buildMessage { image("base64://$base64") }
        )
    }
    return "发送成功"
}

// ========== Dynamic ToolSet ==========

@Suppress("unused")
class ImageTool(
    val image: ByteArray?,
    val groupId: Long,
    val chatToolSet: ChatToolSet,
    val state: AtomicBoolean
) : MetaToolSet {

    @LLMDescription("回复消息，并发送涩图")
    @Tool
    fun sendMessageAndImage(@LLMDescription("回复 2～3 句为主，最多 5 句") sentences: List<String>): String? {
        state.value = true
        scope.launch {
            for ((i, v) in sentences.withIndex()) {
                if (i == 0) {
                    chatToolSet.sendText(listOf(v))
                } else {
                    delay(calcHumanTypingDelay(v))
                    chatToolSet.sendText(listOf(v))
                }
            }
            if (image != null) {
                val base64 = Base64.getEncoder().encodeToString(image)
                MetaToolSet.meta.roledBot.refBot.sendGroupMsg(groupId, buildMessage { image("base64://$base64") })
            }
        }
        return null
    }
}

// ========== Private helpers ==========

@OptIn(ExperimentalTime::class)
suspend fun getImage(meta: Meta): ByteArray? {
    val database = useDatabase()
    val llm = useLLM()
    val http = useHttp()

    val history = database.getLatestHistory(
        meta.botId,
        meta.groupId,
        10,
        1.days
    )
    val ctx = buildString {
        for (entity in history) {
            appendLine("${entity.nick}: ${entity.content}")
        }
    }

    val prompt = prompt("提取标题、关键词", LLMParams(maxTokens = 65536)) {
        user {
            text(
                """
                请根据以下规则提取"当前内容"中索要的二次元/动漫图片的关键词或标签(Tag)，仅限二次元、动漫、游戏角色或风格类标签。

                规则：
                1. 仅提取 **二次元图片相关标签**，如角色名、画风、类型（萌系、赛博朋克、机甲等）。
                2. 忽略泛泛的词语，如"涩图""图片""发一下"等，不要捏造。
                3. 可以提取多个关键词。
                4. 多个关键词如果是 **AND** 语义，则是一个标签组；如果是 **OR** 语义，则是多个标签组。
                5. 如果没有可提取的标签，返回空列表。
                6. 历史上下文仅作为参考，帮助理解用户意图。

                历史上下文：
                """.trimIndent()
            )
            text(
                """
                $ctx

                当前内容：
                ${meta.input}
                """.trimIndent()
            )
        }
    }

    log.info { "用户输入：${meta.input}，开始提取关键词" }

    val result = llm.executeStructured<TagGroup>(
        prompt,
        LLMModelChoice.Lite
    )

    var tags: List<String>? = null
    if (result.isSuccess) {
        tags = result.getOrNull()?.data?.run {
            buildList {
                for (group in groups) {
                    add(group.values.joinToString("|"))
                }
            }
        }
    }

    if (tags.isNullOrEmpty()) {
        log.info { "未提取到关键词、标签" }
    } else {
        log.info { "提取到关键词、标签: ${tags.joinToString("&")}" }
    }

    val node: JsonNode = http.get("https://lolisuki.cn/api/setu/v1") {
        parameter("r18", 1)
        parameter("level", 4)
        parameter("num", 4)
        if (tags != null) {
            for (tag in tags) {
                parameter("tag", tag)
            }
        }
        log.info { "开始获取图片连接: $url" }
    }.body()

    var image: ByteArray? = null
    if (node.get("code").asInt() != 0) {
        log.error { "获取图片连接失败: $node" }
    } else {
        for (i in 0 until 4) {
            var url: String? = null
            try {
                url = node.get("data")[i].get("urls").get("regular").asText()
                log.info { "开始获取图片: $url" }
                withContext(Dispatchers.IO) {
                    val connection = URL(url).openConnection()
                        .apply {
                            connectTimeout = 20_000
                            readTimeout = 60_000
                        }
                    image = connection.getInputStream().use { input ->
                        input.readBytes()
                    }
                }
                break
            } catch (_: Exception) {
                log.info { "图片: $url 获取失败，开始获取下一张图片" }
            }
        }
        if (image == null) {
            log.warn { "未获取到图片" }
        }
    }
    return image
}

// ========== Data classes ==========

@Serializable
data class Tag(val values: List<String>)

@Serializable
data class TagGroup(val groups: List<Tag>)
