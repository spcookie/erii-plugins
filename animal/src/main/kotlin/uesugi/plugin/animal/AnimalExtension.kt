package uesugi.plugin.animal

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import org.pf4j.Extension
import uesugi.common.BotManage
import uesugi.common.event.BotConnectedEvent
import uesugi.common.toolkit.ConfigHolder
import uesugi.onebot.sdk.client.event.onGroupMessage
import uesugi.plugin.animal.gif.PlaywrightBrowserPool
import uesugi.plugin.animal.service.AnimalService
import uesugi.plugin.animal.service.DailyTaskService
import uesugi.plugin.animal.store.AnimalStore
import uesugi.spi.CmdExtension
import uesugi.spi.PassiveExtension
import uesugi.spi.PluginContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

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
    private val registeredBots = ConcurrentHashMap.newKeySet<String>()

    // groupId -> set of processed messageIds，按群隔离去重
    private val processedMessages = ConcurrentHashMap<Long, MutableSet<Int>>()

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
        registerMessageRewardListeners()

        // 一次性修复存量数据：合并超过3只的同类型宠物
        scope.launch { service.repairAllMerges() }

        // 定期清理已处理的 messageId，防止内存泄漏
        context.scheduler.scheduleRecurrently("cleanup-processed-messages", 3.minutes) {
            processedMessages.clear()
        }

        context.onEvent { event ->
            if (event is BotConnectedEvent && ConfigHolder.isPluginEnabled(event.configKey, "animal")) {
                registerBotListener(event.botId)
            }
        }

        log.info { "AnimalExtension loaded" }
    }

    private fun registerMessageRewardListeners() {
        BotManage.getAllBots().forEach { bot ->
            val configKey = runCatching { BotManage.getConfigKey(bot.selfId) }.getOrNull() ?: return@forEach
            if (!ConfigHolder.isPluginEnabled(configKey, "animal")) return@forEach
            registerBotListener(bot.selfId)
        }
    }

    internal fun registerBotListener(botId: String) {
        if (!registeredBots.add(botId)) return
        val bot = BotManage.getBot(botId) ?: return
        bot.refBot.onGroupMessage { event ->
            if (!scope.isActive) return@onGroupMessage
            val groupMessages = processedMessages.computeIfAbsent(event.groupId) {
                ConcurrentHashMap.newKeySet()
            }
            if (!groupMessages.add(event.messageId)) return@onGroupMessage
            val groupId = event.groupId.toString()
            val senderId = event.userId
            runCatching {
                if (store.getAllGroupIds().contains(groupId)) {
                    service.onUserMessage(groupId, senderId)
                }
            }.onFailure { e ->
                log.error(e) { "Failed to process message reward for $groupId/$senderId" }
            }
        }
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
            AnimalCommandHandler(
                store = store,
                service = service,
                serverUrl = serverUrl
            ).handleWithError(meta) { ctx ->
                withContext(Dispatchers.IO) {
                    meta.parser(ctx)
                }
            }
        }
    }

    override fun onUnload() {
        dailyTaskService.stopDailyTasks()
        context.scheduler.cancel("cleanup-processed-messages")
        processedMessages.clear()
        scope.cancel()
        PlaywrightBrowserPool.close()
        log.info { "AnimalExtension unloaded" }
    }
}
