package uesugi.plugin.animal

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import uesugi.onebot.sdk.client.api.sendGroupMsg
import uesugi.onebot.sdk.message.buildMessage
import uesugi.plugin.animal.service.AnimalService
import uesugi.plugin.animal.store.AnimalStore
import uesugi.spi.Meta
import uesugi.spi.isAdmin

class AnimalCommandHandler(
    private val store: AnimalStore,
    private val service: AnimalService,
    private val scope: CoroutineScope,
    private val serverUrl: String
) {

    private val log = KotlinLogging.logger {}

    fun handle(meta: Meta): AnimalContext? {
        val ctx = AnimalContextFactory.createFromMeta(
            meta = meta,
            store = store,
            service = service,
            serverUrl = serverUrl,
            isAdmin = meta.isAdmin()
        ) ?: return null

        return ctx.copy(
            sendMessage = { msg ->
                scope.launch {
                    meta.roledBot.refBot.sendGroupMsg(meta.groupId.toLong(), msg)
                }
            }
        )
    }

    fun handleWithError(meta: Meta, parser: (AnimalContext) -> Unit) {
        try {
            val ctx = handle(meta) ?: return
            parser(ctx)
        } catch (e: Exception) {
            log.error(e) { "Error handling command" }
            scope.launch {
                runCatching {
                    meta.roledBot.refBot.sendGroupMsg(meta.groupId.toLong(), buildMessage {
                        text("执行失败：${e.message}")
                    })
                }
            }
        }
    }
}
