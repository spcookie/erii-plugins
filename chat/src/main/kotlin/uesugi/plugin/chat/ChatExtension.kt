@file:Definition

package uesugi.plugin.chat

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.MessagePart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uesugi.common.LLMModelChoice
import uesugi.onebot.core.message.buildMessage
import uesugi.onebot.sdk.client.api.sendGroupMsg
import uesugi.spi.Meta
import uesugi.spi.annotation.Cmd
import uesugi.spi.annotation.Definition
import uesugi.spi.annotation.useKv
import uesugi.spi.annotation.useLLM
import uesugi.spi.getRefBot

@Serializable
private data class ChatMemoryEntry(val role: String, val content: String)

private const val MAX_MEMORY_ENTRIES = 20

private fun memoryKey(meta: Meta) = "chat:history:${meta.botId}:${meta.groupId}"

@Cmd(name = "chat")
suspend fun chat(meta: Meta, args: List<String>) {
    if (args.isEmpty()) return
    val content = args[0]

    val kv = useKv()
    val history = kv.get(memoryKey(meta))
        ?.let { runCatching { Json.decodeFromString<List<ChatMemoryEntry>>(it) }.getOrNull() }
        ?: emptyList()

    val result = useLLM().execute(
        prompt = prompt("chat") {
            for (entry in history) {
                when (entry.role) {
                    "user" -> user { text(entry.content) }
                    "assistant" -> assistant { text(entry.content) }
                }
            }
            user { text(content) }
        },
        model = LLMModelChoice.Pro,
    )

    val replyText = result.parts
        .filterIsInstance<MessagePart.Text>()
        .joinToString("\n") { it.text }

    val updatedHistory = (history + listOf(
        ChatMemoryEntry("user", content),
        ChatMemoryEntry("assistant", replyText),
    )).takeLast(MAX_MEMORY_ENTRIES)
    kv.set(memoryKey(meta), Json.encodeToString(updatedHistory))

    val segments = buildMessage {
        for (part in result.parts.reversed()) {
            when (part) {
                is MessagePart.Reasoning -> {
                    markdown(
                        """
                        |```思考
                        |${part.content.joinToString()}
                        |```
                        |""".trimMargin()
                    )
                }

                is MessagePart.Text -> {
                    markdown(part.text)
                }

                else -> {}
            }
        }
    }

    meta.getRefBot()
        .sendGroupMsg(
            meta.groupId.toLong(),
            segments
        )
}
