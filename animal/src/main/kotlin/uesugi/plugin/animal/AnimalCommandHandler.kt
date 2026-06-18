package uesugi.plugin.animal

import io.github.oshai.kotlinlogging.KotlinLogging
import uesugi.plugin.animal.service.AnimalService
import uesugi.plugin.animal.store.AnimalStore
import uesugi.spi.Meta
import uesugi.spi.isAdmin

class AnimalCommandHandler(
    private val store: AnimalStore,
    private val service: AnimalService,
    private val serverUrl: String
) {

    private val log = KotlinLogging.logger {}

    fun handle(meta: Meta): AnimalContext? {
        return AnimalContextFactory.createFromMeta(
            meta = meta,
            store = store,
            service = service,
            serverUrl = serverUrl,
            isAdmin = meta.isAdmin()
        )
    }

    suspend fun handleWithError(meta: Meta, parser: suspend (AnimalContext) -> Unit) {
        try {
            val ctx = handle(meta) ?: return
            parser(ctx)
        } catch (e: Exception) {
            log.error(e) { "Error handling command" }
        }
    }
}
