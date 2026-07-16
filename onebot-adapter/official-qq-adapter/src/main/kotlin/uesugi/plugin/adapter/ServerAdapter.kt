@file:Definition

package uesugi.plugin.adapter

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigValueType
import kotlinx.coroutines.*
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import uesugi.official.qq.onebot.*
import uesugi.spi.Kv
import uesugi.spi.annotation.*
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

private val json = Json { ignoreUnknownKeys = true }
private val initPattern = Regex("""(?:<@([^>\s]+)>\s*)?/init\s*(.*)""")
private val groupPattern = Regex("""(?:<@([^>\s]+)>\s*)?/group\s*(.*)""")
private val memberPattern = Regex("""(?:<@([^>\s]+)>\s*)?/member\s*(.*)""")
private val helpPattern = Regex("""(?:<@([^>\s]+)>\s*)?/help""")
private val approvePattern = Regex("""(?:<@([^>\s]+)>\s*)?/approve\s+(\d{4})""")
private val rejectPattern = Regex("""(?:<@([^>\s]+)>\s*)?/reject\s+(\d{4})""")
private val envSubstitutionPattern = Regex("""\$\{\??([A-Z_][A-Z0-9_]*)}""")
private const val KV_KEY_MEMBERS = "official-qq-adapter.id-map.members"
private const val KV_KEY_GROUPS = "official-qq-adapter.id-map.groups"
private const val KV_INIT_PREFIX = "official-qq-adapter.init"

private val lifecycleLock = Any()
private var adapterState: AdapterState? = null

private data class PendingApproval(
    val type: String,
    val idx: Int,
    val targetId: Long,
    val groupOpenid: String,
    val memberOpenid: String,
)

private data class AdapterState(
    val runtime: OfficialQqOnebotRuntime,
    val scope: CoroutineScope,
)

@OnLoad
suspend fun init() {
    stopAdapter()

    val kv = useKv()

    val config = buildConfig(kv)
    val botSelfIds = extractSelfIds(config)

    val membersCache = loadMembersFromKv(kv, botSelfIds)
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName("OfficialQqAdapter"))
    val botKeys = botSelfIds.keys.toList()
    val initStates = loadInitStatesFromKv(kv)
    val groupsCache = loadGroupsFromKv(kv, botSelfIds)

    val pendingApprovals = ConcurrentHashMap<String, PendingApproval>()

    fun generateApprovalCode(): String {
        val chars = "0123456789".toCharArray()
        while (true) {
            val code = (1..4).map { chars.random() }.joinToString("")
            if (!pendingApprovals.containsKey(code)) return code
        }
    }

    val gatewayEventHandler: (OfficialGatewayEvent, OfficialQqApiClient) -> Boolean = handler@{ event, client ->
        fun parseNumArgs(rawArgs: String): Pair<Int, Long>? {
            val parts = rawArgs.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
            if (parts.isEmpty()) return null
            val idx: Int
            val num: Long?
            when (parts.size) {
                1 -> {
                    idx = 1; num = parts[0].toLongOrNull()
                }

                2 -> {
                    idx = parts[0].toIntOrNull() ?: 1; num = parts[1].toLongOrNull()
                }

                else -> return null
            }
            return num?.let { idx to it }
        }

        fun resolveMapper(idx: Int): Pair<String, IdMapper>? {
            val key = botKeys.getOrNull(idx - 1) ?: return null
            val mapper = IdMapperRegistry.get(key) ?: return null
            return key to mapper
        }

        fun handleGroupCommand(rawArgs: String, groupOpenid: String): Boolean {
            val (idx, targetGroupId) = parseNumArgs(rawArgs) ?: return false
            val (key, mapper) = resolveMapper(idx) ?: return false
            mapper.addGroupMapping(targetGroupId, groupOpenid)
            groupsCache.getOrPut(key) { mutableMapOf() }[targetGroupId.toString()] = groupOpenid
            persistGroups(scope, kv, groupsCache)
            scope.launch { sendGroupMapSuccess(client, groupOpenid, key, targetGroupId) }
            return true
        }

        fun handleMemberCommand(rawArgs: String, groupOpenid: String, memberOpenid: String): Boolean {
            val (idx, targetMemberId) = parseNumArgs(rawArgs) ?: return false
            val (key, mapper) = resolveMapper(idx) ?: return false
            mapper.addMemberMapping(targetMemberId, memberOpenid)
            membersCache.getOrPut(key) { mutableMapOf() }[targetMemberId.toString()] = memberOpenid
            persistMembers(scope, kv, membersCache)
            scope.launch { sendMemberMapSuccess(client, groupOpenid, key, targetMemberId) }
            return true
        }

        when (event) {
            is OfficialGatewayEvent.GroupMessage if event.type in listOf(
                "GROUP_MESSAGE_CREATE",
                "GROUP_AT_MESSAGE_CREATE"
            ) -> {
                val content = event.event.content.trim()
                val groupOpenid = event.event.groupOpenid
                val memberOpenid = event.event.author.memberOpenid
                val role = event.event.author.memberRole

                val initMatch = initPattern.find(content)
                if (initMatch != null) {
                    if (role !in listOf("owner", "admin")) return@handler false
                    val botOpenid = initMatch.groupValues[1]
                    if (botOpenid.isEmpty()) {
                        scope.launch { sendHelp(client, groupOpenid) }
                        return@handler false
                    }
                    val indices = initMatch.groupValues[2].trim()
                        .split("\\s+".toRegex())
                        .filter { it.isNotBlank() }
                    val targetKeys = if (indices.isEmpty()) {
                        val firstKey = botKeys.firstOrNull() ?: return@handler false
                        listOf(firstKey)
                    } else {
                        indices.mapNotNull { idxStr ->
                            val idx = idxStr.toIntOrNull() ?: return@mapNotNull null
                            botKeys.getOrNull(idx - 1)
                        }
                    }
                    for (key in targetKeys) {
                        val mapper = IdMapperRegistry.get(key)
                        val selfId = botSelfIds[key]
                        if (mapper != null && selfId != null) {
                            mapper.addMemberMapping(selfId, botOpenid)
                            membersCache.getOrPut(key) { mutableMapOf() }[selfId.toString()] = botOpenid
                        }
                    }
                    persistMembers(scope, kv, membersCache)
                    initStates[groupOpenid] = true
                    persistInitState(scope, kv, groupOpenid)
                    scope.launch {
                        sendInitSuccess(client, groupOpenid, targetKeys, botOpenid)
                    }
                    return@handler false
                }

                val groupMatch = groupPattern.find(content)
                if (groupMatch != null) {
                    if (role !in listOf("owner", "admin")) return@handler false
                    if (!handleGroupCommand(groupMatch.groupValues[2], groupOpenid)) {
                        scope.launch { sendHelp(client, groupOpenid) }
                    }
                    return@handler false
                }

                val memberMatch = memberPattern.find(content)
                if (memberMatch != null) {
                    val args = parseNumArgs(memberMatch.groupValues[2])
                    if (args == null) {
                        scope.launch { sendHelp(client, groupOpenid) }
                        return@handler false
                    }
                    val (idx, targetMemberId) = args
                    if (role in listOf("owner", "admin")) {
                        handleMemberCommand(memberMatch.groupValues[2], groupOpenid, memberOpenid)
                    } else {
                        val code = generateApprovalCode()
                        pendingApprovals[code] = PendingApproval(
                            type = "member",
                            idx = idx,
                            targetId = targetMemberId,
                            groupOpenid = groupOpenid,
                            memberOpenid = memberOpenid,
                        )
                        scope.launch {
                            sendGroupMarkdown(
                                client, groupOpenid,
                                "## Pending Approval\n\n" +
                                        "Member mapping request:\n" +
                                        "- Target: `$targetMemberId`\n\n" +
                                        "Reply `/approve $code` to confirm or `/reject $code` to reject."
                            )
                        }
                    }
                    return@handler false
                }

                val approveMatch = approvePattern.find(content)
                if (approveMatch != null) {
                    if (role !in listOf("owner", "admin")) return@handler false
                    val code = approveMatch.groupValues[2]
                    val pending = pendingApprovals.remove(code)
                    if (pending != null && pending.type == "member") {
                        handleMemberCommand(
                            "${pending.idx} ${pending.targetId}",
                            pending.groupOpenid,
                            pending.memberOpenid
                        )
                        scope.launch {
                            sendGroupMarkdown(client, groupOpenid, "## Approved\n\nMember mapping `$code` confirmed.")
                        }
                    } else {
                        scope.launch {
                            sendGroupMarkdown(client, groupOpenid, "## Invalid Code\n\n`$code` not found or expired.")
                        }
                    }
                    return@handler false
                }

                val rejectMatch = rejectPattern.find(content)
                if (rejectMatch != null) {
                    if (role !in listOf("owner", "admin")) return@handler false
                    val code = rejectMatch.groupValues[2]
                    val pending = pendingApprovals.remove(code)
                    if (pending != null) {
                        scope.launch {
                            sendGroupMarkdown(client, groupOpenid, "## Rejected\n\nMember mapping `$code` rejected.")
                        }
                    } else {
                        scope.launch {
                            sendGroupMarkdown(client, groupOpenid, "## Invalid Code\n\n`$code` not found or expired.")
                        }
                    }
                    return@handler false
                }

                if (helpPattern.containsMatchIn(content)) {
                    scope.launch { sendHelp(client, groupOpenid) }
                    return@handler false
                }
                return@handler initStates[groupOpenid] == true
            }

            else -> true
        }
    }

    runCatching {
        val runtime = runOfficialQqOnebot(
            config,
            gatewayEventHandler = gatewayEventHandler,
            installShutdownHook = false,
        )
        synchronized(lifecycleLock) {
            adapterState = AdapterState(runtime, scope)
        }
    }.onFailure {
        scope.cancel(CancellationException("official qq adapter failed to start", it))
        throw it
    }
}

@OnUnload
fun shutdown() {
    runBlocking { stopAdapter() }
}

private suspend fun stopAdapter() {
    val state = synchronized(lifecycleLock) {
        adapterState.also { adapterState = null }
    } ?: return

    state.scope.cancel(CancellationException("official qq adapter unloaded"))
    state.runtime.stop()
}

private suspend fun buildConfig(kv: Kv): Config {
    val pluginConfig = resolveEnvPlaceholders(useConfig()())

    if (pluginConfig.isEmpty) return pluginConfig

    val defaultsBuilder = StringBuilder()
    for (key in pluginConfig.root().keys) {
        defaultsBuilder.appendLine("$key.id-map.auto = true")
    }

    val kvMembers = kv.get(KV_KEY_MEMBERS)
    if (kvMembers != null) {
        val membersByBot = json.decodeFromString<Map<String, Map<String, String>>>(kvMembers)
        for ((botKey, members) in membersByBot) {
            for ((localId, openid) in members) {
                defaultsBuilder.appendLine("$botKey.id-map.members.$localId = \"$openid\"")
            }
        }
    }

    val kvGroups = kv.get(KV_KEY_GROUPS)
    if (kvGroups != null) {
        val groupsByBot = json.decodeFromString<Map<String, Map<String, String>>>(kvGroups)
        for ((botKey, groups) in groupsByBot) {
            for ((localId, groupOpenid) in groups) {
                defaultsBuilder.appendLine("$botKey.id-map.groups.$localId = \"$groupOpenid\"")
            }
        }
    }

    val defaults = ConfigFactory.parseString(defaultsBuilder.toString())
    return pluginConfig.withFallback(defaults)
}

private fun resolveEnvPlaceholders(config: Config): Config {
    val rendered = config.root().render(ConfigRenderOptions.concise().setJson(true))
    val resolved = envSubstitutionPattern.replace(rendered) { match ->
        val name = match.groupValues[1]
        System.getenv(name) ?: ""
    }
    return ConfigFactory.parseString(resolved)
}

private suspend fun loadMembersFromKv(
    kv: Kv,
    botSelfIds: Map<String, Long>,
): MutableMap<String, MutableMap<String, String>> {
    val result = mutableMapOf<String, MutableMap<String, String>>()
    val kvMembers = kv.get(KV_KEY_MEMBERS) ?: return result
    return decodeMapFromString(kvMembers, botSelfIds, result)
}

private fun decodeMapFromString(
    kvs: String,
    botSelfIds: Map<String, Long>,
    result: MutableMap<String, MutableMap<String, String>>
): MutableMap<String, MutableMap<String, String>> {
    val valuesByBot = json.decodeFromString<Map<String, Map<String, String>>>(kvs)
    for ((botKey, values) in valuesByBot) {
        if (botKey in botSelfIds) {
            result[botKey] = values.toMutableMap()
        }
    }
    return result
}

private fun persistMembers(
    scope: CoroutineScope,
    kv: Kv,
    members: Map<String, Map<String, String>>,
) {
    scope.launch {
        kv.set(
            KV_KEY_MEMBERS, json.encodeToString(
                MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer())),
                members
            )
        )
    }
}

private fun extractSelfIds(config: Config): Map<String, Long> {
    val result = mutableMapOf<String, Long>()
    for (key in config.root().keys) {
        if (config.getValue(key).valueType() == ConfigValueType.OBJECT) {
            val selfId = config.getLong("$key.onebot.self-id")
            result[key] = selfId
        }
    }
    return result
}

private suspend fun sendInitSuccess(
    client: OfficialQqApiClient,
    groupOpenid: String,
    keys: List<String>,
    botOpenid: String,
) {
    val botList = keys.joinToString(", ") { "`$it`" }
    sendGroupMarkdown(
        client, groupOpenid,
        "## Init Success\n\n" +
                "ID mapping added:\n" +
                "- Bot instance: `$botList`\n" +
                "- OpenID: `$botOpenid`"
    )
}

private suspend fun sendHelp(
    client: OfficialQqApiClient,
    groupOpenid: String,
) {
    val initCmd = withContext(Dispatchers.IO) {
        URLEncoder.encode("/init", "UTF-8")
    }
    val groupCmd = withContext(Dispatchers.IO) {
        URLEncoder.encode("/group", "UTF-8")
    }
    val memberCmd = withContext(Dispatchers.IO) {
        URLEncoder.encode("/member", "UTF-8")
    }
    val approveCmd = withContext(Dispatchers.IO) {
        URLEncoder.encode("/approve", "UTF-8")
    }
    sendGroupMarkdown(
        client, groupOpenid,
        "## ID Mapping\n\n" +
                "### /init *(owner/admin only)*\n" +
                "`/init [index...]` — map bot QQ number to openid\n" +
                "<qqbot-cmd-input text=\"$initCmd\" show=\"/init\" reference=\"false\" />\n" +
                "- `index` — bot instance index (default: 1)\n\n" +
                "### /group *(owner/admin only)*\n" +
                "`/group [index] num` — map group QQ number to group openid\n" +
                "<qqbot-cmd-input text=\"$groupCmd\" show=\"/group\" reference=\"false\" />\n" +
                "- `index` — bot instance index (default: 1)\n" +
                "- `num` — target group QQ number\n\n" +
                "### /member *(member needs approval)*\n" +
                "`/member [index] num` — map member QQ number to openid\n" +
                "<qqbot-cmd-input text=\"$memberCmd\" show=\"/member\" reference=\"false\" />\n" +
                "- `index` — bot instance index (default: 1)\n" +
                "- `num` — target member QQ number\n" +
                "- owner/admin: executes directly\n" +
                "- member: creates pending approval\n\n" +
                "### /approve /reject *(owner/admin only)*\n" +
                "`/approve code` or `/reject code`\n" +
                "<qqbot-cmd-input text=\"$approveCmd\" show=\"/approve\" reference=\"false\" />\n" +
                "- Confirm or reject a pending mapping request\n\n"
    )
}

private suspend fun loadInitStatesFromKv(kv: Kv): ConcurrentHashMap<String, Boolean> {
    val result = ConcurrentHashMap<String, Boolean>()
    val kvData = kv.get(KV_INIT_PREFIX) ?: return result
    runCatching {
        val map = json.decodeFromString<Map<String, String>>(kvData)
        map.forEach { (groupOpenid, value) -> result[groupOpenid] = value.toBoolean() }
    }
    return result
}

private suspend fun sendGroupMapSuccess(
    client: OfficialQqApiClient,
    groupOpenid: String,
    key: String,
    targetGroupId: Long,
) {
    sendGroupMarkdown(
        client, groupOpenid,
        "## Group Map Success\n\n" +
                "- Bot instance: `$key`\n" +
                "- Target group: `$targetGroupId`"
    )
}

private suspend fun sendMemberMapSuccess(
    client: OfficialQqApiClient,
    groupOpenid: String,
    key: String,
    targetMemberId: Long,
) {
    sendGroupMarkdown(
        client, groupOpenid,
        "## Member Map Success\n\n" +
                "- Bot instance: `$key`\n" +
                "- Target member: `$targetMemberId`"
    )
}

private suspend fun loadGroupsFromKv(
    kv: Kv,
    botSelfIds: Map<String, Long>,
): MutableMap<String, MutableMap<String, String>> {
    val result = mutableMapOf<String, MutableMap<String, String>>()
    val kvGroups = kv.get(KV_KEY_GROUPS) ?: return result
    return decodeMapFromString(kvGroups, botSelfIds, result)
}

private fun persistGroups(
    scope: CoroutineScope,
    kv: Kv,
    groups: Map<String, Map<String, String>>,
) {
    scope.launch {
        kv.set(
            KV_KEY_GROUPS, json.encodeToString(
                MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer())),
                groups
            )
        )
    }
}

private fun persistInitState(scope: CoroutineScope, kv: Kv, groupOpenid: String) {
    scope.launch {
        val current = kv.get(KV_INIT_PREFIX)
        val map = if (current != null) {
            json.decodeFromString<Map<String, String>>(current).toMutableMap()
        } else {
            mutableMapOf()
        }
        map[groupOpenid] = "true"
        kv.set(
            KV_INIT_PREFIX, json.encodeToString(
                MapSerializer(String.serializer(), String.serializer()),
                map
            )
        )
    }
}

private suspend fun sendGroupMarkdown(
    client: OfficialQqApiClient,
    groupOpenid: String,
    markdownContent: String,
) {
    runCatching {
        client.sendGroupMessage(
            groupOpenid = groupOpenid,
            request = SendGroupMessageRequest(
                msgType = 2,
                markdown = MarkdownInfo(content = markdownContent),
            )
        )
    }
}
