package uesugi.plugin.animal

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import uesugi.plugin.animal.core.Mode
import uesugi.plugin.animal.domain.User
import uesugi.plugin.animal.service.AnimalService
import uesugi.plugin.animal.store.AnimalStore
import uesugi.spi.PluginContext

class AnimalHtmlRenderer(
    private val store: AnimalStore,
    private val service: AnimalService,
    private val context: PluginContext
) {

    private val log = KotlinLogging.logger {}

    private inline fun Route.cardRoute(
        path: String,
        notFound: String = "Not found",
        crossinline html: suspend ApplicationCall.() -> String?
    ) {
        get(path) {
            val body = runCatching { html(call) }.getOrNull()
            if (body != null) {
                call.respondText(body, contentType = ContentType.Text.Html)
            } else {
                call.respondText(notFound, status = HttpStatusCode.NotFound)
            }
        }
    }

    fun registerHtmlRoutes() {
        context.server.route {
            cardRoute("/pet/{groupId}/{userId}/{petId}", "Pet not found") {
                getPetHtml(
                    param("groupId"),
                    longParam("userId") ?: return@cardRoute null,
                    longParam("petId") ?: return@cardRoute null
                )
            }

            cardRoute("/farm/{groupId}/{userId}", "Farm not found") {
                getFarmHtml(param("groupId"), longParam("userId") ?: return@cardRoute null)
            }

            cardRoute("/list/{groupId}/{userId}", "List not found") {
                getListHtml(param("groupId"), longParam("userId") ?: return@cardRoute null)
            }

            // === Card routes ===

            cardRoute("/card/pet/{groupId}/{userId}/{petId}", "Pet not found") {
                getPetCardHtml(
                    param("groupId"),
                    longParam("userId") ?: return@cardRoute null,
                    longParam("petId") ?: return@cardRoute null,
                    request.queryParameters["type"] ?: "detail"
                )
            }

            cardRoute("/card/farm/{groupId}/{userId}", "Farm not found") {
                getFarmCardHtml(param("groupId"), longParam("userId") ?: return@cardRoute null)
            }

            cardRoute("/card/status/{groupId}/{userId}", "Status not found") {
                getStatusCardHtml(param("groupId"), longParam("userId") ?: return@cardRoute null)
            }

            cardRoute("/card/help/{groupId}/{userId}", "Help not found") {
                getHelpCardHtml(param("groupId"), longParam("userId") ?: return@cardRoute null)
            }

            cardRoute("/card/coins/{groupId}/{userId}", "User not found") {
                getCoinsCardHtml(param("groupId"), longParam("userId") ?: return@cardRoute null)
            }

            cardRoute("/card/draw/{groupId}/{userId}", "Draw result not found") {
                val idsStr = request.queryParameters["ids"] ?: ""
                val cost = request.queryParameters["cost"]?.toIntOrNull() ?: 0
                val petIds = idsStr.split(",").mapNotNull { it.toLongOrNull() }
                getDrawCardHtml(param("groupId"), longParam("userId") ?: return@cardRoute null, petIds, cost)
            }

            cardRoute("/card/sell/{groupId}/{userId}", "User not found") {
                val price = request.queryParameters["price"]?.toIntOrNull() ?: 0
                val name = request.queryParameters["name"] ?: ""
                getSellCardHtml(param("groupId"), longParam("userId") ?: return@cardRoute null, price, name)
            }

            cardRoute("/card/fields/{groupId}/{userId}", "Fields not found") {
                getFieldsCardHtml(param("groupId"), longParam("userId") ?: return@cardRoute null)
            }

            cardRoute("/card/list/{groupId}/{userId}", "List not found") {
                getListCardHtml(param("groupId"), longParam("userId") ?: return@cardRoute null)
            }
        }
    }

    private fun ApplicationCall.param(name: String): String = parameters[name] ?: ""
    private fun ApplicationCall.longParam(name: String): Long? = parameters[name]?.toLongOrNull()

    private fun userHeaderHtml(user: User, userId: Long, showId: Boolean = false): String {
        val idHtml = if (showId) """<span class="user-id">ID: $userId</span>""" else ""
        return """
            <div class="user-header">
                <span class="user-name">${user.getName()}</span>
                $idHtml
            </div>
        """.trimIndent()
    }

    private fun renderHtml(svgContent: String): String {
        val svg = normalizeSvg(svgContent)
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                    body { margin: 0; }
                    .svg-stage { width: 100%; max-width: 600px; overflow: visible; line-height: 0; }
                    .svg-stage > svg { display: block; width: 100%; height: auto; }
                </style>
            </head>
            <body>
            <div class="svg-stage">$svg</div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun normalizeSvg(svg: String): String {
        if (Regex("""^\s*<svg\b[^>]*\bwidth=""").containsMatchIn(svg)) return svg
        val match = Regex("""^\s*<svg\b([^>]*)>""").find(svg) ?: return svg
        val attrs = match.groupValues[1]
        return svg.replaceRange(match.range, "<svg$attrs width=\"600\" height=\"300\" viewBox=\"0 0 600 300\">")
    }

    // === Swiss-style base layout ===

    private fun swissBase(
        bodyClass: String = "",
        style: String = "",
        body: () -> String
    ): String {
        val bodyHtml = body()
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="UTF-8">
            <style>
                * { margin:0; padding:0; box-sizing:border-box; }
                body {
                    font-family: -apple-system,BlinkMacSystemFont,"Helvetica Neue","PingFang SC","Microsoft YaHei",sans-serif;
                    background: #fafafa;
                    color: #111;
                    -webkit-font-smoothing: antialiased;
                    line-height: 1.5;
                }
                .card {
                    background: #fff;
                    overflow: hidden;
                }
                .rule {
                    height: 2px;
                    background: #111;
                    margin: 0 24px;
                }
                .rule-light {
                    height: 1px;
                    background: #e0e0e0;
                    margin: 0 24px;
                }
                .label {
                    font-size: 10px;
                    font-weight: 500;
                    color: #999;
                    text-transform: uppercase;
                    letter-spacing: 0.8px;
                }
                .value {
                    font-size: 14px;
                    font-weight: 600;
                    color: #111;
                }
                .stat-num {
                    font-size: 28px;
                    font-weight: 700;
                    color: #111;
                    line-height: 1;
                    letter-spacing: -0.5px;
                }
                .stat-label {
                    font-size: 10px;
                    color: #999;
                    text-transform: uppercase;
                    letter-spacing: 0.8px;
                    margin-top: 4px;
                }
                .card-body { line-height: 0; }
                .card-body svg { display: block; max-width: 100%; height: auto; }
                .svg-stage { width: 100%; overflow: visible; line-height: 0; }
                .svg-stage > svg { display: block; width: 100%; height: auto; }
                .user-header { display: flex; align-items: baseline; gap: 12px; padding: 0 24px 16px; }
                .user-header .user-name { font-size: 13px; font-weight: 600; color: #111; }
                .user-header .user-id { font-size: 10px; color: #999; font-family: "SF Mono","Menlo","Monaco","Consolas",monospace; }
                $style
            </style>
            </head>
            <body>
            <div class="card$bodyClass">
            $bodyHtml
            </div>
            </body>
            </html>
        """.trimIndent()
    }

    // === Pet card ===

    suspend fun getPetCardHtml(groupId: String, userId: Long, petId: Long, type: String): String? {
        val user = store.getUser(groupId, userId) ?: return null
        val petIdResolved = if (petId == 0L) user.personas.firstOrNull()?.id ?: return null else petId
        val pet = user.personas.find { it.id == petIdResolved } ?: return null

        val isRegister = type == "register"
        val title = if (isRegister) "REGISTER" else "PET"
        val subtitle = if (isRegister) "你获得了 ${pet.getType().name}" else pet.getType().name

        val canEvolve = pet.getType().personaEvolution.weight > 0
        val price = service.calculatePetPrice(pet)

        val svg = normalizeSvg(user.createLineAnimation(petIdResolved, Mode.LINE))

        val evolveHtml = if (canEvolve) {
            """<div class="tag">可进化</div>"""
        } else ""

        val userHeader = userHeaderHtml(user, userId)

        return swissBase(
            style = """
                .card-head { padding: 28px 24px 24px; }
                .head-label { font-size: 10px; font-weight: 600; color: #999; text-transform: uppercase; letter-spacing: 1.2px; margin-bottom: 4px; }
                .head-title { font-size: 13px; font-weight: 500; color: #555; margin-bottom: 18px; }
                .meta-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px 24px; }
                .tag { display: inline-block; font-size: 10px; font-weight: 600; color: #111; background: #f0f0f0; padding: 3px 8px; margin-top: 14px; letter-spacing: 0.5px; }
                .card-body { padding: 16px 0 0; }
            """.trimIndent()
        ) {
            """
                <div class="card-head">
                    <div class="head-label">$title</div>
                    <div class="head-title">$subtitle</div>
                    <div class="meta-grid">
                        <div>
                            <div class="label">等级</div>
                            <div class="value">Lv.${pet.level()}</div>
                        </div>
                        <div>
                            <div class="label">售价</div>
                            <div class="value">$price 金币</div>
                        </div>
                    </div>
                    $evolveHtml
                </div>
                <div class="rule"></div>
                $userHeader
                <div class="rule-light"></div>
                <div class="card-body">
                    <div class="svg-stage">$svg</div>
                </div>
            """.trimIndent()
        }
    }

    // === Farm card (SVG only, centered with padding) ===

    suspend fun getFarmCardHtml(groupId: String, userId: Long): String? {
        val user = store.getUser(groupId, userId) ?: return null
        val petsInfo = user.personas.joinToString(", ") { "#${it.id}:${it.getType().name}(v=${it.visible})" }
        log.info { "[farm] group=$groupId user=$userId personas=${user.personas.size} [$petsInfo]" }
        val svg = normalizeSvg(user.createFarmAnimation())

        return swissBase(
            style = """
                .card-body { padding: 32px 16px; display: flex; justify-content: center; align-items: center; }
            """.trimIndent()
        ) {
            """
                <div class="card-body">
                    <div class="svg-stage">$svg</div>
                </div>
            """.trimIndent()
        }
    }

    // === List card ===

    suspend fun getListCardHtml(groupId: String, userId: Long): String? {
        val user = store.getUser(groupId, userId) ?: return null
        val pets = user.personas
        val svg = normalizeSvg(user.createListAnimation(Mode.NONE))

        val petRows = pets.joinToString("\n") { pet ->
            val price = service.calculatePetPrice(pet)
            val canEvolve = pet.getType().personaEvolution.weight > 0
            val evolveMark = if (canEvolve) """<span class="evolve-mark">E</span>""" else ""
            """
                <div class="pet-row">
                    <span class="pet-id">#${pet.id}</span>
                    <span class="pet-name">${pet.getType().name}$evolveMark</span>
                    <span class="pet-lv">Lv.${pet.level()}</span>
                    <span class="pet-price">$price</span>
                </div>
            """.trimIndent()
        }

        return swissBase(
            style = """
                .card-head { padding: 24px; }
                .head-label { font-size: 10px; font-weight: 600; color: #999; text-transform: uppercase; letter-spacing: 1.2px; margin-bottom: 4px; }
                .stats-row { display: flex; gap: 40px; margin-top: 16px; }
                .stat-block { }
                .pet-table { padding: 0 24px 16px; }
                .pet-header { display: flex; padding: 8px 0; border-bottom: 1px solid #111; margin-bottom: 4px; }
                .pet-header span { font-size: 10px; font-weight: 600; color: #999; text-transform: uppercase; letter-spacing: 0.8px; }
                .pet-row { display: flex; padding: 7px 0; border-bottom: 1px solid #f0f0f0; font-size: 13px; align-items: center; gap: 8px; }
                .pet-id { width: 52px; flex-shrink: 0; color: #999; font-size: 11px; }
                .pet-name { flex: 1; font-weight: 500; color: #111; }
                .pet-lv { width: 56px; color: #555; text-align: right; }
                .pet-price { width: 56px; font-weight: 600; color: #111; text-align: right; }
                .pet-price::after { content: 'G'; font-size: 10px; color: #999; margin-left: 2px; }
                .evolve-mark { display: inline-block; font-size: 8px; font-weight: 700; color: #111; background: #f0f0f0; padding: 1px 4px; margin-left: 6px; vertical-align: middle; }
                .card-body { padding: 16px 0 0; }
            """.trimIndent()
        ) {
            """
                <div class="card-head">
                    <div class="head-label">COLLECTION</div>
                    <div class="stats-row">
                        <div class="stat-block">
                            <div class="stat-num">${pets.size}</div>
                            <div class="stat-label">宠物</div>
                        </div>
                        <div class="stat-block">
                            <div class="stat-num">${user.coins}</div>
                            <div class="stat-label">金币</div>
                        </div>
                        <div class="stat-block">
                            <div class="stat-num">${user.contributionCount()}</div>
                            <div class="stat-label">贡献</div>
                        </div>
                    </div>
                </div>
                <div class="rule"></div>
                ${userHeaderHtml(user, userId, showId = false)}
                <div class="pet-table">
                    <div class="pet-header">
                        <span class="pet-id">ID</span>
                        <span class="pet-name">名称</span>
                        <span class="pet-lv">等级</span>
                        <span class="pet-price">售价</span>
                    </div>
                    $petRows
                </div>
                <div class="rule-light"></div>
                <div class="card-body">
                    <div class="svg-stage">$svg</div>
                </div>
            """.trimIndent()
        }
    }

    // === Help card ===

    suspend fun getHelpCardHtml(groupId: String, userId: Long): String? {
        val user = store.getUser(groupId, userId) ?: return null

        val commands = listOf(
            "register" to "注册用户，获取一只随机宠物",
            "list" to "查看宠物列表和金币余额",
            "farm" to "查看宠物农场全景",
            "line [id]" to "查看单只宠物详情（默认第一只）",
            "draw [n]" to "抽宠物（100/1000 金币）",
            "sell [id]" to "售卖宠物换取金币",
            "coins" to "查看金币余额",
            "setfarm [id] [on|off]" to "设置农场展示/隐藏宠物",
            "field list" to "查看已解锁背景列表",
            "field set <type>" to "切换当前背景",
            "status" to "查看今日发言、贡献、金币追踪",
            "help" to "显示此帮助信息",
        )

        val rules = listOf(
            "每天首次发言打卡 +10贡献 +10金币",
            "每 5 条消息 +10贡献 +5金币（日上限 5 次）",
            "每累计 30 贡献度获得 1 只随机宠物",
            "每 20 贡献度升级 1 只展示中的宠物（优先等级最低）",
            "隐藏的宠物不参与升级，农场至少展示 1 只宠物",
            "宠物 100 级可进化，等级重置",
            "同类型超过 3 只自动合并：80% 等级转化（最少 3 级）",
            "首次 1000 贡献解锁新背景，之后每 6000 贡献解锁一个",
            "超过 3 天未发言，每日随机扣减 1 只宠物 1 级",
        )

        val commandsHtml = commands.joinToString("\n") { (cmd, desc) ->
            """<div class="cmd-row"><span class="cmd">$cmd</span><span class="desc">$desc</span></div>"""
        }

        val rulesHtml = rules.joinToString("\n") { rule ->
            """<div class="rule-row"><span class="rule-dot"></span><span class="desc">$rule</span></div>"""
        }

        return swissBase(
            style = """
                .card-head { padding: 28px 24px 20px; }
                .head-label { font-size: 10px; font-weight: 600; color: #999; text-transform: uppercase; letter-spacing: 1.2px; margin-bottom: 4px; }
                .head-info { font-size: 12px; color: #777; margin-top: 8px; display: flex; gap: 28px; }
                .head-info strong { color: #111; }
                .section { padding: 20px 24px 0; }
                .section-title { font-size: 10px; font-weight: 600; color: #999; text-transform: uppercase; letter-spacing: 1.2px; margin-bottom: 14px; }
                .cmd-row { display: flex; padding: 4px 0; font-size: 12px; line-height: 1.7; }
                .cmd { font-family: "SF Mono","Menlo","Monaco","Consolas",monospace; font-size: 11px; font-weight: 500; color: #111; white-space: nowrap; min-width: 148px; }
                .desc { color: #555; flex: 1; }
                .rule-row { display: flex; padding: 3px 0; font-size: 12px; line-height: 1.7; }
                .rule-dot { width: 5px; height: 5px; background: #111; margin: 7px 12px 0 0; flex-shrink: 0; }
                .card-footer { padding: 20px 24px 24px; font-size: 11px; color: #bbb; }
            """.trimIndent()
        ) {
            """
                <div class="card-head">
                    <div class="head-label">HELP</div>
                    <div class="head-info">
                        <span>场主 <strong>${user.getName()}</strong></span>
                        <span>金币 <strong>${user.coins}</strong></span>
                    </div>
                </div>
                <div class="rule"></div>
                <div class="section">
                    <div class="section-title">命令</div>
                    $commandsHtml
                </div>
                <div class="section">
                    <div class="section-title">规则</div>
                    $rulesHtml
                </div>
                <div class="card-footer">
                    /animal &lt;命令&gt; 或 @bot 「我的宠物」
                </div>
            """.trimIndent()
        }
    }

    // === Status card ===

    suspend fun getStatusCardHtml(groupId: String, userId: Long): String {
        val user = store.getUser(groupId, userId)
        val stats = service.getDailyStats(groupId, userId)

        val checkInStatus = if (stats.checkedInToday) "已打卡" else "未打卡"
        val checkInColor = if (stats.checkedInToday) "#111" else "#999"

        val penaltyHtml = if (stats.penaltyRisk) {
            """<div class="warn-row"><span class="warn-dot"></span>已 ${stats.inactiveDays} 天未发言，每日随机扣减一只宠物 1 级</div>"""
        } else ""

        return swissBase(
            style = """
                .card-head { padding: 28px 24px 20px; }
                .head-label { font-size: 10px; font-weight: 600; color: #999; text-transform: uppercase; letter-spacing: 1.2px; margin-bottom: 4px; }
                .head-info { font-size: 12px; color: #777; margin-top: 8px; }
                .head-info strong { color: #111; }
                .stats-grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 20px; padding: 24px; }
                .stat-item { }
                .stat-num { font-size: 28px; font-weight: 700; color: #111; line-height: 1; letter-spacing: -0.5px; }
                .stat-num.dim { color: #ccc; }
                .stat-label { font-size: 10px; color: #999; text-transform: uppercase; letter-spacing: 0.8px; margin-top: 4px; }
                .bar-section { padding: 0 24px 20px; }
                .bar-row { display: flex; align-items: center; padding: 6px 0; font-size: 12px; }
                .bar-label { width: 64px; flex-shrink: 0; color: #999; font-size: 10px; text-transform: uppercase; letter-spacing: 0.5px; }
                .bar-track { flex: 1; height: 4px; background: #eee; margin: 0 12px; }
                .bar-fill { height: 4px; background: #111; }
                .bar-value { width: 48px; text-align: right; font-size: 11px; color: #555; flex-shrink: 0; }
                .warn-row { display: flex; align-items: center; margin: 0 24px; padding: 10px 12px; background: #fafafa; font-size: 11px; color: #888; }
                .warn-dot { width: 5px; height: 5px; background: #999; margin-right: 10px; flex-shrink: 0; }
                .card-foot { padding: 16px 24px 0; }
                .gain-row { display: flex; gap: 32px; padding: 8px 0; }
                .gain-item { font-size: 12px; color: #555; }
                .gain-item strong { color: #111; }
            """.trimIndent()
        ) {
            """
                <div class="card-head">
                    <div class="head-label">STATUS</div>
                    <div class="head-info">
                        <span>打卡状态 <strong style="color:$checkInColor">$checkInStatus</strong></span>
                    </div>
                </div>
                <div class="rule"></div>
                ${user?.let { userHeaderHtml(it, userId) } ?: ""}
                <div class="stats-grid">
                    <div class="stat-item">
                        <div class="stat-num">${stats.messageCount}</div>
                        <div class="stat-label">今日发言</div>
                    </div>
                    <div class="stat-item">
                        <div class="stat-num">${stats.totalCoins}</div>
                        <div class="stat-label">金币</div>
                    </div>
                    <div class="stat-item">
                        <div class="stat-num">${stats.totalContribution}</div>
                        <div class="stat-label">总贡献</div>
                    </div>
                </div>
                <div class="bar-section">
                    <div class="bar-row">
                        <span class="bar-label">发言奖励</span>
                        <div class="bar-track">
                            <div class="bar-fill" style="width:${stats.rewardsClaimed * 100 / stats.maxRewards}%"></div>
                        </div>
                        <span class="bar-value">${stats.rewardsClaimed}/${stats.maxRewards}</span>
                    </div>
                    <div class="bar-row" style="margin-top:2px">
                        <span class="bar-label"></span>
                        <span style="font-size:10px;color:#bbb;">距下次奖励还需 ${stats.messagesToNextReward} 条</span>
                    </div>
                </div>
                <div class="rule-light"></div>
                <div class="card-foot">
                    <div class="gain-row">
                        <div class="gain-item">今日贡献 <strong>+${stats.contributionGained}</strong></div>
                        <div class="gain-item">今日金币 <strong>+${stats.coinsGained}</strong></div>
                    </div>
                </div>
                $penaltyHtml
            """.trimIndent()
        }
    }

    // === Coins card ===

    suspend fun getCoinsCardHtml(groupId: String, userId: Long): String? {
        val user = store.getUser(groupId, userId) ?: return null

        return swissBase(
            style = """
                .card-head { padding: 28px 24px 0; }
                .head-label { font-size: 10px; font-weight: 600; color: #999; text-transform: uppercase; letter-spacing: 1.2px; margin-bottom: 4px; }
                .card-body { padding: 24px 24px 36px; text-align: center; }
                .coin-amt { font-size: 56px; font-weight: 700; color: #111; line-height: 1; letter-spacing: -1px; }
                .coin-label { font-size: 11px; color: #999; text-transform: uppercase; letter-spacing: 1.2px; margin-top: 8px; }
            """.trimIndent()
        ) {
            """
                <div class="card-head">
                    <div class="head-label">COINS</div>
                </div>
                <div class="rule"></div>
                ${userHeaderHtml(user, userId, showId = false)}
                <div class="card-body">
                    <div class="coin-amt">${user.coins}</div>
                    <div class="coin-label">金币</div>
                </div>
            """.trimIndent()
        }
    }

    // === Draw card ===

    suspend fun getDrawCardHtml(groupId: String, userId: Long, petIds: List<Long>, cost: Int): String? {
        val user = store.getUser(groupId, userId) ?: return null
        val pets = petIds.mapNotNull { id -> user.personas.find { it.id == id } }

        val petsHtml = pets.joinToString("\n") { pet ->
            val svg = normalizeSvg(user.createLineAnimation(pet.id, Mode.NONE))
            """
                <div class="draw-pet">
                    <div class="draw-pet-svg"><div class="svg-stage">$svg</div></div>
                    <div class="draw-pet-name">${pet.getType().name} <span class="draw-pet-lv">Lv.${pet.level()}</span></div>
                </div>
            """.trimIndent()
        }

        val countLabel = if (pets.size > 1) "${pets.size} 连抽" else "单抽"

        return swissBase(
            style = """
                .card-head { padding: 28px 24px 20px; }
                .head-label { font-size: 10px; font-weight: 600; color: #999; text-transform: uppercase; letter-spacing: 1.2px; margin-bottom: 4px; }
                .head-title { font-size: 13px; font-weight: 500; color: #555; }
                .draw-list { padding: 16px 24px 8px; }
                .draw-pet { margin-bottom: 12px; }
                .draw-pet-svg { line-height: 0; }
                .draw-pet-name { font-size: 12px; font-weight: 500; color: #111; padding: 6px 0 0; }
                .draw-pet-lv { color: #999; }
                .card-foot { padding: 12px 24px 24px; font-size: 12px; color: #777; }
            """.trimIndent()
        ) {
            """
                <div class="card-head">
                    <div class="head-label">DRAW</div>
                    <div class="head-title">$countLabel · 花费 $cost 金币</div>
                </div>
                <div class="rule"></div>
                <div class="draw-list">
                    $petsHtml
                </div>
                <div class="card-foot">
                    剩余金币 ${user.coins}
                </div>
            """.trimIndent()
        }
    }

    // === Sell card ===

    suspend fun getSellCardHtml(groupId: String, userId: Long, price: Int, name: String): String? {
        val user = store.getUser(groupId, userId) ?: return null

        return swissBase(
            style = """
                .card-head { padding: 28px 24px 0; }
                .head-label { font-size: 10px; font-weight: 600; color: #999; text-transform: uppercase; letter-spacing: 1.2px; margin-bottom: 4px; }
                .card-body { padding: 24px 24px 32px; text-align: center; }
                .sell-label { font-size: 10px; font-weight: 600; color: #999; text-transform: uppercase; letter-spacing: 1.2px; }
                .sell-amt { font-size: 48px; font-weight: 700; color: #111; line-height: 1.2; letter-spacing: -1px; }
                .sell-amt::before { content: '+'; }
                .sell-detail { font-size: 12px; color: #777; margin-top: 8px; }
                .sell-balance { font-size: 11px; color: #bbb; margin-top: 16px; }
            """.trimIndent()
        ) {
            """
                <div class="card-head">
                    <div class="head-label">SELL</div>
                </div>
                <div class="rule"></div>
                ${userHeaderHtml(user, userId, showId = false)}
                <div class="card-body">
                    <div class="sell-label">SOLD</div>
                    <div class="sell-amt">$price</div>
                    <div class="sell-detail">售出 $name</div>
                    <div class="sell-balance">余额 ${user.coins} 金币</div>
                </div>
            """.trimIndent()
        }
    }

    // === Fields card ===

    suspend fun getFieldsCardHtml(groupId: String, userId: Long): String? {
        val user = store.getUser(groupId, userId) ?: return null
        val fields = user.fields.toList()
        val selected = user.getSelectedField()

        val fieldsHtml = fields.joinToString("\n") { field ->
            val isSelected = field.fieldType == selected
            val marker = if (isSelected) """<span class="fld-check">当前</span>""" else ""
            val rowClass = if (isSelected) "fld-row selected" else "fld-row"
            val bg = field.fillBackground()
            val border = field.drawBorder()
            val previewSvg =
                """<svg width="240" height="80" viewBox="0 0 600 300" fill="none" xmlns="http://www.w3.org/2000/svg">$bg$border</svg>"""
            """
                <div class="$rowClass">
                    <div class="fld-preview">$previewSvg</div>
                    <div class="fld-info">
                        <span class="fld-name">${field.fieldType.name}</span>
                        $marker
                    </div>
                </div>
            """.trimIndent()
        }

        return swissBase(
            style = """
                .card-head { padding: 28px 24px 20px; }
                .head-label { font-size: 10px; font-weight: 600; color: #999; text-transform: uppercase; letter-spacing: 1.2px; margin-bottom: 4px; }
                .head-count { font-size: 12px; color: #777; }
                .fld-list { padding: 8px 24px 24px; }
                .fld-row { display: flex; align-items: center; padding: 8px 0; border-bottom: 1px solid #f0f0f0; gap: 12px; }
                .fld-row.selected { }
                .fld-preview { width: 240px; height: 80px; flex-shrink: 0; border: 1px solid #e0e0e0; border-radius: 3px; overflow: hidden; line-height: 0; }
                .fld-preview svg { display: block; }
                .fld-info { flex: 1; display: flex; align-items: center; gap: 8px; }
                .fld-name { font-size: 13px; color: #111; }
                .fld-row.selected .fld-name { font-weight: 600; }
                .fld-check { font-size: 10px; font-weight: 600; color: #111; background: #f0f0f0; padding: 2px 8px; }
            """.trimIndent()
        ) {
            """
                <div class="card-head">
                    <div class="head-label">FIELDS</div>
                    <div class="head-count">已解锁 ${fields.size} 个背景</div>
                </div>
                <div class="rule"></div>
                <div class="fld-list">
                    $fieldsHtml
                </div>
            """.trimIndent()
        }
    }

    // === Legacy routes (SVG only) ===

    suspend fun getPetHtml(groupId: String, userId: Long, petId: Long): String? {
        val user = store.getUser(groupId, userId) ?: return null
        val svg = user.createLineAnimation(petId, Mode.LINE)
        return renderHtml(svg)
    }

    suspend fun getFarmHtml(groupId: String, userId: Long): String? {
        val user = store.getUser(groupId, userId) ?: return null
        val svg = user.createFarmAnimation()
        return renderHtml(svg)
    }

    suspend fun getListHtml(groupId: String, userId: Long): String? {
        val user = store.getUser(groupId, userId) ?: return null
        val svg = normalizeSvg(user.createListAnimation(Mode.NONE))
        return renderHtml(svg)
    }
}
