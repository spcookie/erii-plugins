package uesugi.plugin.animal

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.pf4j.Extension
import uesugi.plugin.animal.service.AnimalService
import uesugi.plugin.animal.service.DailyTaskService
import uesugi.plugin.animal.store.AnimalStore
import uesugi.spi.CmdExtension
import uesugi.spi.PassiveExtension
import uesugi.spi.PluginContext

@Extension
class AnimalExtension : PassiveExtension<Animal>, CmdExtension<AnimalContext, AnimalArgParser, Animal> {

    override val cmd: String = "animal"

    private val log = KotlinLogging.logger {}

    private lateinit var context: PluginContext
    private lateinit var store: AnimalStore
    private lateinit var service: AnimalService
    private lateinit var dailyTaskService: DailyTaskService
    private lateinit var htmlRenderer: AnimalHtmlRenderer
    private lateinit var serverUrl: String
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onLoad(context: PluginContext) {
        this.context = context
        this.serverUrl = context.server.url.buildString()

        store = AnimalStore(context.kv)
        service = AnimalService(store)

        dailyTaskService = DailyTaskService(store, service, context.scheduler)
        dailyTaskService.startDailyTasks()

        htmlRenderer = AnimalHtmlRenderer(store, service, context)
        htmlRenderer.registerHtmlRoutes()

        registerCommandHandler()
        registerTools()

        log.info { "AnimalExtension loaded" }
    }

    private fun registerTools() {
        context.tool {
            {
                AnimalToolSet(
                    store,
                    service,
                    serverUrl = serverUrl
                )
            }
        }
    }

    private fun registerCommandHandler() {
        context.chain { meta ->
            // 每条消息静默触发发言奖励/打卡
            val senderId = meta.senderId?.toLongOrNull()
            if (senderId != null) {
                runCatching {
                    service.onUserMessage(meta.groupId, senderId)
                }.onFailure { e ->
                    log.error(e) { "Failed to process message reward for ${meta.groupId}/$senderId" }
                }
            }

            AnimalCommandHandler(
                store = store,
                service = service,
                serverUrl = serverUrl,
                scope = scope
            ).handleWithError(meta) { ctx ->
                meta.parser(ctx)
            }
        }
    }

    override fun onUnload() {
        dailyTaskService.stopDailyTasks()
        scope.cancel()
        log.info { "AnimalExtension unloaded" }
    }
}
