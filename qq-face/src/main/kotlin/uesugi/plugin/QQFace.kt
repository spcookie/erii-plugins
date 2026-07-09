@file:Definition(pluginId = "qq-face", version = "0.0.1", description = "QQ表情匹配插件")

package uesugi.plugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uesugi.common.ChatMessage
import uesugi.onebot.sdk.client.api.sendGroupMsg
import uesugi.onebot.core.message.buildMessage
import uesugi.spi.annotation.*
import kotlin.time.Duration.Companion.milliseconds

private val log = KotlinLogging.logger {}
private val objectMapper = ObjectMapper()
private const val INIT_FACE = "init_face"
private const val MAX_RETRIES = 3
private const val RETRY_DELAY_MS = 1000L
private val mutex = Mutex()

// ========== Tool ==========

@ChatMessage
@LLMTool(set = "qq-face")
@LLMDesc("在群聊中发送一个QQ表情，用来表达情绪或对当前对话作出反应。当发送表情比发送文字更自然时可以调用此工具。")
suspend fun sendFace(
    @LLMDesc("要发送的QQ表情名称，应选择最符合当前语气或情绪的表情。")
    query: String,
    @LLMDesc("要发送的QQ表情数量，默认为1，最大为5")
    count: Int = 1
): String? {
    ensureFace()
    val effectCount = if (count <= 0) 1 else if (count > 5) 5 else count
    val firstOk = sendOneFace(query)
    if (!firstOk) return "没有该表情，表情发送失败"
    if (effectCount > 1) {
        repeat(effectCount - 1) { sendOneFace(query) }
    }
    return "表情发送成功"
}

// ========== Private helpers ==========

private suspend fun ensureFace() {
    try {
        val kv = useKv()
        if (kv.get(INIT_FACE) == null) {
            mutex.withLock {
                if (kv.get(INIT_FACE) == null) {
                    log.info { "Initializing face embeddings from face_config.json..." }

                    val config = useConfig()
                    val inputStream = try {
                        config.readResource("face_config.json")
                    } catch (e: Exception) {
                        throw IllegalStateException("face_config.json not found in resources", e)
                    }

                    val jsonNode: JsonNode = objectMapper.readTree(inputStream)
                    val sysface = jsonNode["sysface"]

                    val vector = useVector()
                    for (face in sysface) {
                        val qSid = face["QSid"].asText()
                        val qDes = face["QDes"].asText().removePrefix("/")
                        val iqLid = face["IQLid"].asText()

                        val embedding = embeddingWithRetry(listOf(qDes), emptyList())

                        vector.upsert("super_$qSid", qDes, qSid, embedding)
                        vector.upsert("iq_$iqLid", qDes, iqLid, embedding)
                        log.info { "Inserted face $qDes, $qSid, $iqLid" }
                    }

                    kv.set(INIT_FACE, "1")
                    log.info { "Face embeddings initialized." }
                }
            }
        }
    } catch (e: Exception) {
        log.error(e) { "Face initialization failed." }
    }
}

private suspend fun embeddingWithRetry(
    texts: List<String>,
    images: List<ByteArray>,
    retryCount: Int = 0
): FloatArray {
    return try {
        useVector().embedding(texts, images)
    } catch (e: Exception) {
        if (retryCount < MAX_RETRIES) {
            log.warn { "Embedding failed (retry ${retryCount + 1}/$MAX_RETRIES): ${e.message}" }
            delay((RETRY_DELAY_MS * (retryCount + 1)).milliseconds)
            embeddingWithRetry(texts, images, retryCount + 1)
        } else {
            log.error(e) { "Embedding failed after $MAX_RETRIES retries" }
            throw e
        }
    }
}

private suspend fun sendOneFace(query: String): Boolean {
    val vector = useVector()
    val embedding = try {
        embeddingWithRetry(listOf(query), emptyList())
    } catch (e: Exception) {
        log.error(e) { "Failed to get embedding for query: $query" }
        return false
    }
    val search = vector.search(embedding, 5)

    if (search.isEmpty()) {
        log.info { "No face found for query: $query" }
        return false
    }

    val (id, content, _, score) = search.first()

    if (score < 0.5) {
        log.info { "Face score $score is less than 0.5, not sending." }
        return false
    }

    log.info { "Sending face $content, id $id" }

    val miraiFaceId = when {
        id.startsWith("super_") -> id.removePrefix("super_").toIntOrNull() ?: return false
        id.startsWith("iq_") -> id.removePrefix("iq_").toIntOrNull() ?: return false
        else -> id.toIntOrNull() ?: return false
    }

    return try {
        val meta = useToolMeta().value
        meta.roledBot.refBot.sendGroupMsg(meta.groupId.toLong(), buildMessage { face(miraiFaceId.toString()) })
        true
    } catch (e: Exception) {
        log.error(e) { "Failed to send face $miraiFaceId" }
        false
    }
}
