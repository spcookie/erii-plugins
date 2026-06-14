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

    private fun createAnimalContext(): AnimalContext? {
        return AnimalContextFactory.createFromMeta(
            meta = MetaToolSet.meta,
            store = store,
            service = service,
            serverUrl = serverUrl,
            isAdmin = MetaToolSet.meta.isAdmin()
        )
    }

    private fun runCommand(argv: List<String>): String? {
        return try {
            val ctx = createAnimalContext()
            if (ctx == null) {
                return "无法识别当前操作的用户，请直接 @机器人 使用 /animal 命令"
            }
            val parser = AnimalArgParser()
            parser.init(MetaToolSet.meta, ctx)
            parser.main(argv)
            null
        } catch (e: Exception) {
            log.error(e) { "Failed to run command: ${argv.joinToString(" ")}" }
            "执行失败：${e.message}"
        }
    }

    @ChatMessage
    @Tool
    @LLMDescription("注册用户，获取一只随机宠物")
    fun registerAnimal(): String? {
        return runCommand(listOf("register"))
    }

    @ChatMessage
    @Tool
    @LLMDescription("查看用户的宠物列表")
    fun listAnimals(): String? {
        return runCommand(listOf("list"))
    }

    @ChatMessage
    @Tool
    @LLMDescription("查看宠物农场")
    fun viewFarm(): String? {
        return runCommand(listOf("farm"))
    }

    @ChatMessage
    @Tool
    @LLMDescription("查看单只宠物的详细信息，需要宠物ID参数")
    fun viewAnimal(petId: Long): String? {
        if (petId <= 0) return "请提供有效的宠物ID（正整数）"
        return runCommand(listOf("line", petId.toString()))
    }

    @ChatMessage
    @Tool
    @LLMDescription("使用金币抽宠物，100金币抽1次，1000金币抽10次")
    fun drawAnimal(count: Int = 1): String? {
        if (count != 1 && count != 10) return "抽宠次数只能是 1（100金币）或 10（1000金币）"
        return runCommand(listOf("draw", count.toString()))
    }

    @ChatMessage
    @Tool
    @LLMDescription("查看用户金币余额")
    fun viewCoins(): String? {
        return runCommand(listOf("coins"))
    }

    @ChatMessage
    @Tool
    @LLMDescription("售卖宠物，需要宠物ID参数")
    fun sellAnimal(petId: Long): String? {
        if (petId <= 0) return "请提供有效的宠物ID（正整数）"
        return runCommand(listOf("sell", petId.toString()))
    }

    @ChatMessage
    @Tool
    @LLMDescription("设置农场显示的宠物，visible=true显示，visible=false隐藏")
    fun setFarmPet(petId: Long, visible: Boolean): String? {
        if (petId <= 0) return "请提供有效的宠物ID（正整数）"
        val visibleStr = if (visible) "on" else "off"
        return runCommand(listOf("setfarm", petId.toString(), visibleStr))
    }

    @ChatMessage
    @Tool
    @LLMDescription("查看用户已解锁的背景列表")
    fun listFields(): String? {
        return runCommand(listOf("field", "list"))
    }

    @ChatMessage
    @Tool
    @LLMDescription("设置背景，需要背景类型参数如 SNOWY_FIELD")
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
    @LLMDescription("查看今日发言、贡献度、金币、惩罚等状态追踪")
    fun status(): String? {
        return runCommand(listOf("status"))
    }
}