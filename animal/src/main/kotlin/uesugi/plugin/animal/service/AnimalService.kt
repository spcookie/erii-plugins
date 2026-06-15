package uesugi.plugin.animal.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uesugi.plugin.animal.core.FieldType
import uesugi.plugin.animal.core.Mode
import uesugi.plugin.animal.core.PersonaGrade
import uesugi.plugin.animal.core.PersonaType
import uesugi.plugin.animal.domain.Persona
import uesugi.plugin.animal.domain.User
import uesugi.plugin.animal.domain.request.VisibleChangeType
import uesugi.plugin.animal.store.AnimalStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

class AnimalService(private val store: AnimalStore) {

    private val log = KotlinLogging.logger {}
    private val groupLocks = ConcurrentHashMap<String, Mutex>()

    private suspend inline fun <T> withGroupLock(groupId: String, block: suspend () -> T): T {
        val mutex = groupLocks.computeIfAbsent(groupId) { Mutex() }
        return mutex.withLock { block() }
    }

    companion object {
        const val COINS_PER_DRAW = 100
        const val COINS_PER_10_DRAW = 1000
        const val CONTRIBUTION_PER_CHECK_IN = 10
        const val CONTRIBUTION_PER_MESSAGES = 10
        const val MESSAGES_PER_CONTRIBUTION = 5
        const val MAX_DAILY_MESSAGE_REWARDS = 5
        const val COINS_PER_CHECK_IN = 10
        const val COINS_PER_MESSAGE_REWARD = 5
        const val INACTIVE_GRACE_DAYS = 3
    }

    data class DailyStats(
        val checkedInToday: Boolean,
        val messageCount: Int,
        val rewardsClaimed: Int,
        val maxRewards: Int,
        val messagesToNextReward: Int,
        val contributionGained: Int,
        val coinsGained: Int,
        val totalContribution: Long,
        val totalCoins: Int,
        val inactiveDays: Long,
        val penaltyRisk: Boolean,
    )

    suspend fun getDailyStats(groupId: String, userId: Long): DailyStats {
        val user = store.getUser(groupId, userId) ?: return DailyStats(
            checkedInToday = false, messageCount = 0, rewardsClaimed = 0,
            maxRewards = MAX_DAILY_MESSAGE_REWARDS, messagesToNextReward = MESSAGES_PER_CONTRIBUTION,
            contributionGained = 0, coinsGained = 0, totalContribution = 0, totalCoins = 0,
            inactiveDays = 0, penaltyRisk = false
        )
        val today = LocalDate.now().format(dateFormatter)
        val checkedInToday = user.lastCheckInDate == today
        val rewardCount = user.todayMessageCount / MESSAGES_PER_CONTRIBUTION
        val cappedRewards = minOf(rewardCount, MAX_DAILY_MESSAGE_REWARDS)
        val remaining = if (cappedRewards >= MAX_DAILY_MESSAGE_REWARDS) 0
        else MESSAGES_PER_CONTRIBUTION - (user.todayMessageCount % MESSAGES_PER_CONTRIBUTION)

        val contributionGained = (if (checkedInToday) CONTRIBUTION_PER_CHECK_IN else 0) +
                cappedRewards * CONTRIBUTION_PER_MESSAGES
        val coinsGained = (if (checkedInToday) COINS_PER_CHECK_IN else 0) +
                cappedRewards * COINS_PER_MESSAGE_REWARD

        val lastActive = user.lastCheckInDate
        val inactiveDays = if (lastActive != null) {
            LocalDate.now().toEpochDay() - LocalDate.parse(lastActive, dateFormatter).toEpochDay()
        } else 0

        return DailyStats(
            checkedInToday = checkedInToday,
            messageCount = user.todayMessageCount,
            rewardsClaimed = cappedRewards,
            maxRewards = MAX_DAILY_MESSAGE_REWARDS,
            messagesToNextReward = remaining,
            contributionGained = contributionGained,
            coinsGained = coinsGained,
            totalContribution = user.contributionCount(),
            totalCoins = user.coins,
            inactiveDays = inactiveDays,
            penaltyRisk = inactiveDays >= INACTIVE_GRACE_DAYS,
        )
    }

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    suspend fun registerUser(groupId: String, userId: Long, name: String): User = withGroupLock(groupId) {
        store.getUser(groupId, userId) ?: run {
            val user = User.newUser(
                id = userId,
                name = name,
                contributions = emptyMap()
            )

            store.saveUser(groupId, user)
            store.addUserId(groupId, userId)

            log.info { "User $userId registered with pet in group $groupId" }
            user
        }
    }

    suspend fun getUserPets(groupId: String, userId: Long): List<Persona> {
        val user = store.getUser(groupId, userId) ?: return emptyList()
        return user.personas.toList()
    }

    suspend fun viewPet(groupId: String, userId: Long, petId: Long): Persona? {
        val user = store.getUser(groupId, userId) ?: return null
        return user.personas.find { it.id == petId }
    }

    suspend fun onUserMessage(groupId: String, userId: Long): String = withGroupLock(groupId) {
        val user = store.getUser(groupId, userId) ?: return "请先注册宠物，使用 /animal register"
        val today = LocalDate.now().format(dateFormatter)

        user.todayMessageCount++

        // 打卡：当天首次发言
        if (user.lastCheckInDate != today) {
            user.lastCheckInDate = today
            user.todayMessageCount = 1
            user.coins += COINS_PER_CHECK_IN
            user.updateContribution(CONTRIBUTION_PER_CHECK_IN)
            store.saveUser(groupId, user)
            return "打卡成功！+${CONTRIBUTION_PER_CHECK_IN}贡献度 +${COINS_PER_CHECK_IN}金币"
        }

        // 发言奖励：每 MESSAGES_PER_CONTRIBUTION 条触发，每日上限 MAX_DAILY_MESSAGE_REWARDS 次
        val rewardCount = user.todayMessageCount / MESSAGES_PER_CONTRIBUTION
        if (user.todayMessageCount > 0 &&
            user.todayMessageCount % MESSAGES_PER_CONTRIBUTION == 0 &&
            rewardCount <= MAX_DAILY_MESSAGE_REWARDS
        ) {
            user.coins += COINS_PER_MESSAGE_REWARD
            user.updateContribution(CONTRIBUTION_PER_MESSAGES)
            store.saveUser(groupId, user)
            return "发言奖励！+${CONTRIBUTION_PER_MESSAGES}贡献度 +${COINS_PER_MESSAGE_REWARD}金币"
        }

        // 非奖励消息也要持久化 todayMessageCount
        store.saveUser(groupId, user)
        ""
    }

    data class DrawResult(
        val message: String,
        val pets: List<Persona>,
        val cost: Int,
    )

    data class SellResult(
        val price: Int,
        val petName: String,
        val message: String,
    )

    suspend fun drawPet(groupId: String, userId: Long, count: Int): Result<DrawResult> = withGroupLock(groupId) {
        val user = store.getUser(groupId, userId) ?: return Result.failure(Exception("用户不存在"))

        val drawCount = if (count == 10) 10 else 1
        val cost = if (count == 10) COINS_PER_10_DRAW else COINS_PER_DRAW

        if (user.coins < cost) {
            return Result.failure(Exception("金币不足！需要 $cost 金币，当前 ${user.coins} 金币"))
        }

        user.coins -= cost

        val drawnPets = mutableListOf<Persona>()
        repeat(drawCount) {
            val personaType = PersonaType.random()
            val persona = user.addPersona(personaType)
            drawnPets.add(persona)
        }

        store.saveUser(groupId, user)

        val petInfo = drawnPets.joinToString(", ") { "${it.getType()} (Lv.${it.level()})" }
        Result.success(
            DrawResult(
                message = "抽宠成功！花费 $cost 金币，获得：$petInfo",
                pets = drawnPets,
                cost = cost,
            )
        )
    }

    suspend fun sellPet(groupId: String, userId: Long, petId: Long): Result<SellResult> = withGroupLock(groupId) {
        val user = store.getUser(groupId, userId) ?: return Result.failure(Exception("用户不存在"))

        if (user.personas.size <= 1) {
            return Result.failure(Exception("至少需要保留一只宠物"))
        }

        val pet = user.personas.find { it.id == petId }
            ?: return Result.failure(Exception("找不到宠物 $petId"))

        val price = calculatePetPrice(pet)
        val petName = pet.getType().name
        user.coins += price
        user.deletePersona(petId)

        store.saveUser(groupId, user)
        Result.success(SellResult(price, petName, "售卖成功！获得 $price 金币"))
    }

    fun calculatePetPrice(pet: Persona): Int {
        val weight = pet.getType().weight
        val basePrice = if (pet.getType().grade == PersonaGrade.EVOLUTION) {
            // 进化变体：无法抽取，需升至100级进化获得，保底价值反映进化投入
            1000
        } else {
            maxOf(((1.0 / weight) * 0.5).toInt(), 5)
        }
        return basePrice + pet.level().toInt() * 5
    }

    suspend fun getCoins(groupId: String, userId: Long): Int {
        val user = store.getUser(groupId, userId) ?: return 0
        return user.coins
    }

    suspend fun addCoins(groupId: String, userId: Long, amount: Int): Result<String> = withGroupLock(groupId) {
        val user = store.getUser(groupId, userId) ?: return Result.failure(Exception("用户不存在"))
        user.coins += amount
        store.saveUser(groupId, user)
        Result.success("已为 $userId 添加 $amount 金币，当前 ${user.coins} 金币")
    }

    suspend fun deductCoins(groupId: String, userId: Long, amount: Int): Result<String> = withGroupLock(groupId) {
        val user = store.getUser(groupId, userId) ?: return Result.failure(Exception("用户不存在"))
        user.coins -= amount
        store.saveUser(groupId, user)
        Result.success("已扣除 $userId $amount 金币，当前 ${user.coins} 金币")
    }

    suspend fun addCoinsToAll(groupId: String, amount: Int): Result<String> = withGroupLock(groupId) {
        val userIds = store.getAllUserIds(groupId)
        var count = 0
        for (userId in userIds) {
            val user = store.getUser(groupId, userId) ?: continue
            user.coins += amount
            store.saveUser(groupId, user)
            count++
        }
        Result.success("已为 $count 位用户各添加 $amount 金币")
    }

    suspend fun deductCoinsFromAll(groupId: String, amount: Int): Result<String> = withGroupLock(groupId) {
        val userIds = store.getAllUserIds(groupId)
        var count = 0
        for (userId in userIds) {
            val user = store.getUser(groupId, userId) ?: continue
            user.coins -= amount
            store.saveUser(groupId, user)
            count++
        }
        Result.success("已扣除 $count 位用户各 $amount 金币")
    }

    suspend fun setFarmPet(groupId: String, userId: Long, petId: Long, visible: Boolean): Result<String> =
        withGroupLock(groupId) {
        val user = store.getUser(groupId, userId) ?: return Result.failure(Exception("用户不存在"))

        return try {
            user.changePersonaVisible(petId, visible, VisibleChangeType.DEFAULT)
            store.saveUser(groupId, user)
            Result.success("设置成功！宠物　＃${petId} ${if (visible) "已显示" else "已隐藏"}在农场中")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setField(groupId: String, userId: Long, fieldType: FieldType): Result<String> = withGroupLock(groupId) {
        val user = store.getUser(groupId, userId) ?: return Result.failure(Exception("用户不存在"))

        return try {
            user.changeField(fieldType)
            store.saveUser(groupId, user)
            Result.success("背景设置成功！")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun clearStorage() = withGroupLock("__all__") {
        store.clearAll()
    }

    suspend fun resetDailyTasks() {
        val groupIds = store.getAllGroupIds().sorted()

        for (groupId in groupIds) {
            withGroupLock(groupId) {
                val userIds = store.getAllUserIds(groupId)
                for (userId in userIds) {
                    val user = store.getUser(groupId, userId) ?: continue

                    // 超过宽限天数未发言，随机扣减一只宠物1级
                    val lastActive = user.lastCheckInDate
                    if (lastActive != null) {
                        val lastDate = LocalDate.parse(lastActive, dateFormatter)
                        val daysInactive = LocalDate.now().toEpochDay() - lastDate.toEpochDay()
                        if (daysInactive > INACTIVE_GRACE_DAYS) {
                            user.deductRandomPersonaLevel()
                            log.info { "User $userId in group $groupId penalized for inactivity ($daysInactive days), pet level deducted" }
                        }
                    }

                    // 重置今日消息计数
                    user.todayMessageCount = 0

                    store.saveUser(groupId, user)
                }
            }
        }
    }

    suspend fun getAllFieldTypes(groupId: String, userId: Long): List<FieldType> {
        val user = store.getUser(groupId, userId) ?: return emptyList()
        return user.fields.map { it.fieldType }
    }

    suspend fun getSelectedField(groupId: String, userId: Long): FieldType? {
        val user = store.getUser(groupId, userId) ?: return null
        return user.getSelectedField()
    }

    fun buildPersonaSvg(user: User, pet: Persona, mode: Mode): String {
        return pet.toSvgForce(mode)
    }

    fun buildFarmSvg(user: User): String {
        return user.createFarmAnimation()
    }

    fun buildLineSvg(user: User, pet: Persona): String {
        return pet.toSvgForce(Mode.LINE)
    }
}
