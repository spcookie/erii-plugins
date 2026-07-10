package uesugi.plugin.chat

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.text.text
import uesugi.common.LLMModelChoice
import uesugi.onebot.core.message.buildMessage
import uesugi.onebot.sdk.client.api.sendGroupMsg
import uesugi.spi.annotation.Cmd
import uesugi.spi.annotation.useLLM
import uesugi.spi.annotation.useMeta
import uesugi.spi.getRefBot

@Cmd(name = "chat")
suspend fun chat(args: List<String>) {
    if (args.isEmpty()) return
    val content = args[0]
    val result = useLLM().execute(
        prompt = prompt("chat") {
            text {
                text(content)
            }
        },
        model = LLMModelChoice.Pro
    )

    val segments = buildMessage {
        for (part in result.parts) {
            when (part) {
                is MessagePart.Reasoning -> {
                    markdown(part.content.joinToString())
                }

                is MessagePart.Text -> {
                    markdown(part.text)
                }

                else -> {}
            }
        }
    }

    val meta = useMeta()
    meta.getRefBot()
        .sendGroupMsg(
            meta.groupId.toLong(),
            segments
        )
}