package uesugi.plugin.animal

import kotlinx.coroutines.runBlocking
import uesugi.common.toolkit.BrowserScraper
import uesugi.common.toolkit.BrowserScraperHolder
import uesugi.onebot.sdk.client.api.sendGroupMsg
import uesugi.plugin.animal.service.AnimalService
import uesugi.plugin.animal.store.AnimalStore
import uesugi.spi.Meta
import java.util.*

object AnimalContextFactory {

    fun createFromMeta(
        meta: Meta,
        store: AnimalStore,
        service: AnimalService,
        serverUrl: String,
        isAdmin: Boolean,
    ): AnimalContext? {
        val senderId = meta.senderId ?: return null
        val userId = senderId.toLongOrNull() ?: return null
        val senderNick = senderId

        return AnimalContext(
            store = store,
            service = service,
            groupId = meta.groupId,
            senderId = userId,
            senderNick = senderNick,
            isAdmin = isAdmin,
            sendMessage = { msg ->
                runBlocking {
                    meta.roledBot.refBot.sendGroupMsg(meta.groupId.toLong(), msg)
                }
            },
            createImage = { bytes ->
                Base64.getEncoder().encodeToString(bytes)
            },
            serverUrl = serverUrl,
            takeScreenshot = { url, width, height ->
                runCatching {
                    BrowserScraperHolder.getInstance().takeFullScreenshot(
                        url = url,
                        width = width,
                        height = height,
                        quality = 100,
                        type = BrowserScraper.ScreenshotType.PNG,
                        scaleFactor = 2.0,
                    )
                }.getOrNull()
            }
        )
    }
}
