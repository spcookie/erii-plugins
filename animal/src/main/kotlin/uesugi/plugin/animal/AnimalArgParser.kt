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
    val isAdmin: Boolean,
    val sendMessage: (List<MessageSegment>) -> Unit,
    val createImage: (ByteArray) -> String?,
    val serverUrl: String,
    val takeScreenshot: (String, Int, Int) -> ByteArray?
)

private fun AnimalContext.sendCard(path: String, width: Int, height: Int) {
    val url = "$serverUrl$path"
    val screenshot = takeScreenshot(url, width, height)
    val imageBase64 = screenshot?.let { createImage(it) }
    sendMessage(buildMessage {
        imageBase64?.let { image("base64://$it") }
    })
}

private suspend fun AnimalContext.runRegisterCommand() {
    val user = service.registerUser(groupId, senderId, senderNick)
    val pet = user.personas.firstOrNull()
    pet?.let {
        sendCard("/card/pet/${groupId}/${senderId}/${it.id}?type=register", 360, 400)
    } ?: sendMessage(buildMessage { text("注册失败，请稍后重试") })
}

private suspend fun AnimalContext.ensureRegistered() {
    if (service.getUserPets(groupId, senderId).isEmpty()) {
        runRegisterCommand()
    }
}

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
            Field(),
            Reset(),
            Help(),
            Status(),
            AddCoins(),
            DeductCoins()
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
            ctx.runRegisterCommand()
        }
    }
}

class ListPets : CliktCommand("list") {
    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            ctx.ensureRegistered()
            val pets = ctx.service.getUserPets(ctx.groupId, ctx.senderId)
            val height = 350 + pets.size * 50
            ctx.sendCard("/card/list/${ctx.groupId}/${ctx.senderId}", 500, height)
        }
    }
}

class Farm : CliktCommand("farm") {
    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            ctx.ensureRegistered()
            ctx.sendCard("/card/farm/${ctx.groupId}/${ctx.senderId}", 600, 400)
        }
    }
}

class Coins : CliktCommand("coins") {
    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            ctx.ensureRegistered()
            ctx.sendCard("/card/coins/${ctx.groupId}/${ctx.senderId}", 260, 190)
        }
    }
}

class Line : CliktCommand("line") {
    private val petIdArg: String? by argument().optional()
    val petId: Long? get() = petIdArg?.toLongOrNull()

    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            ctx.ensureRegistered()
            val resolvedId = petId ?: run {
                val pets = ctx.service.getUserPets(ctx.groupId, ctx.senderId)
                pets.firstOrNull()?.id ?: run {
                    ctx.sendMessage(buildMessage { text("你还没有宠物") })
                    return@runBlocking
                }
            }
            val pet = ctx.service.viewPet(ctx.groupId, ctx.senderId, resolvedId) ?: run {
                ctx.sendMessage(buildMessage { text("找不到该宠物") })
                return@runBlocking
            }

            ctx.sendCard("/card/pet/${ctx.groupId}/${ctx.senderId}/${pet.id}", 360, 400)
        }
    }
}

class Draw : CliktCommand("draw") {
    private val countArg: String? by argument().optional()
    val count: Int get() = countArg?.toIntOrNull() ?: 1

    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            ctx.ensureRegistered()
            val result = ctx.service.drawPet(ctx.groupId, ctx.senderId, count)
            result.onSuccess { drawResult ->
                val ids = drawResult.pets.joinToString(",") { it.id.toString() }
                val height = if (drawResult.pets.size > 1) 600 else 420
                ctx.sendCard("/card/draw/${ctx.groupId}/${ctx.senderId}?ids=$ids&cost=${drawResult.cost}", 500, height)
            }.onFailure {
                ctx.sendMessage(buildMessage { text("抽宠失败：${it.message}") })
            }
        }
    }
}

class Sell : CliktCommand("sell") {
    private val petIdArg: String? by argument().optional()

    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            ctx.ensureRegistered()
            val resolvedId = petIdArg?.toLongOrNull() ?: run {
                val pets = ctx.service.getUserPets(ctx.groupId, ctx.senderId)
                pets.firstOrNull()?.id ?: run {
                    ctx.sendMessage(buildMessage { text("你还没有宠物") })
                    return@runBlocking
                }
            }
            val result = ctx.service.sellPet(ctx.groupId, ctx.senderId, resolvedId)
            result.onSuccess { sellResult ->
                ctx.sendCard(
                    "/card/sell/${ctx.groupId}/${ctx.senderId}?price=${sellResult.price}&name=${sellResult.petName}",
                    400,
                    260
                )
            }.onFailure {
                ctx.sendMessage(buildMessage { text("售卖失败：${it.message}") })
            }
        }
    }
}

class SetFarm : CliktCommand("setfarm") {
    private val petIdArg: String? by argument().optional()
    private val visibleArg: String? by argument().optional()
    val visible: Boolean get() = visibleArg?.lowercase()?.let { it != "off" && it != "false" && it != "0" } ?: true

    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            ctx.ensureRegistered()
            val resolvedId = petIdArg?.toLongOrNull() ?: run {
                val pets = ctx.service.getUserPets(ctx.groupId, ctx.senderId)
                pets.firstOrNull()?.id ?: run {
                    ctx.sendMessage(buildMessage { text("你还没有宠物") })
                    return@runBlocking
                }
            }
            val result = ctx.service.setFarmPet(ctx.groupId, ctx.senderId, resolvedId, visible)
            ctx.sendMessage(buildMessage { text(result.getOrElse { "设置失败：${it.message}" }) })
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
            ctx.ensureRegistered()
            ctx.sendCard("/card/fields/${ctx.groupId}/${ctx.senderId}", 500, 650)
        }
    }
}

class FieldSet : CliktCommand("set") {
    private val fieldTypeArg: String? by argument().optional()
    val fieldType: String get() = fieldTypeArg ?: ""

    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            ctx.ensureRegistered()
            if (fieldType.isEmpty()) {
                ctx.sendMessage(buildMessage { text("请指定背景类型") })
                return@runBlocking
            }
            val field = try {
                FieldType.valueOf(fieldType.uppercase())
            } catch (_: Exception) {
                ctx.sendMessage(buildMessage { text("未知的背景类型: $fieldType") })
                return@runBlocking
            }
            val result = ctx.service.setField(ctx.groupId, ctx.senderId, field)
            ctx.sendMessage(buildMessage { text(result.getOrElse { "设置失败：${it.message}" }) })
        }
    }
}

class Reset : CliktCommand("reset") {
    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            if (!ctx.isAdmin) {
                ctx.sendMessage(buildMessage { text("权限不足，仅管理员可执行 reset") })
                return@runBlocking
            }
            ctx.service.clearStorage()
            ctx.sendMessage(buildMessage { text("已清理所有 animal 数据") })
        }
    }
}

class AddCoins : CliktCommand("add-coins") {
    private val amountArg: String by argument()
    private val userIdArg: String? by argument().optional()

    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            if (!ctx.isAdmin) {
                ctx.sendMessage(buildMessage { text("权限不足，仅管理员可执行 add-coins") })
                return@runBlocking
            }
            val amount = amountArg.toIntOrNull()
            if (amount == null) {
                ctx.sendMessage(buildMessage { text("请输入有效的金币数量") })
                return@runBlocking
            }
            val targetUserId = userIdArg
            val result = if (targetUserId != null) {
                val userId = targetUserId.toLongOrNull()
                if (userId == null) {
                    ctx.sendMessage(buildMessage { text("请输入有效的用户 ID") })
                    return@runBlocking
                }
                ctx.service.addCoins(ctx.groupId, userId, amount)
            } else {
                ctx.service.addCoinsToAll(ctx.groupId, amount)
            }
            ctx.sendMessage(buildMessage { text(result.getOrElse { "操作失败：${it.message}" }) })
        }
    }
}

class DeductCoins : CliktCommand("deduct-coins") {
    private val amountArg: String by argument()
    private val userIdArg: String? by argument().optional()

    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            if (!ctx.isAdmin) {
                ctx.sendMessage(buildMessage { text("权限不足，仅管理员可执行 deduct-coins") })
                return@runBlocking
            }
            val amount = amountArg.toIntOrNull()
            if (amount == null) {
                ctx.sendMessage(buildMessage { text("请输入有效的金币数量") })
                return@runBlocking
            }
            val targetUserId = userIdArg
            val result = if (targetUserId != null) {
                val userId = targetUserId.toLongOrNull()
                if (userId == null) {
                    ctx.sendMessage(buildMessage { text("请输入有效的用户 ID") })
                    return@runBlocking
                }
                ctx.service.deductCoins(ctx.groupId, userId, amount)
            } else {
                ctx.service.deductCoinsFromAll(ctx.groupId, amount)
            }
            ctx.sendMessage(buildMessage { text(result.getOrElse { "操作失败：${it.message}" }) })
        }
    }
}

class Help : CliktCommand("help") {
    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            ctx.ensureRegistered()
            ctx.sendCard("/card/help/${ctx.groupId}/${ctx.senderId}", 500, 520)
        }
    }
}

class Status : CliktCommand("status") {
    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            ctx.ensureRegistered()
            ctx.sendCard("/card/status/${ctx.groupId}/${ctx.senderId}", 360, 430)
        }
    }
}
