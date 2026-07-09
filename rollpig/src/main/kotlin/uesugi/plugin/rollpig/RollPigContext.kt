package uesugi.plugin.rollpig

import uesugi.onebot.core.model.MessageSegment
import uesugi.onebot.core.message.buildMessage
import uesugi.plugin.rollpig.service.RollPigService
import uesugi.plugin.rollpig.store.RollPigStore

data class RollPigContext(
    val store: RollPigStore,
    val service: RollPigService,
    val groupId: String,
    val senderId: Long,
    val senderNick: String,
    val isAdmin: Boolean,
    val sendMessage: (List<MessageSegment>) -> Unit,
    val createImage: (ByteArray) -> String?,
) {
    fun sendText(text: String) {
        sendMessage(buildMessage {
            at(senderId)
            text(" ")
            text(text)
        })
    }

    fun sendImage(bytes: ByteArray) {
        val base64 = createImage(bytes)
        if (base64 != null) {
            sendMessage(buildMessage { image("base64://$base64") })
        }
    }
}
