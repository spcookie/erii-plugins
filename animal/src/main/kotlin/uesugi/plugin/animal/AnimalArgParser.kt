package uesugi.plugin.animal

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import kotlinx.coroutines.runBlocking
import uesugi.onebot.core.model.MessageSegment
import uesugi.onebot.sdk.message.buildMessage
import uesugi.plugin.animal.core.FieldType
import uesugi.plugin.animal.service.AnimalService
import uesugi.plugin.animal.store.AnimalStore
import uesugi.spi.ArgParserHolder
import uesugi.spi.Meta

data class AnimalContext(
    val store: AnimalStore,
    val service: AnimalService,
    val groupId: String,
    val senderId: Long,
    val senderNick: String,
    val sendMessage: (List<MessageSegment>) -> Unit,
    val createImage: (ByteArray) -> String?,
    val serverUrl: String,
    val takeScreenshot: (String) -> ByteArray?
)

class AnimalArgParser : ArgParserHolder<AnimalContext>() {

    private lateinit var context: AnimalContext

    override fun init(meta: Meta, context: AnimalContext) {
        this.context = context
        subcommands(
            Register(),
            ListPets(),
            Farm(),
            Coins(),
            Line(),
            Draw(),
            Sell(),
            SetFarm(),
            Field()
        )
    }

    override fun run() {
        currentContext.findOrSetObject { context }
    }
}

class Register : CliktCommand("register") {
    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            val user = ctx.service.registerUser(ctx.groupId, ctx.senderId, ctx.senderNick)
            val pet = user.personas.firstOrNull()
            val petTypeName = pet?.getType()?.name ?: "未知宠物"

            ctx.sendMessage(buildMessage { text("注册成功！你获得了 $petTypeName！") })

            pet?.let {
                val url = "${ctx.serverUrl}/pet/${ctx.groupId}/${ctx.senderId}/${it.id}"
                val screenshot = ctx.takeScreenshot(url)
                val imageBase64 = screenshot?.let { ctx.createImage(it) }
                ctx.sendMessage(buildMessage {
                    text(url)
                    imageBase64?.let { image("base64://$it") }
                })
            }
        }
    }
}

class ListPets : CliktCommand("list") {
    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            val pets = ctx.service.getUserPets(ctx.groupId, ctx.senderId)
            if (pets.isEmpty()) {
                ctx.sendMessage(buildMessage { text("你还没有宠物") })
                return@runBlocking
            }
            val petList = pets.joinToString("\n") { pet ->
                val price = ctx.service.calculatePetPrice(pet)
                "• [${pet.id}] ${pet.getType().name} Lv.${pet.level()} $:$price"
            }
            val user = ctx.store.getUser(ctx.groupId, ctx.senderId)
            val url = "${ctx.serverUrl}/list/${ctx.groupId}/${ctx.senderId}"
            val screenshot = ctx.takeScreenshot(url)
            val imageBase64 = screenshot?.let { ctx.createImage(it) }
            ctx.sendMessage(buildMessage {
                text(
                    """
                |你的宠物（共 ${pets.size} 只）:
                |$petList
                |金币: ${user?.coins ?: 0}
                |累计贡献度: ${user?.contributionCount() ?: 0}
            """.trimMargin()
                )
            })
            ctx.sendMessage(buildMessage {
                text(url)
                imageBase64?.let { image("base64://$it") }
            })
        }
    }
}

class Farm : CliktCommand("farm") {
    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            val user = ctx.store.getUser(ctx.groupId, ctx.senderId) ?: run {
                ctx.sendMessage(buildMessage { text("你还没有注册宠物") })
                return@runBlocking
            }

            val url = "${ctx.serverUrl}/farm/${ctx.groupId}/${ctx.senderId}"
            val screenshot = ctx.takeScreenshot(url)
            val imageBase64 = screenshot?.let { ctx.createImage(it) }
            ctx.sendMessage(buildMessage {
                text(
                    """
                |农场预览:
                |用户名: ${user.getName()}
                |宠物数: ${user.personas.size}
                |累计贡献: ${user.contributionCount()}
                |背景: ${user.getSelectedField().name}
            """.trimMargin()
                )
            })
            ctx.sendMessage(buildMessage {
                text(url)
                imageBase64?.let { image("base64://$it") }
            })
        }
    }
}

class Coins : CliktCommand("coins") {
    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            val coins = ctx.service.getCoins(ctx.groupId, ctx.senderId)
            ctx.sendMessage(buildMessage { text("你当前有 $coins 金币") })
        }
    }
}

class Line : CliktCommand("line") {
    private val petIdArg: String? by argument().optional()
    val petId: Long? get() = petIdArg?.toLongOrNull()

    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            val pet = ctx.service.viewPet(ctx.groupId, ctx.senderId, petId ?: 0L) ?: run {
                ctx.sendMessage(buildMessage { text("找不到该宠物") })
                return@runBlocking
            }
            val canEvolve = pet.getType().personaEvolution.weight > 0
            val evolutionInfo = if (canEvolve) "可进化！" else "不可进化"

            val url = "${ctx.serverUrl}/pet/${ctx.groupId}/${ctx.senderId}/${pet.id}"
            val screenshot = ctx.takeScreenshot(url)
            val imageBase64 = screenshot?.let { ctx.createImage(it) }
            ctx.sendMessage(buildMessage {
                text(
                    """
                |宠物详情:
                |类型: ${pet.getType().name}
                |等级: Lv.${pet.level()}
                |$evolutionInfo
            """.trimMargin()
                )
            })
            ctx.sendMessage(buildMessage {
                text(url)
                imageBase64?.let { image("base64://$it") }
            })
        }
    }
}

class Draw : CliktCommand("draw") {
    private val countArg: String? by argument().optional()
    val count: Int get() = countArg?.toIntOrNull() ?: 1

    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            val result = ctx.service.drawPet(ctx.groupId, ctx.senderId, count)
            ctx.sendMessage(buildMessage { text(result.getOrElse { "抽宠失败：${it.message}" }) })
        }
    }
}

class Sell : CliktCommand("sell") {
    private val petIdArg: String? by argument().optional()
    val petId: Long get() = petIdArg?.toLongOrNull() ?: 0L

    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            val result = ctx.service.sellPet(ctx.groupId, ctx.senderId, petId)
            ctx.sendMessage(buildMessage { text(result.getOrElse { "售卖失败：$it" }) })
        }
    }
}

class SetFarm : CliktCommand("setfarm") {
    private val petIdArg: String? by argument().optional()
    private val visibleArg: String? by argument().optional()
    val petId: Long get() = petIdArg?.toLongOrNull() ?: 0L
    val visible: Boolean get() = visibleArg?.lowercase()?.let { it != "off" && it != "false" && it != "0" } ?: true

    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            val result = ctx.service.setFarmPet(ctx.groupId, ctx.senderId, petId, visible)
            ctx.sendMessage(buildMessage { text(result.getOrElse { "设置失败：$it" }) })
        }
    }
}

class Field : CliktCommand("field") {
    init {
        subcommands(FieldList(), FieldSet())
    }

    override fun run() {
        // Field has subcommands, this won't be called
    }
}

class FieldList : CliktCommand("list") {
    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            val user = ctx.store.getUser(ctx.groupId, ctx.senderId) ?: run {
                ctx.sendMessage(buildMessage { text("用户不存在") })
                return@runBlocking
            }
            val fields = user.fields
            val selectedField = user.getSelectedField()
            val fieldList = fields.joinToString("\n") { field ->
                val marker = if (field.fieldType == selectedField) " [当前]" else ""
                "• ${field.fieldType.name}$marker"
            }
            ctx.sendMessage(buildMessage {
                text(
                    """
                |已解锁的背景（共 ${fields.size} 个）:
                |$fieldList
            """.trimMargin()
                )
            })
        }
    }
}

class FieldSet : CliktCommand("set") {
    private val fieldTypeArg: String? by argument().optional()
    val fieldType: String get() = fieldTypeArg ?: ""

    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            if (fieldType.isEmpty()) {
                ctx.sendMessage(buildMessage { text("请指定背景类型") })
                return@runBlocking
            }
            val field = try {
                FieldType.valueOf(fieldType.uppercase())
            } catch (e: Exception) {
                ctx.sendMessage(buildMessage { text("未知的背景类型: $fieldType") })
                return@runBlocking
            }
            val result = ctx.service.setField(ctx.groupId, ctx.senderId, field)
            ctx.sendMessage(buildMessage { text(result.getOrElse { "设置失败：$it" }) })
        }
    }
}
