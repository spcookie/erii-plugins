package uesugi.plugin.animal

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import uesugi.onebot.core.model.MessageSegment
import uesugi.onebot.sdk.message.buildMessage
import uesugi.plugin.animal.core.FieldType
import uesugi.plugin.animal.gif.FarmGifRenderer
import uesugi.plugin.animal.service.AnimalService
import uesugi.plugin.animal.store.AnimalStore
import uesugi.spi.ArgParserHolder
import uesugi.spi.Meta
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

data class AnimalContext(
    val store: AnimalStore,
    val service: AnimalService,
    val groupId: String,
    val senderId: Long,
    val senderNick: String,
    val isAdmin: Boolean,
    val sendMessage: suspend (List<MessageSegment>) -> Unit,
    val createImage: (ByteArray) -> String?,
    val serverUrl: String,
    val takeScreenshot: (String, Int, Int) -> ByteArray?,
    val textCollector: MutableList<String>? = null,
    val imageCollector: MutableList<String>? = null,
    val ultrafarmInProgress: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    val ultrafarmSemaphore: Semaphore = Semaphore(2),
)

private suspend fun AnimalContext.sendText(text: String) {
    textCollector?.add("[user=$senderId] $text")
    sendMessage(buildMessage {
        text(text)
    })
}

private fun AnimalContext.collectText(text: String) {
    textCollector?.add("[user=$senderId] $text")
}

private fun AnimalContext.collectImage(text: String) {
    imageCollector?.add("[user=$senderId] $text")
}

private suspend fun AnimalContext.sendCard(path: String, width: Int, height: Int, params: String = "") {
    val paramsPart = if (params.isBlank()) "" else ", params=[$params]"
    imageCollector?.add("[user=$senderId] 生成图片卡片: url=$serverUrl$path, width=$width, height=$height$paramsPart")
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
        sendCard(
            "/card/pet/${groupId}/${senderId}/${it.id}?type=register",
            360,
            400,
            "groupId=$groupId, userId=$senderId, petId=${it.id}, type=register"
        )
        collectText("注册成功，获得宠物 #${it.id} [${it.getType().name}] Lv.${it.level()}")
    } ?: sendText("注册失败，请稍后重试")
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
            DeductCoins(),
            Ultrafarm()
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
            ctx.sendCard(
                "/card/list/${ctx.groupId}/${ctx.senderId}",
                500,
                height,
                "groupId=${ctx.groupId}, userId=${ctx.senderId}"
            )
            val petLines = pets.joinToString(", ") { "#${it.id} ${it.getType().name} Lv.${it.level()}" }
            val user = ctx.store.getUser(ctx.groupId, ctx.senderId)
            val coins = user?.coins ?: 0
            ctx.collectText("宠物列表：共 ${pets.size} 只，金币余额 $coins${if (pets.isNotEmpty()) " — $petLines" else ""}")
        }
    }
}

class Farm : CliktCommand("farm") {
    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            ctx.ensureRegistered()
            val user = ctx.store.getUser(ctx.groupId, ctx.senderId)
            val pets = user?.personas ?: emptyList()
            val visible = pets.count { it.visible }
            ctx.sendCard(
                "/card/farm/${ctx.groupId}/${ctx.senderId}",
                600,
                400,
                "groupId=${ctx.groupId}, userId=${ctx.senderId}"
            )
            ctx.collectText("农场状态：共 ${pets.size} 只宠物，展示中 $visible 只")
        }
    }
}

class Coins : CliktCommand("coins") {
    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            ctx.ensureRegistered()
            val user = ctx.store.getUser(ctx.groupId, ctx.senderId)
            val coins = user?.coins ?: 0
            ctx.sendCard(
                "/card/coins/${ctx.groupId}/${ctx.senderId}",
                260,
                190,
                "groupId=${ctx.groupId}, userId=${ctx.senderId}"
            )
            ctx.collectText("金币余额：$coins")
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
                    ctx.sendText("你还没有宠物")
                    return@runBlocking
                }
            }
            val pet = ctx.service.viewPet(ctx.groupId, ctx.senderId, resolvedId) ?: run {
                ctx.sendText("找不到该宠物")
                return@runBlocking
            }

            ctx.sendCard(
                "/card/pet/${ctx.groupId}/${ctx.senderId}/${pet.id}",
                360,
                400,
                "groupId=${ctx.groupId}, userId=${ctx.senderId}, petId=${pet.id}, type=detail"
            )
            ctx.collectText("查看宠物 #${pet.id} [${pet.getType().name}] Lv.${pet.level()}")
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
                val height = 420 + maxOf(0, drawResult.pets.size - 1) * 300
                ctx.sendCard(
                    "/card/draw/${ctx.groupId}/${ctx.senderId}?ids=$ids&cost=${drawResult.cost}",
                    500,
                    height,
                    "groupId=${ctx.groupId}, userId=${ctx.senderId}, petIds=[$ids], cost=${drawResult.cost}"
                )
                val petLines = drawResult.pets.joinToString(", ") { "#${it.id} ${it.getType().name} Lv.${it.level()}" }
                ctx.collectText("抽宠结果：获得 ${drawResult.pets.size} 只宠物（$petLines），消耗 ${drawResult.cost} 金币")
            }.onFailure {
                ctx.sendText("抽宠失败：${it.message}")
            }
        }
    }
}

class Sell : CliktCommand("sell") {
    private val args: List<String> by argument().multiple()

    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            ctx.ensureRegistered()
            val petIds = if (args.isEmpty()) {
                val pets = ctx.service.getUserPets(ctx.groupId, ctx.senderId)
                listOf(pets.firstOrNull()?.id ?: run {
                    ctx.sendText("你还没有宠物")
                    return@runBlocking
                })
            } else {
                val ids = args.mapNotNull { it.toLongOrNull() }
                if (ids.isEmpty() || ids.size != args.size) {
                    ctx.sendText("请输入有效的宠物ID")
                    return@runBlocking
                }
                ids
            }
            val result = ctx.service.sellPets(ctx.groupId, ctx.senderId, petIds)
            result.onSuccess { sellResult ->
                val label = if (sellResult.petCount > 1) "共${sellResult.petCount}只" else sellResult.petName
                ctx.sendCard(
                    "/card/sell/${ctx.groupId}/${ctx.senderId}?price=${sellResult.price}&name=$label",
                    300,
                    260,
                    "groupId=${ctx.groupId}, userId=${ctx.senderId}, price=${sellResult.price}, name=$label"
                )
                ctx.collectText("售卖成功：$label，获得 ${sellResult.price} 金币")
            }.onFailure {
                ctx.sendText("售卖失败：${it.message}")
            }
        }
    }
}

class SetFarm : CliktCommand("setfarm") {
    private val args: List<String> by argument().multiple()

    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            ctx.ensureRegistered()

            val (visible, idArgs) = when {
                args.firstOrNull()?.lowercase() == "off" -> false to args.drop(1)
                args.firstOrNull()?.lowercase() == "on" -> true to args.drop(1)
                else -> true to args
            }

            val ids = if (idArgs.isEmpty()) {
                val pets = ctx.service.getUserPets(ctx.groupId, ctx.senderId)
                listOf(pets.firstOrNull()?.id ?: run {
                    ctx.sendText("你还没有宠物")
                    return@runBlocking
                })
            } else {
                val parsed = idArgs.mapNotNull { it.toLongOrNull() }
                if (parsed.isEmpty() || parsed.size != idArgs.size) {
                    ctx.sendText("请输入有效的宠物ID")
                    return@runBlocking
                }
                parsed
            }

            val result = ctx.service.setFarmPets(ctx.groupId, ctx.senderId, ids, visible)
            ctx.sendText(result.getOrElse { "设置失败：${it.message}" })
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
            val user = ctx.store.getUser(ctx.groupId, ctx.senderId)
            val fields = user?.fields?.toList() ?: emptyList()
            val selected = user?.getSelectedField()?.name ?: "无"
            ctx.sendCard(
                "/card/fields/${ctx.groupId}/${ctx.senderId}",
                500,
                650,
                "groupId=${ctx.groupId}, userId=${ctx.senderId}"
            )
            val fieldNames = fields.joinToString(", ") { it.fieldType.name }
            ctx.collectText("背景列表：已解锁 ${fields.size} 个，当前选中 $selected${if (fields.isNotEmpty()) " — $fieldNames" else ""}")
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
                ctx.sendText("请指定背景类型")
                return@runBlocking
            }
            val field = try {
                FieldType.valueOf(fieldType.uppercase())
            } catch (_: Exception) {
                ctx.sendText("未知的背景类型: $fieldType")
                return@runBlocking
            }
            val result = ctx.service.setField(ctx.groupId, ctx.senderId, field)
            ctx.sendText(result.getOrElse { "设置失败：${it.message}" })
        }
    }
}

class Reset : CliktCommand("reset") {
    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            if (!ctx.isAdmin) {
                ctx.sendText("权限不足，仅管理员可执行 reset")
                return@runBlocking
            }
            ctx.service.clearStorage()
            ctx.sendText("已清理所有 animal 数据")
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
                ctx.sendText("权限不足，仅管理员可执行 add-coins")
                return@runBlocking
            }
            val amount = amountArg.toIntOrNull()
            if (amount == null) {
                ctx.sendText("请输入有效的金币数量")
                return@runBlocking
            }
            val targetUserId = userIdArg
            val result = if (targetUserId != null) {
                val userId = targetUserId.toLongOrNull()
                if (userId == null) {
                    ctx.sendText("请输入有效的用户 ID")
                    return@runBlocking
                }
                ctx.service.addCoins(ctx.groupId, userId, amount)
            } else {
                ctx.service.addCoinsToAll(ctx.groupId, amount)
            }
            ctx.sendText(result.getOrElse { "操作失败：${it.message}" })
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
                ctx.sendText("权限不足，仅管理员可执行 deduct-coins")
                return@runBlocking
            }
            val amount = amountArg.toIntOrNull()
            if (amount == null) {
                ctx.sendText("请输入有效的金币数量")
                return@runBlocking
            }
            val targetUserId = userIdArg
            val result = if (targetUserId != null) {
                val userId = targetUserId.toLongOrNull()
                if (userId == null) {
                    ctx.sendText("请输入有效的用户 ID")
                    return@runBlocking
                }
                ctx.service.deductCoins(ctx.groupId, userId, amount)
            } else {
                ctx.service.deductCoinsFromAll(ctx.groupId, amount)
            }
            ctx.sendText(result.getOrElse { "操作失败：${it.message}" })
        }
    }
}

class Help : CliktCommand("help") {
    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            ctx.ensureRegistered()
            ctx.sendCard(
                "/card/help/${ctx.groupId}/${ctx.senderId}",
                260,
                480,
                "groupId=${ctx.groupId}, userId=${ctx.senderId}"
            )
            ctx.collectText(
                "帮助：register 注册/list 列表/farm 农场/line [id] 查看/draw [n] 抽宠/sell [id] 售卖/coins 金币/" +
                        "setfarm [on|off] [id] 设置展示/field list 背景列表/field set <type> 切换背景/status 状态/help 帮助"
            )
        }
    }
}

class Status : CliktCommand("status") {
    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        runBlocking {
            ctx.ensureRegistered()
            val stats = ctx.service.getDailyStats(ctx.groupId, ctx.senderId)
            ctx.sendCard(
                "/card/status/${ctx.groupId}/${ctx.senderId}",
                360,
                430,
                "groupId=${ctx.groupId}, userId=${ctx.senderId}"
            )
            ctx.collectText(
                "今日状态：发言 ${stats.messageCount} 次，总金币 ${stats.totalCoins}，总贡献 ${stats.totalContribution}，" +
                        "今日贡献 +${stats.contributionGained}，今日金币 +${stats.coinsGained}，" +
                        "发言奖励 ${stats.rewardsClaimed}/${stats.maxRewards}"
            )
        }
    }
}

class Ultrafarm : CliktCommand("ultrafarm") {

    private val log = KotlinLogging.logger {}

    override fun run() {
        val ctx = currentContext.findObject<AnimalContext>() ?: return
        val key = "${ctx.groupId}:${ctx.senderId}"

        if (!ctx.ultrafarmInProgress.add(key)) {
            return
        }

        runBlocking {
            try {
                withTimeout(3.minutes) {
                    ctx.ultrafarmSemaphore.acquire()
                }
            } catch (_: TimeoutCancellationException) {
                ctx.ultrafarmInProgress.remove(key)
                return@runBlocking
            }

            try {
                ctx.ensureRegistered()

                val bytes = FarmGifRenderer().render(ctx.groupId, ctx.senderId, ctx.serverUrl)
                val base64 = ctx.createImage(bytes)
                if (base64 != null) {
                    ctx.sendMessage(buildMessage { image("base64://$base64") })
                    ctx.collectImage("ultrafarm GIF 动图已发送 (${bytes.size} bytes)")
                } else {
                    ctx.sendText("ultrafarm 图片上传失败")
                }
            } catch (e: Exception) {
                log.error(e) { "ultrafarm 生成失败" }
                ctx.sendText("ultrafarm 生成失败")
            } finally {
                ctx.ultrafarmInProgress.remove(key)
                ctx.ultrafarmSemaphore.release()
            }
        }
    }
}
