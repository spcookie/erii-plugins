package uesugi.plugin.animal.service

import io.github.oshai.kotlinlogging.KotlinLogging
import uesugi.plugin.animal.core.FieldType
import uesugi.plugin.animal.core.Mode
import uesugi.plugin.animal.core.PersonaType
import uesugi.plugin.animal.domain.Persona
import uesugi.plugin.animal.domain.User
import uesugi.plugin.animal.domain.request.VisibleChangeType
import uesugi.plugin.animal.store.AnimalStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class AnimalService(private val store: AnimalStore) {

    private val log = KotlinLogging.logger {}

    companion object {
        const val COINS_PER_DRAW = 100
        const val COINS_PER_10_DRAW = 1000
        const val CONTRIBUTION_PER_CHECK_IN = 10
        const val CONTRIBUTION_PER_MESSAGES = 10
        const val MESSAGES_PER_CONTRIBUTION = 5
        const val MAX_DAILY_MESSAGE_REWARDS = 5
        const val COINS_PER_CHECK_IN = 10
        const val COINS_PER_MESSAGE_REWARD = 5
        const val FIELD_PENALTY_ON_INACTIVE = 30
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

    suspend fun registerUser(groupId: String, userId: Long, name: String): User {
        val existingUser = store.getUser(groupId, userId)
        if (existingUser != null) {
            return existingUser
        }

        val user = User.newUser(
            id = userId,
            name = name,
            contributions = emptyMap()
        )

        store.saveUser(groupId, user)
        store.addUserId(groupId, userId)

        log.info { "User $userId registered with pet in group $groupId" }
        return user
    }

    suspend fun getUserPets(groupId: String, userId: Long): List<Persona> {
        val user = store.getUser(groupId, userId) ?: return emptyList()
        return user.personas.toList()
    }

    suspend fun viewPet(groupId: String, userId: Long, petId: Long): Persona? {
        val user = store.getUser(groupId, userId) ?: return null
        return user.personas.find { it.id == petId }
    }

    suspend fun onUserMessage(groupId: String, userId: Long): String {
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
        return ""
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

    suspend fun drawPet(groupId: String, userId: Long, count: Int): Result<DrawResult> {
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
        return Result.success(
            DrawResult(
                message = "抽宠成功！花费 $cost 金币，获得：$petInfo",
                pets = drawnPets,
                cost = cost,
            )
        )
    }

    suspend fun sellPet(groupId: String, userId: Long, petId: Long): Result<SellResult> {
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
        return Result.success(SellResult(price, petName, "售卖成功！获得 $price 金币"))
    }

    fun calculatePetPrice(pet: Persona): Int {
        val weight = pet.getType().weight
        val basePrice = if (weight <= 0.0) {
            100  // 进化变体：无法抽取，需升至100级进化获得
        } else {
            maxOf(((1.0 / weight) * 0.5).toInt(), 5)
        }
        return basePrice + pet.level().toInt() * 5
    }

    suspend fun getCoins(groupId: String, userId: Long): Int {
        val user = store.getUser(groupId, userId) ?: return 0
        return user.coins
    }

    suspend fun setFarmPet(groupId: String, userId: Long, petId: Long, visible: Boolean): Result<String> {
        val user = store.getUser(groupId, userId) ?: return Result.failure(Exception("用户不存在"))

        return try {
            user.changePersonaVisible(petId, visible, VisibleChangeType.DEFAULT)
            store.saveUser(groupId, user)
            Result.success("设置成功！宠物 ${if (visible) "已显示" else "已隐藏"}在农场中")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setField(groupId: String, userId: Long, fieldType: FieldType): Result<String> {
        val user = store.getUser(groupId, userId) ?: return Result.failure(Exception("用户不存在"))

        return try {
            user.changeField(fieldType)
            store.saveUser(groupId, user)
            Result.success("背景设置成功！")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun resetDailyTasks() {
        val groupIds = store.getAllGroupIds()

        for (groupId in groupIds) {
            val userIds = store.getAllUserIds(groupId)
            for (userId in userIds) {
                val user = store.getUser(groupId, userId) ?: continue

                // 超过宽限天数未发言，固定扣除贡献度
                val lastActive = user.lastCheckInDate
                if (lastActive != null) {
                    val lastDate = LocalDate.parse(lastActive, dateFormatter)
                    val daysInactive = LocalDate.now().toEpochDay() - lastDate.toEpochDay()
                    if (daysInactive > INACTIVE_GRACE_DAYS) {
                        user.deductContribution(FIELD_PENALTY_ON_INACTIVE)
                        log.info { "User $userId in group $groupId penalized for inactivity ($daysInactive days)" }
                    }
                }

                // 重置今日消息计数
                user.todayMessageCount = 0

                store.saveUser(groupId, user)
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
