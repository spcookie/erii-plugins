package uesugi.plugin.animal

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import com.github.ajalt.clikt.core.main
import io.github.oshai.kotlinlogging.KotlinLogging
import uesugi.common.ChatMessage
import uesugi.plugin.animal.service.AnimalService
import uesugi.plugin.animal.store.AnimalStore
import uesugi.spi.MetaToolSet
import uesugi.spi.isAdmin

class AnimalToolSet(
    private val store: AnimalStore,
    private val service: AnimalService,
    private val serverUrl: String,
) : MetaToolSet {

    private val log = KotlinLogging.logger {}

    private fun runCommand(argv: List<String>): String? {
        return try {
            val textCollector = mutableListOf<String>()
            val imageCollector = mutableListOf<String>()
            val ctx = AnimalContextFactory.createFromMeta(
                meta = MetaToolSet.meta,
                store = store,
                service = service,
                serverUrl = serverUrl,
                isAdmin = MetaToolSet.meta.isAdmin(),
                textCollector = textCollector,
                imageCollector = imageCollector,
            )
            if (ctx == null) {
                return "无法识别当前操作的用户，请直接 @机器人 使用 /animal 命令"
            }
            val parser = AnimalArgParser()
            parser.init(MetaToolSet.meta, ctx)
            parser.main(argv)

            val outputs = mutableListOf<String>()
            outputs.addAll(textCollector)
            outputs.addAll(imageCollector)
            outputs.takeIf { it.isNotEmpty() }?.joinToString("\n")
        } catch (e: Exception) {
            log.error(e) { "Failed to run command: ${argv.joinToString(" ")}" }
            "执行失败：${e.message}"
        }
    }

    @ChatMessage
    @Tool
    @LLMDescription("注册用户并获取一只随机宠物；新用户必须先注册才能使用其他宠物功能")
    fun registerAnimal(): String? {
        return runCommand(listOf("register"))
    }

    @ChatMessage
    @Tool
    @LLMDescription("查看宠物列表和金币余额")
    fun listAnimals(): String? {
        return runCommand(listOf("list"))
    }

    @ChatMessage
    @Tool
    @LLMDescription("查看宠物农场全景（最多显示20只可见宠物）")
    fun viewFarm(): String? {
        return runCommand(listOf("farm"))
    }

    @ChatMessage
    @Tool
    @LLMDescription("查看用户的宠物农场 GIF 动图（生成耗时约 20~30s）")
    fun viewFarmGif(): String? {
        return runCommand(listOf("ultrafarm"))
    }

    @ChatMessage
    @Tool
    @LLMDescription("查看单只宠物详情；默认显示第一只宠物，传入宠物ID可查看指定宠物")
    fun viewAnimal(petId: Long): String? {
        if (petId <= 0) return "请提供有效的宠物ID（正整数）"
        return runCommand(listOf("line", petId.toString()))
    }

    @ChatMessage
    @Tool
    @LLMDescription("抽宠物，100金币/次，支持1-100次任意次数")
    fun drawAnimal(count: Int = 1): String? {
        if (count <= 0 || count > 100) return "抽宠次数必须是 1 到 100 之间的整数"
        return runCommand(listOf("draw", count.toString()))
    }

    @ChatMessage
    @Tool
    @LLMDescription("查看金币余额")
    fun viewCoins(): String? {
        return runCommand(listOf("coins"))
    }

    @ChatMessage
    @Tool
    @LLMDescription("售卖宠物换取金币，支持批量；传入一个或多个宠物ID，用空格或逗号分隔")
    fun sellAnimal(petIds: String): String? {
        if (petIds.isBlank()) return "请提供要售卖的宠物ID"
        val ids = petIds.split(" ", ",").mapNotNull { it.trim().toLongOrNull() }
        if (ids.isEmpty()) return "请提供有效的宠物ID"
        return runCommand(listOf("sell") + ids.map { it.toString() })
    }

    @ChatMessage
    @Tool
    @LLMDescription("批量设置农场展示/隐藏宠物；visible=true显示，visible=false隐藏；petIds可传入一个或多个ID，用空格或逗号分隔")
    fun setFarmPet(visible: Boolean, petIds: String): String? {
        if (petIds.isBlank()) return "请提供宠物ID"
        val ids = petIds.split(" ", ",").mapNotNull { it.trim().toLongOrNull() }
        if (ids.isEmpty()) return "请提供有效的宠物ID"
        val visibleStr = if (visible) "on" else "off"
        return runCommand(listOf("setfarm", visibleStr) + ids.map { it.toString() })
    }

    @ChatMessage
    @Tool
    @LLMDescription("查看已解锁背景列表")
    fun listFields(): String? {
        return runCommand(listOf("field", "list"))
    }

    @ChatMessage
    @Tool
    @LLMDescription("切换当前背景，需要背景类型参数如 SNOWY_FIELD")
    fun setField(fieldType: String): String? {
        if (fieldType.isBlank()) return "请提供背景类型，例如 SNOWY_FIELD"
        return runCommand(listOf("field", "set", fieldType))
    }

    @ChatMessage
    @Tool
    @LLMDescription("查看宠物游戏帮助和规则说明")
    fun help(): String? {
        return runCommand(listOf("help"))
    }

    @ChatMessage
    @Tool
    @LLMDescription("查看今日发言、贡献、金币追踪")
    fun status(): String? {
        return runCommand(listOf("status"))
    }
}
