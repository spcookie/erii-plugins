package uesugi.plugin.rollpig

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import uesugi.onebot.sdk.client.api.sendGroupMsg
import uesugi.plugin.rollpig.service.RollPigService
import uesugi.plugin.rollpig.store.RollPigStore
import uesugi.spi.Meta
import java.util.*

object RollPigContextFactory {

    fun createFromMeta(
        meta: Meta,
        store: RollPigStore,
        service: RollPigService,
        scope: CoroutineScope,
        isAdmin: Boolean,
    ): RollPigContext {
        val userId = meta.senderId?.toLongOrNull() ?: 0L
        val senderNick = meta.senderId ?: "User"

        return RollPigContext(
            store = store,
            service = service,
            groupId = meta.groupId,
            senderId = userId,
            senderNick = senderNick,
            isAdmin = isAdmin,
            sendMessage = { msg ->
                scope.launch {
                    meta.roledBot.refBot.sendGroupMsg(meta.groupId.toLong(), msg)
                }
            },
            createImage = { bytes ->
                Base64.getEncoder().encodeToString(bytes)
            }
        )
    }
}
