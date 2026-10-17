// 聊天的进程级状态（iOS: Features/Chat/ChatViewModel.swift，2899 行逐段对照移植）。
//
// 联系人式多会话（与 PC 一致）："assistant" = 你↔秘书主会话；"device:<id>" = 与某台设备的会话。
// 每个会话各自维护 blocks / 分页 / 未读；服务端给所有推送打了 conversation 标签，据此路由。
// **无本地持久化**，冷启动重拉。所有改动都在主线程（AppGraph.scope 是 Main.immediate）上做，不加锁。
package com.geek.umbra.feature.chat

import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.model.KnownDevice
import com.geek.umbra.model.TaskDetail
import com.geek.umbra.navigation.Tab
import com.geek.umbra.navigation.TabBadges
import com.geek.umbra.net.ApiResult
import com.geek.umbra.net.BackgroundWork
import com.geek.umbra.net.HttpResult
import com.geek.umbra.net.ImagePurpose
import com.geek.umbra.net.ImageQuality
import com.geek.umbra.net.ImageTier
import com.geek.umbra.net.PreparedImage
import com.geek.umbra.net.ReasonKind
import com.geek.umbra.net.ServerSettings
import com.geek.umbra.net.SocketState
import com.geek.umbra.net.StorageFmt
import com.geek.umbra.net.UmbraJson
import com.geek.umbra.net.UmbraUrl
import com.geek.umbra.net.UploadError
import com.geek.umbra.net.Uploads
import com.geek.umbra.net.arr
import com.geek.umbra.net.bool
import com.geek.umbra.net.dbl
import com.geek.umbra.net.long
import com.geek.umbra.net.obj
import com.geek.umbra.net.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 「来自哪个会话、哪一条」的定位请求（iOS: ChatFocus.swift）。通知 / 执勤推送进来时由 ChatApi.focus 举起。 */
data class ChatLocateTarget(val conv: String, val messageId: Long)

/**
 * 聊天页「眼前是哪个会话」与「点通知要定位到哪一条」（iOS: ChatFocus.swift）。
 * 通知那一侧在 Compose 树之外，只需要两格状态 —— 一格读、一格写。
 */
object ChatFocus {
    @Volatile var viewingConv: String? = null; private set
    val locate = MutableStateFlow<ChatLocateTarget?>(null)
    fun appear(conv: String) { viewingConv = conv }
    /** 只撤自己置的那一格：从 A 推进 B 时，B 的 appear 可能先于 A 的 disappear 到。 */
    fun disappear(conv: String) { if (viewingConv == conv) viewingConv = null }
}

object ChatStore {
    const val MAIN_CONV = "assistant"

    /** 回复静默超时 120 秒：「一点动静都没有」的时长，不是整轮耗时（带工具的一轮跑几分钟很正常）。 */
    private const val IDLE_TIMEOUT_MS = 120_000L
    /** 图片消息那一轮的最后一道兜底：60 秒内一条后续事件都没有。 */
    private const val VISION_TIMEOUT_MS = 60_000L
    /** 图都传上去、WS 也送出去了，15 秒没等到 message_saved：转失败态并说实话。 */
    private const val RECEIPT_TIMEOUT_MS = 15_000L

    private fun str(id: Int, vararg args: Any): String = AppGraph.context.getString(id, *args)

    // MARK: - 对外状态

    private val _blocks = MutableStateFlow<List<ChatBlock>>(emptyList())
    /** 当前会话的块（驱动对话页）。 */
    val blocks: StateFlow<List<ChatBlock>> = _blocks
    private val _activeConv = MutableStateFlow(MAIN_CONV)
    val activeConv: StateFlow<String> = _activeConv
    private val _unread = MutableStateFlow<Set<String>>(emptySet())
    val unread: StateFlow<Set<String>> = _unread
    private val _devices = MutableStateFlow<List<KnownDevice>>(emptyList())
    val devices: StateFlow<List<KnownDevice>> = _devices
    private val _previews = MutableStateFlow<Map<String, ConvPreview>>(emptyMap())
    val previews: StateFlow<Map<String, ConvPreview>> = _previews
    private val _history = MutableStateFlow(HistoryRowState())
    val historyRow: StateFlow<HistoryRowState> = _history
    private val _resumeAsks = MutableStateFlow<Map<String, ResumeAsk>>(emptyMap())
    val resumeAsks: StateFlow<Map<String, ResumeAsk>> = _resumeAsks

    /** 草稿。变得不再以「/」开头时复位「当普通消息发」，下次再敲 / 面板照常弹。 */
    val draft = MutableStateFlow("")
    /** 输入框上方的引用条；发出去随消息带走（一次性）。 */
    val quote = MutableStateFlow<ChatQuote?>(null)
    /** 「/」快捷输入选中的动作芯片；挂着时动作名以【动作名】前缀并进正文。 */
    val chipAction = MutableStateFlow<SlashAction?>(null)
    /** 面板空态里按过「当普通消息发」。 */
    val slashDismissed = MutableStateFlow(false)
    /** 灵感页「让 Umbra 去做这件事」带过来的来源横幅文案。 */
    val ideaBanner = MutableStateFlow<String?>(null)
    /** 对话页一出现就把键盘叫起来（任务「重新发起」举、对话页接走后放下）。 */
    val composerFocusPending = MutableStateFlow(false)

    fun setDraft(v: String) {
        draft.value = v
        if (!v.startsWith("/") && slashDismissed.value) slashDismissed.value = false
    }

    /** 「/」面板该不该开（语音态 / 只读的条件在页面侧再叠）。 */
    fun slashPanelOn(): Boolean = chipAction.value == null && !slashDismissed.value && draft.value.startsWith("/")
    fun slashQuery(): String = draft.value.drop(1).trim()

    // MARK: - 每个会话的独立状态

    private class ConvStore {
        val blocks = mutableListOf<ChatBlock>()
        var assistantIdx: Int? = null
        val taskMap = HashMap<String, Int>()
        var oldestId: Long? = null
        var hasMoreHistory = true
        var loaded = false
        /** 这段会话曾经真的可以往前翻 —— 「到底了」只给翻过页、或首屏就满了的会话。 */
        var pagedOnce = false
        /** 上一次拉历史没拉到（那一行画「加载失败 · 重试」）。和 hasMoreHistory 必须是两样。 */
        var historyFailed = false
        var paging: ChatHistoryPaging.Cursor
            get() = ChatHistoryPaging.Cursor(oldestId, hasMoreHistory, pagedOnce, historyFailed)
            set(v) { oldestId = v.oldestId; hasMoreHistory = v.hasMore; pagedOnce = v.pagedOnce; historyFailed = v.failed }
    }

    private val stores = LinkedHashMap<String, ConvStore>()
    private var isLoadingHistory = false
    /** 正在等回复的会话（流式事件缺 conversation 时的兜底归属）。 */
    private var pendingConv = MAIN_CONV
    private var replyTimeout: Job? = null
    private var visionTimeout: Job? = null
    private var visionWatch: Pair<String, List<String>>? = null

    private val uploads = HashMap<String, Job>()
    private val lastPct = HashMap<String, Int>()
    private val uploadGen = HashMap<String, Int>()

    private val autoApprovedConfirms = HashSet<String>()
    /** 授权卡 / 问答卡「处理那一帧」发在第几条连接上（ChatCardResend.HandledOnThisConnection 用）。 */
    private val cardSentEpoch = HashMap<String, Int>()
    private val resumeAskFetching = HashSet<String>()
    /** operate 的这次运行跑在哪台电脑上（实时接管要带 device_id）。只在内存里。 */
    private val operateRunDevice = HashMap<String, String>()
    private val operateTaskDevice = HashMap<String, String>()

    private var started = false

    private fun store(conv: String): ConvStore = stores.getOrPut(conv) { ConvStore() }

    // MARK: - 启动 / 守卫

    /** 订阅长连接帧与连接状态。只做一次（ChatModule.register 时调）。 */
    fun start() {
        if (started) return
        started = true
        AppGraph.scope.launch {
            AppGraph.socket.frames.collect { f ->
                // 长连接事件都是服务端推来的：它们连带发出的请求不是人点出来的
                withContext(BackgroundWork) { runCatching { handleMessage(f) } }
            }
        }
        AppGraph.scope.launch {
            AppGraph.socket.state.collect { st ->
                // 连接掉线且有「思考中」的回合在等 → 立即收尾为错误，不用干等超时。Unauthorized 比离线更没救（不重连）。
                if ((st == SocketState.Offline || st == SocketState.Unauthorized) && store(pendingConv).assistantIdx != null) {
                    stopReplyTimeout()
                    failPendingTurn()
                }
            }
        }
    }

    /** 守卫「关」：清空会话内存（未登录期间不留别人的聊天在内存里）。 */
    fun onLock() {
        replyTimeout?.cancel(); replyTimeout = null
        visionTimeout?.cancel(); visionTimeout = null; visionWatch = null
        uploads.values.forEach { it.cancel() }
        uploads.clear(); uploadGen.clear(); lastPct.clear()
        stores.clear()
        autoApprovedConfirms.clear(); cardSentEpoch.clear()
        operateRunDevice.clear(); operateTaskDevice.clear()
        isLoadingHistory = false
        // 「接着跑」那几颗钮退回「还不知道」（不能原样留着：未登录期间挂一颗按了必回 401 的钮）
        _resumeAsks.value = _resumeAsks.value.mapValues { ResumeAsk() }
        _devices.value = emptyList()
        _previews.value = emptyMap()
        _unread.value = emptySet()
        quote.value = null
        updateBadge()
        _blocks.value = emptyList()
        publishHistoryRow()
    }

    /** 守卫「开」：补拉历史 / 会话列表 / 设备列表（后台路径）。 */
    fun onUnlock() {
        AppGraph.scope.launch {
            withContext(BackgroundWork) {
                refetchResumeAsks()
                loadHistory()
                loadConversationsList()
                loadDevices(background = true)
            }
        }
    }

    // MARK: - 联系人 / 设备

    fun convLabel(conv: String): String {
        if (conv == MAIN_CONV) return str(R.string.chat_conv_secretary)
        device(conv)?.let { return it.device_name }
        if (conv.startsWith("device:")) return conv.removePrefix("device:")
        return conv
    }

    fun device(conv: String): KnownDevice? {
        if (!conv.startsWith("device:")) return null
        val id = conv.removePrefix("device:")
        return _devices.value.firstOrNull { it.device_id == id }
    }

    /** 联系人顺序：秘书恒在首位 → 在线设备 → 离线设备（服务端已排好）。 */
    fun contacts(devs: List<KnownDevice>): List<String> = listOf(MAIN_CONV) + devs.map { it.conversation }

    /** background：长连接推来的刷新信号（设备上下线）连带的请求不是人点的，统一出口不打「人引起的」头。 */
    fun loadDevices(background: Boolean = false) {
        AppGraph.scope.launch { if (background) withContext(BackgroundWork) { reloadDevices() } else reloadDevices() }
    }

    /** 下拉刷新用的可等待版本。拉失败（null）保留旧联系人，别把列表清成只剩秘书。 */
    suspend fun reloadDevices() {
        val list = AppGraph.http.get<List<KnownDevice>>("/devices/all")
        if (list != null) _devices.value = list
    }

    /** 从联系人列表移除一台设备（DELETE /devices/{id}，id 按一个路径段编码）。 */
    fun forgetDevice(deviceId: String) {
        AppGraph.scope.launch {
            val r = AppGraph.http.call("DELETE", "/devices/${UmbraUrl.segment(deviceId)}")
            if (r is ApiResult.Ok) _devices.value = _devices.value.filter { it.device_id != deviceId }
        }
    }

    private fun setPreview(conv: String, text: String, at: String?) {
        if (text.isEmpty()) return
        _previews.value = _previews.value + (conv to ConvPreview(text, at))
    }

    // MARK: - 反映到界面

    /** 只把变更反映到当前会话，**不标未读**（删除、找回、卡片作答这类「不是新消息」的变更）。 */
    private fun refresh(conv: String) {
        if (conv == _activeConv.value) {
            _blocks.value = store(conv).blocks.toList()
            publishHistoryRow()
        }
    }

    /** 当前会话 → 更新 blocks；否则标未读。 */
    private fun reflect(conv: String) {
        if (conv == _activeConv.value) {
            _blocks.value = store(conv).blocks.toList()
            publishHistoryRow()
        } else {
            _unread.value = _unread.value + conv
            updateBadge()
        }
    }

    /** 底栏聊天 Tab 角标：只要「有没有」（写 -1 画一颗点）。 */
    private fun updateBadge() {
        if (_unread.value.isNotEmpty()) TabBadges.counts[Tab.Chat] = -1 else TabBadges.counts.remove(Tab.Chat)
    }

    private fun publishHistoryRow() {
        val s = store(_activeConv.value)
        _history.value = HistoryRowState(
            // 「加载失败」那一档空会话也出：首屏没拉到时不能被说成「跟秘书说点什么」
            show = s.historyFailed || (s.blocks.isNotEmpty() && (s.hasMoreHistory || s.pagedOnce)),
            loading = isLoadingHistory,
            failed = s.historyFailed,
            exhausted = !s.hasMoreHistory && s.pagedOnce,
        )
    }

    // MARK: - 历史

    private suspend fun fetchHistory(conv: String, beforeId: Long? = null): List<JsonObject>? =
        AppGraph.http.get<List<JsonObject>>(
            "/history",
            mapOf("limit" to ChatHistoryPaging.PAGE_SIZE, "conversation" to conv, "before_id" to beforeId),
        )

    /** 主会话首屏历史。没拉到（null）时 loaded 不置：下一次还按首次加载去合并。 */
    fun loadHistory() {
        if (isLoadingHistory) return
        isLoadingHistory = true
        AppGraph.scope.launch {
            val messages = withContext(BackgroundWork) { fetchHistory(MAIN_CONV) }
            isLoadingHistory = false
            val s = store(MAIN_CONV)
            val wasLoaded = s.loaded
            // 已经加载过的（重新登入时守卫的「开」会再调一次）不动现有块 —— 本地可能已经往前翻过几页
            val merges = !wasLoaded || s.blocks.isEmpty()
            if (messages != null && merges) mergeHistory(messages, s)
            if (messages != null) s.loaded = true
            s.paging = ChatHistoryPaging.afterFirst(messages?.map { msgId(it) }, merges, s.paging)
            reflect(MAIN_CONV)
        }
    }

    private fun loadConvHistory(conv: String) {
        val s = store(conv)
        if (s.loaded) return
        s.loaded = true
        AppGraph.scope.launch {
            val messages = fetchHistory(conv)
            // 和推送合并，不是二选一（umbra-ios#32）
            if (messages != null) mergeHistory(messages, s) else s.loaded = false
            s.paging = ChatHistoryPaging.afterFirst(messages?.map { msgId(it) }, true, s.paging)
            // 首次进这个会话时把历史铺上，不是「来新消息了」
            refresh(conv)
        }
    }

    private fun msgId(m: JsonObject): Long = m.long("id") ?: 0L

    /**
     * 把首屏这批历史和「历史回来之前已经推到这个会话里的块」合并（判据在 ChatHistoryMerge，有用例）。
     * 合并之后下标类状态整体重算：taskMap 按块重扫、assistantIdx 按它原来指着的那块的 id 重找。
     */
    private fun mergeHistory(messages: List<JsonObject>, s: ConvStore) {
        val hist = messages.mapNotNull { historyToBlock(it) }
        val early = s.blocks.toList()
        val streamingId = s.assistantIdx?.let { if (it < early.size) early[it].id else null }
        val folded = early.map { b ->
            when {
                b is ChatBlock.Device && b.serverId == null ->
                    ChatHistoryMerge.Early(echoKey = ChatHistoryMerge.echoKey("device", b.text), at = ChatHistoryMerge.epoch(b.ts))
                // 设备会话里秘书发给设备的那条也不带 id；正在流式的占位不算（还没落库）
                b is ChatBlock.Assistant && b.serverId == null && !b.thinking && !b.streaming ->
                    ChatHistoryMerge.Early(echoKey = ChatHistoryMerge.echoKey("assistant", b.text), at = ChatHistoryMerge.epoch(b.ts))
                else -> ChatHistoryMerge.Early(serverId = b.serverId)
            }
        }
        val ids = messages.map { msgId(it) }.toSet()
        val claimed = folded.mapNotNull { it.serverId }.toSet()
        val echoRows = messages.filter { msgId(it) !in claimed }.mapNotNull { m ->
            val content = m.str("content") ?: ""
            if (content == "__new_topic__") return@mapNotNull null
            val at = ChatHistoryMerge.epoch(m.str("created_at"))
            when (m.str("role")) {
                "device" -> ChatHistoryMerge.echoKey("device", content) to at
                "user", "system" -> null
                else -> ChatHistoryMerge.echoKey("assistant", content) to at
            }
        }
        val kept = ChatHistoryMerge.keep(folded, ids, echoRows.map { it.first }, echoRows.map { it.second })
        s.blocks.clear()
        s.blocks.addAll(hist)
        s.blocks.addAll(kept.map { early[it] })
        s.taskMap.clear()
        s.blocks.forEachIndexed { i, b -> if (b is ChatBlock.Task) s.taskMap[b.taskId] = i }
        s.assistantIdx = streamingId?.let { id -> s.blocks.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
    }

    /** /conversations：预览与会话顺序。 */
    private fun loadConversationsList() {
        AppGraph.scope.launch {
            val rows = withContext(BackgroundWork) { AppGraph.http.get<List<JsonObject>>("/conversations") } ?: return@launch
            for (r in rows) {
                val conv = r.str("conversation") ?: continue
                store(conv)
                setPreview(conv, r.str("last_content") ?: "", r.str("last_at"))
            }
        }
    }

    /** 历史里的一行 → 一个块。返回可空：`__new_topic__` 分界标记、没有图的图片消息不上屏。 */
    private fun historyToBlock(m: JsonObject): ChatBlock? {
        val content = m.str("content") ?: ""
        if (content == "__new_topic__") return null
        val id = m.long("id")
        val ts = m.str("created_at")
        val meta = m.obj("meta")
        val atts = m.arr("atts").mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.filter { it.isNotEmpty() }
        return when (m.str("role")) {
            "user" -> {
                if ((m.str("kind") ?: "text") == "image") {
                    if (atts.isEmpty()) null else ChatBlock.Image(atts = atts, ts = ts, serverId = id, state = ImageState.Sent)
                } else ChatBlock.User(text = content, ts = ts, serverId = id, quote = ChatQuote.of(meta?.get("quote")), atts = atts)
            }
            "device" -> ChatBlock.Device(text = content, ts = ts, serverId = id)
            // 取消收尾那条系统行是真消息（进库、能删、能找回），重进时照样画成居中一行
            "system" -> ChatBlock.Note(text = content, serverId = id, toolsRun = tools(meta), dutyNotice = meta?.obj("duty_notice"))
            else -> {
                val resumeId = resumeTaskId(meta?.str("action"), meta?.str("task_id"))
                if (resumeId != null) noteResumeAsk(resumeId)
                ChatBlock.Assistant(
                    text = content, ts = ts, serverId = id, interrupted = meta?.bool("interrupted") ?: false,
                    toolsRun = tools(meta), resumeTaskId = resumeId, citations = KbCitations.parse(meta?.get("citations")),
                    duty = meta?.obj("duty"),
                )
            }
        }
    }

    private fun tools(meta: JsonObject?): List<ChatToolRun> =
        meta?.arr("tools")?.mapNotNull { t ->
            val o = t as? JsonObject ?: return@mapNotNull null
            val name = o.str("name")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ChatToolRun(name, o.str("args") ?: "")
        } ?: emptyList()

    /** 「重试」：首屏没拉到（还没有游标）就重拉首屏，否则重翻上一页。 */
    fun retryHistory() {
        val conv = _activeConv.value
        val s = store(conv)
        if (s.oldestId == null) {
            if (conv == MAIN_CONV) loadHistory() else { s.loaded = false; loadConvHistory(conv) }
        } else AppGraph.scope.launch { loadOlderHistory() }
    }

    /** 往前翻一页。没拉到不判到头；插旧消息不是「来新消息了」（refresh 不标未读）。 */
    suspend fun loadOlderHistory() {
        val conv = _activeConv.value
        val s = store(conv)
        val before = s.oldestId ?: return
        if (isLoadingHistory || !s.hasMoreHistory) return
        s.pagedOnce = true
        isLoadingHistory = true
        // 「正在取」那一档要立刻显示（一个只在事情结束后才出现的进行态，等于没有）
        publishHistoryRow()
        val result = fetchHistory(conv, before)
        isLoadingHistory = false
        s.paging = ChatHistoryPaging.afterOlder(result?.map { msgId(it) }, s.paging)
        if (result.isNullOrEmpty()) { refresh(conv); publishHistoryRow(); return }
        val newBlocks = result.mapNotNull { historyToBlock(it) }
        s.blocks.addAll(0, newBlocks)
        val shift = newBlocks.size
        for (k in s.taskMap.keys.toList()) s.taskMap[k] = s.taskMap[k]!! + shift
        s.assistantIdx = s.assistantIdx?.plus(shift)
        refresh(conv)
    }

    // MARK: - 会话切换

    fun switchConversation(conv: String) {
        _activeConv.value = conv
        _unread.value = _unread.value - conv
        updateBadge()
        // 引用是单例状态：带着它发到别的会话，就是一条指向别处的引用，点了永远跳不过去
        quote.value = null
        val s = store(conv)
        _blocks.value = s.blocks.toList()
        publishHistoryRow()
        if (!s.loaded) {
            if (conv == MAIN_CONV) loadHistory() else loadConvHistory(conv)
        }
    }

    // MARK: - 发送

    /** 发到**当前会话**。芯片动作以【动作名】前缀并入正文，气泡里显示的就是这份全文。 */
    fun send() {
        val raw = draft.value.trim()
        if (raw.isEmpty()) return
        val text = chipAction.value?.let { "【${it.label}】$raw" } ?: raw
        setDraft("")
        chipAction.value = null
        ideaBanner.value = null
        val sent = quote.value
        quote.value = null
        deliver(text, sent)
    }

    private fun messageFrame(text: String, conv: String, q: ChatQuote?) = buildJsonObject {
        put("type", "message")
        put("content", text)
        put("client_id", AppGraph.prefs.clientId)
        put("auto_approve_operate", AppGraph.prefs.autoApproveOperate)
        put("conversation", conv)
        // mode 固定 auto：模式条已撤（批次 005），服务端参数保留一段时间
        put("mode", "auto")
        if (q != null) put("quote", q.wire)
    }

    /** 真正把一条消息发出去（乐观气泡 / 占位 / 失败收尾 / 计时）。「重新发送」直接喂这里，不借输入框。 */
    private fun deliver(text: String, q: ChatQuote?) {
        val conv = _activeConv.value
        val s = store(conv)
        val now = ChatTimeRules.nowIso()
        // 上一轮还在转的占位先收尾：assistantIdx 马上要被这一轮覆盖
        sealPlaceholder(s)
        s.blocks += ChatBlock.User(text = text, ts = now, quote = q)
        // 发不出去（离线）就地收尾：标失败、不画占位（原来三个点转满 120 秒才冒「超时」，而消息压根没出门）
        if (!AppGraph.socket.send(messageFrame(text, conv, q))) {
            val last = s.blocks.last() as ChatBlock.User
            s.blocks[s.blocks.lastIndex] = last.copy(failure = SendFailure.Offline)
            stopReplyTimeout()
            setPreview(conv, text, now)
            reflect(conv)
            return
        }
        beginAssistantTurn(conv, text, now)
    }

    /** 开一轮秘书回复：占位气泡 + 记住归属 + 起 120 秒静默计时。图片消息要等全部传完、WS 也送出去之后才开。 */
    private fun beginAssistantTurn(conv: String, preview: String, ts: String) {
        val s = store(conv)
        s.blocks += ChatBlock.Assistant(thinking = true, streaming = true, traceOpen = true, ts = ts)
        s.assistantIdx = s.blocks.lastIndex
        setPreview(conv, preview, ts)
        reflect(conv)
        pendingConv = conv
        armReplyTimeout()
    }

    private fun armReplyTimeout() {
        replyTimeout?.cancel()
        replyTimeout = AppGraph.scope.launch {
            delay(IDLE_TIMEOUT_MS)
            failPendingTurn()
        }
    }

    /** 这一轮收尾 → 停表并置空（handleMessage 靠「replyTimeout != null」判断还在不在等这一轮）。 */
    private fun stopReplyTimeout() {
        replyTimeout?.cancel()
        replyTimeout = null
    }

    private fun failPendingTurn() {
        replyTimeout = null
        val conv = pendingConv
        val s = store(conv)
        val idx = s.assistantIdx ?: return
        if (idx >= s.blocks.size) return
        (s.blocks[idx] as? ChatBlock.Assistant)?.let { s.blocks[idx] = it.copy(thinking = false, streaming = false) }
        s.assistantIdx = null
        s.blocks += ChatBlock.Error(text = str(R.string.chat_status_timeout))
        reflect(conv)
    }

    /** 把还在转的占位就地收尾（只停转，不追错误行）。 */
    private fun sealPlaceholder(s: ConvStore) {
        val idx = s.assistantIdx
        s.assistantIdx = null
        if (idx == null || idx >= s.blocks.size) return
        (s.blocks[idx] as? ChatBlock.Assistant)?.let { s.blocks[idx] = it.copy(thinking = false, streaming = false) }
    }

    // MARK: - 识图：重读与超时兜底（批次 017 / 018）

    /** 「重读这 N 张」。按块 id 找（一条会话两轮识图时，点旧那颗不许重读新的）。本地先举 retrying，钮随即变「正在重读」。 */
    fun retryVision(conv: String, blockId: String) {
        val a = assistantBlock(conv, blockId) ?: return
        val v = a.vision ?: return
        if (v.failedAtts.isEmpty() || v.retrying != 0) return
        val atts = v.failedAtts
        // 没有 message_id（60 秒兜底那条路）就退成「拿 file_id 再发一条纯图消息」
        val mid = v.messageId ?: run { sendExistingAtts(atts, conv); return }
        updateAssistant(a.copy(vision = v.copy(retrying = atts.size)), conv, allowFinished = true)
        val ok = AppGraph.socket.send(buildJsonObject {
            put("type", "vision_retry"); put("message_id", mid)
            put("atts", buildJsonArray { atts.forEach { add(JsonPrimitive(it)) } })
            put("conversation", conv); put("mode", "auto"); put("client_id", AppGraph.prefs.clientId)
        })
        if (!ok) {
            // 没连上：标志放回去，原因换成实话 —— 静默回滚等于点了没反应
            updateAssistant(a.copy(vision = v.copy(retrying = 0, reason = str(R.string.chat_notConnected))), conv, allowFinished = true)
        }
    }

    /** 「重读」在服务端失败了的那条 error：认回那颗正在重读的气泡（台账 vision-retry-error-never-clears）。 */
    private fun failVisionRetry(conv: String, messageId: Long, reason: String?): Boolean {
        val order = listOf(conv) + stores.keys.filter { it != conv }
        for (c in order) {
            val s = stores[c] ?: continue
            for (b in s.blocks) {
                val a = b as? ChatBlock.Assistant ?: continue
                val v = a.vision ?: continue
                if (v.messageId != messageId || v.retrying <= 0) continue
                updateAssistant(a.copy(vision = v.copy(retrying = 0, reason = reason ?: str(R.string.chat_status_error), reasonKind = null, topupUrl = null)), c, allowFinished = true)
                return true
            }
        }
        return false
    }

    private fun imageFrame(atts: List<String>, conv: String) = buildJsonObject {
        put("type", "message"); put("kind", "image")
        put("atts", buildJsonArray { atts.forEach { add(JsonPrimitive(it)) } })
        put("client_id", AppGraph.prefs.clientId); put("conversation", conv)
    }

    /** 拿已经在服务端的 file_id 再发一条纯图消息（不重传文件）。 */
    private fun sendExistingAtts(atts: List<String>, conv: String) {
        if (atts.isEmpty() || !AppGraph.socket.send(imageFrame(atts, conv))) return
        val s = store(conv)
        s.blocks += ChatBlock.Image(atts = atts, ts = ChatTimeRules.nowIso(), state = ImageState.AwaitingReceipt)
        sealPlaceholder(s)
        beginAssistantTurn(conv, str(R.string.chat_imageMsgPreview), ChatTimeRules.nowIso())
        armVisionTimeout(conv, atts)
    }

    /** 这一轮到此为止（全失败 / 60 秒兜底）：表要停、落点也要清。 */
    private fun finishTurn(conv: String) {
        stopReplyTimeout()
        stopVisionTimeout(conv)
        store(conv).assistantIdx = null
    }

    /** 补跑那一轮重新登记落点（重读成功后服务端会再跑一次模型，那时 assistantIdx 早被清了）。 */
    private fun rearmTurn(conv: String, blockId: String) {
        val s = store(conv)
        val i = s.blocks.indexOfFirst { it is ChatBlock.Assistant && it.id == blockId }
        if (i >= 0) s.assistantIdx = i
        pendingConv = conv
        armReplyTimeout()
    }

    private fun assistantBlock(conv: String, id: String): ChatBlock.Assistant? =
        store(conv).blocks.firstOrNull { it is ChatBlock.Assistant && it.id == id } as? ChatBlock.Assistant

    /** 收 vision_done 时往哪颗块上写：优先按 message_id 认（重读回来的落在已收工的气泡上），认不到退回正在跑的那一轮。 */
    private fun assistantForVision(conv: String, messageId: Long?): ChatBlock.Assistant? {
        if (messageId != null) {
            store(conv).blocks.asReversed().firstOrNull { it is ChatBlock.Assistant && it.vision?.messageId == messageId }
                ?.let { return it as ChatBlock.Assistant }
        }
        return currentAssistant(conv)
    }

    /** 图片消息那一轮的 60 秒兜底：一条后续事件都没有 → 占位换成错误卡 +「重读这 N 张」。 */
    private fun armVisionTimeout(conv: String, atts: List<String>) {
        visionTimeout?.cancel()
        visionWatch = conv to atts
        visionTimeout = AppGraph.scope.launch {
            delay(VISION_TIMEOUT_MS)
            visionTimeout = null
            visionWatch = null
            val a = currentAssistant(conv) ?: return@launch
            if (a.vision?.label?.isEmpty() == false) return@launch
            val v0 = a.vision ?: VisionState()
            val v = v0.copy(
                timedOut = true, reading = false, n = maxOf(v0.n, atts.size), total = maxOf(v0.total, atts.size),
                failedAtts = atts, messageId = imageServerId(conv, atts),
            )
            updateAssistant(a.copy(vision = v, thinking = false, streaming = false), conv, allowFinished = true)
            finishTurn(conv)
        }
    }

    private fun imageServerId(conv: String, atts: List<String>): Long? =
        (store(conv).blocks.asReversed().firstOrNull { it is ChatBlock.Image && it.atts == atts } as? ChatBlock.Image)?.serverId

    /** 只有这一轮自己的事件才该撤它（别的会话的推送不该撤）。 */
    private fun stopVisionTimeout(conv: String) {
        val w = visionWatch ?: return
        if (w.first != conv) return
        visionTimeout?.cancel(); visionTimeout = null; visionWatch = null
    }

    // MARK: - 图片消息（批次 011 ③，016 起接进秘书）

    /** 发一条图片消息：先把块画出来（本地图当预览），再逐张传，全传完才送 WS。 */
    fun sendImages(images: List<ByteArray>, tierOnce: ImageTier?, preps: List<PreparedImage?>, ratio: Float?) {
        if (images.isEmpty()) return
        val conv = _activeConv.value
        val s = store(conv)
        // 张数对不上就整批丢掉：宁可多压一遍，也不能把 A 图的字节安到 B 图头上
        val p = if (preps.size == images.size) preps else emptyList()
        val upSizes = images.indices.map { i -> p.getOrNull(i)?.data?.size ?: images[i].size }
        val blk = ChatBlock.Image(
            data = images, ts = ChatTimeRules.nowIso(), tierOnce = tierOnce, preps = p,
            upSizes = upSizes, totalBytes = upSizes.sum(),
            // 单图比例钳进 [0.4, 2.5]：长截图不钳会画成 220×1760 再被裁掉中间一截
            ratioHint = if (images.size == 1) ratio?.let { minOf(2.5f, maxOf(0.4f, it)) } else null,
        )
        s.blocks += blk
        setPreview(conv, str(R.string.chat_imageMsgPreview), blk.ts)
        reflect(conv)
        runUpload(blk.id, conv)
    }

    /** 按「聊天」那一档（或这一次的覆盖档）缩图。拿不到档位表就原样发（同 iOS MoneySettingsStore.prepareImage）。 */
    suspend fun prepareChatImage(data: ByteArray, override: ImageTier?): PreparedImage {
        val untouched = PreparedImage(data, data.size, data.size, transcoded = false, png = false)
        val tier = override ?: (if (ServerSettings.settings.value == null) null else ServerSettings.tier(ImagePurpose.Chat).first) ?: return untouched
        val px = longEdge(tier) ?: return untouched
        return ImageQuality.prepare(data, px)
    }

    /** 各档长边像素（服务端 image_tiers）；原尺寸 = 0（不缩）。拿不到 → null。 */
    fun longEdge(tier: ImageTier): Int? = if (tier == ImageTier.Origin) 0 else ServerSettings.imageTiers.value[tier.wire]

    /** 当前设置里聊天那一档；设置还没拉到时 null（不缩、也不显示数）。 */
    fun settingsChatTier(): ImageTier? = if (ServerSettings.settings.value == null) null else ServerSettings.tier(ImagePurpose.Chat).first

    private fun imageBlock(blockId: String): ChatBlock.Image? {
        for (s in stores.values) for (b in s.blocks) if (b is ChatBlock.Image && b.id == blockId) return b
        return null
    }

    private fun mutateImage(blockId: String, body: (ChatBlock.Image) -> ChatBlock.Image) {
        for ((conv, s) in stores) {
            val i = s.blocks.indexOfFirst { it is ChatBlock.Image && it.id == blockId }
            if (i < 0) continue
            s.blocks[i] = body(s.blocks[i] as ChatBlock.Image)
            refresh(conv)
            return
        }
    }

    /** 断点续传：从 atts.size 那张接着传，传完送 WS。不重传已传成功的（会在服务端留孤儿文件）。 */
    private fun runUpload(blockId: String, conv: String) {
        uploads[blockId]?.cancel()
        val gen = (uploadGen[blockId] ?: 0) + 1
        uploadGen[blockId] = gen
        uploads[blockId] = AppGraph.scope.launch {
            try {
                while (true) {
                    val blk = imageBlock(blockId) ?: return@launch
                    if (blk.atts.size >= blk.data.size) break
                    val idx = blk.atts.size
                    val cached = blk.preps.getOrNull(idx)
                    val prep = cached ?: prepareChatImage(blk.data[idx], blk.tierOnce)
                    // 缩图是挂起点：这中间可能已经取消 / 删掉了
                    if (!isActive) return@launch
                    val bytes = prep.data
                    mutateImage(blockId) { b ->
                        if (idx !in b.upSizes.indices) b else {
                            val sizes = b.upSizes.toMutableList().also { it[idx] = bytes.size }
                            b.copy(upSizes = sizes, totalBytes = sizes.sum())
                        }
                    }
                    val doneBytes = (imageBlock(blockId)?.upSizes ?: emptyList()).take(idx).sum()
                    val name = prep.filename("chat-${System.currentTimeMillis() / 1000}-$idx.jpg")
                    val (up, err) = Uploads.upload(bytes, name, onProgress = { frac ->
                        // 进度回调在 IO 线程：切回主线程再改块；代次号 + 张号拦迟到的回调
                        AppGraph.scope.launch { noteProgress(blockId, gen, idx, frac, doneBytes, minOf((frac * bytes.size).toInt(), bytes.size)) }
                    })
                    if (!isActive) return@launch
                    if (up != null) {
                        mutateImage(blockId) { b -> b.copy(atts = b.atts + up.file_id, sentBytes = doneBytes + bytes.size, currentFrac = 0f) }
                    } else {
                        // 失败分档：四种失败用户能做的事完全不同（太大 / 未登录 / 存储满 / 其他）
                        mutateImage(blockId) { b -> b.copy(state = ImageState.Failed, failKind = err, failReason = uploadFailText(err, bytes.size)) }
                        return@launch
                    }
                }
                val blk = imageBlock(blockId) ?: return@launch
                if (blk.state != ImageState.Uploading || blk.atts.size != blk.data.size) return@launch
                // 全传完才送 WS。成功路径不动状态 —— message_saved 回执来认领
                if (!AppGraph.socket.send(imageFrame(blk.atts, conv))) {
                    mutateImage(blockId) { b -> b.copy(state = ImageState.Failed, failReason = str(R.string.chat_notConnected)) }
                    return@launch
                }
                mutateImage(blockId) { b -> if (b.state == ImageState.Uploading) b.copy(state = ImageState.AwaitingReceipt) else b }
                // 批次 016：纯图消息也会有回复 —— 不开占位的话随后的 delta / reply 会被静默丢掉
                sealPlaceholder(store(conv))
                beginAssistantTurn(conv, str(R.string.chat_imageMsgPreview), ChatTimeRules.nowIso())
                armVisionTimeout(conv, blk.atts)
                // 回执兜底：message_saved 可能丢（连接刚好断）。不兜的话这条会永远转
                delay(RECEIPT_TIMEOUT_MS)
                val after = imageBlock(blockId) ?: return@launch
                if (after.state != ImageState.AwaitingReceipt || after.serverId != null) return@launch
                mutateImage(blockId) { b -> b.copy(state = ImageState.Failed, failReason = str(R.string.chat_uploadNoReceipt)) }
            } catch (e: CancellationException) {
                throw e
            } finally {
                // 只清自己那一代的槽，别把接手的新任务掐了
                if (uploadGen[blockId] == gen) uploads.remove(blockId)
            }
        }
    }

    /** 进度：按整数百分比节流（放最前面，O(1)）；确认这一张仍是当前在传的那张。 */
    private fun noteProgress(blockId: String, gen: Int, idx: Int, frac: Float, doneBytes: Int, cur: Int) {
        if (uploadGen[blockId] != gen) return
        val f = frac.coerceIn(0f, 1f)
        val pct = (f * 100).toInt()
        if (lastPct[blockId] == pct) return
        var applied = false
        mutateImage(blockId) { b ->
            if (b.state != ImageState.Uploading || b.atts.size != idx) b
            else { applied = true; b.copy(currentFrac = f, sentBytes = doneBytes + cur) }
        }
        if (applied) lastPct[blockId] = pct
    }

    /** 上传失败 → 给人看的那一句（iOS: ChatViewModel.swift:1011 + NetworkError.message）。 */
    fun uploadFailText(err: UploadError?, bytes: Int): String = when (err) {
        null, UploadError.Offline -> str(R.string.chat_uploadFailed)
        UploadError.FileTooLarge -> "这张图 ${"%.1f".format(bytes / 1024.0 / 1024.0)} MB，超过单张 ${Uploads.LIMIT_MB} MB 上限。"
        UploadError.Unauthorized -> "登录已经失效了"
        UploadError.SessionInvalid -> "登录已经失效了（多半是在别的设备上改过密码），重新登录一次。"
        UploadError.BodyTooLarge -> "整个请求太大了，这多半是这台设备上出了别的问题。"
        is UploadError.StorageFull -> StorageFmt.storageFullMessage(err.used, err.limit, err.incoming)
        UploadError.Server -> "服务器没能处理这个请求。"
    }

    /** 「取消上传」：掐掉任务、撤掉这条，把本地那几张图原样交还（放回待发条）。 */
    fun cancelImageUpload(blockId: String): List<ByteArray> {
        uploads.remove(blockId)?.cancel()
        uploadGen.remove(blockId); lastPct.remove(blockId)
        for ((conv, s) in stores) {
            val i = s.blocks.indexOfFirst { it is ChatBlock.Image && it.id == blockId }
            if (i < 0) continue
            val back = (s.blocks[i] as ChatBlock.Image).data
            dropBlocks(i, s)
            refresh(conv)
            return back
        }
        return emptyList()
    }

    /** 掐掉某个会话里所有在途的上传（清空 / 新话题 / 别的端清空都要先调）。 */
    private fun cancelUploads(s: ConvStore) {
        for (b in s.blocks) if (b is ChatBlock.Image) {
            uploads.remove(b.id)?.cancel(); uploadGen.remove(b.id); lastPct.remove(b.id)
        }
    }

    /** 图片消息「重新发送」：从断的那张续传。只有失败态能重传。tierOnce 非空时改这一次的档位（「用清晰重发」）。 */
    fun retryImages(blockId: String, tierOnce: ImageTier? = null) {
        val b = imageBlock(blockId) ?: return
        if (b.state != ImageState.Failed) return
        val conv = _activeConv.value
        mutateImage(blockId) { x ->
            x.copy(
                state = ImageState.Uploading, failReason = "", failKind = null,
                sentBytes = x.upSizes.take(x.atts.size).sum(), currentFrac = 0f,
                tierOnce = tierOnce ?: x.tierOnce,
                // 换了档位：缩好的那几份作废，按新档位现缩
                preps = if (tierOnce != null) emptyList() else x.preps,
            )
        }
        runUpload(blockId, conv)
    }

    // MARK: - 认领 id

    /**
     * 认领服务端给我方消息分配的 id：已经认领过（看**所有块**）就别再找；从本轮占位往前找最近一条无 id、未失败的我方文字气泡。
     */
    private fun claimUserMessageId(uid: Long, conv: String) {
        val s = store(conv)
        if (s.blocks.any { it.serverId == uid }) return
        var i = minOf(s.assistantIdx?.minus(1) ?: s.blocks.lastIndex, s.blocks.lastIndex)
        while (i >= 0) {
            val u = s.blocks[i] as? ChatBlock.User
            if (u != null && u.serverId == null && !u.failed) {
                s.blocks[i] = u.copy(serverId = uid)
                refresh(conv)
                return
            }
            i--
        }
    }

    /** 图片消息的落库回执：按 atts 完全相等认领；认到之后释放本地原图。守卫是 != Sent（第 16 秒才到的回执也要认）。 */
    private fun claimImageMessageId(mid: Long, atts: List<String>) {
        if (atts.isEmpty()) return
        for ((conv, s) in stores) {
            val i = s.blocks.indexOfFirst { it is ChatBlock.Image && it.serverId == null && it.state != ImageState.Sent && it.atts == atts }
            if (i < 0) continue
            s.blocks[i] = (s.blocks[i] as ChatBlock.Image).copy(serverId = mid, state = ImageState.Sent, failReason = "", data = emptyList(), preps = emptyList())
            refresh(conv)
            return
        }
    }

    // MARK: - 停止 / 删除 / 重发 / 引用

    /** 停掉这次回复：只发信号，不动本地气泡（收尾由 reply_cancelled 回来替换）。静默计时不停（收尾也可能丢）。 */
    fun cancelReply(): Boolean {
        val conv = _activeConv.value
        if (store(conv).assistantIdx == null) return false
        return AppGraph.socket.send(buildJsonObject { put("type", "chat_cancel"); put("conversation", conv) })
    }

    /** 删一条消息：发 message_delete，本地不先删，等 message_deleted 广播回来再删。返回 false = 没连上。 */
    fun deleteMessage(serverId: Long): Boolean {
        if (serverId <= 0) return false
        return AppGraph.socket.send(buildJsonObject { put("type", "message_delete"); put("id", serverId) })
    }

    /** 只把本地这一个块拿掉（发送失败、服务端上根本没有这条）。 */
    fun dropLocal(blockId: String) {
        for ((conv, s) in stores) {
            val i = s.blocks.indexOfFirst { it.id == blockId }
            if (i < 0) continue
            dropBlocks(i, s); refresh(conv); return
        }
    }

    /** 重新发送一条发失败的消息：先拿掉失败那条，再走一遍完整发送（正文已含【动作名】前缀，不经输入框）。 */
    fun resendFailed(blockId: String) {
        val conv = _activeConv.value
        val s = store(conv)
        val i = s.blocks.indexOfFirst { it.id == blockId }
        val u = s.blocks.getOrNull(i) as? ChatBlock.User ?: return
        if (!u.failed) return
        dropBlocks(i, s)
        refresh(conv)
        deliver(u.text, u.quote)
    }

    /** 错误卡上「再试一次」要重发的那条：卡之前最近的一条我发的文字消息；纯图 / 带图的不重发（回 null 不出钮）。 */
    fun turnToRetry(errorId: String): ChatBlock.User? {
        val s = store(_activeConv.value)
        val i = s.blocks.indexOfFirst { it is ChatBlock.Error && it.id == errorId }
        if (i < 0) return null
        for (b in s.blocks.subList(0, i).asReversed()) {
            if (b is ChatBlock.User) return if (b.atts.isEmpty()) b else null
            if (b is ChatBlock.Image) return null
        }
        return null
    }

    /** 限流 / 模型服务抖动之后「再试一次」：撤掉错误卡，把上一条原样发一遍。 */
    fun retryTurn(errorId: String) {
        val conv = _activeConv.value
        val s = store(conv)
        val u = turnToRetry(errorId) ?: return
        val i = s.blocks.indexOfFirst { it is ChatBlock.Error && it.id == errorId }
        if (i < 0) return
        dropBlocks(i, s)
        refresh(conv)
        deliver(u.text, u.quote)
    }

    /** 移掉第 i 个块，并把受影响的下标整体前移。 */
    private fun dropBlocks(i: Int, s: ConvStore) {
        s.blocks.removeAt(i)
        s.assistantIdx = s.assistantIdx?.let { a -> if (a > i) a - 1 else if (a == i) null else a }
        for ((k, v) in s.taskMap.toMap()) if (v > i) s.taskMap[k] = v - 1
    }

    /** 引用一条消息：挂到输入框上方，发出去随消息带走。 */
    fun quoteMessage(id: Long?, role: String, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        quote.value = ChatQuote.make(id, role, t)
    }

    /** 灵感详情「让 Umbra 去做这件事」：切到主会话，挂芯片、填草稿、亮来源横幅。**不自动发送**。 */
    fun prefillDraft(text: String, chip: SlashAction?, banner: String?) {
        switchConversation(MAIN_CONV)
        chipAction.value = chip
        slashDismissed.value = false
        setDraft(text)
        ideaBanner.value = banner
    }

    /** 任务「重新发起」：切到主会话、原目标填进输入框、不挂芯片，举「待聚焦」的旗。 */
    fun prefillRelaunch(text: String) {
        switchConversation(MAIN_CONV)
        chipAction.value = null
        quote.value = null
        ideaBanner.value = null
        setDraft(text)
        // 原文碰巧以「/」开头时别弹快捷面板 —— 这是要发出去的话，不是在找动作
        slashDismissed.value = text.startsWith("/")
        composerFocusPending.value = true
    }

    /** 清空当前会话：本地立即清 + POST /history/clear（带 client_id，广播回来时认得出是自己清的）。 */
    fun clearActiveHistory() {
        val conv = _activeConv.value
        val s = store(conv)
        quote.value = null
        cancelUploads(s)
        s.blocks.clear(); s.assistantIdx = null; s.taskMap.clear()
        s.oldestId = null; s.hasMoreHistory = false; s.pagedOnce = false; s.loaded = true
        _previews.value = _previews.value - conv
        reflect(conv)
        AppGraph.scope.launch {
            AppGraph.http.call("POST", "/history/clear", buildJsonObject { put("conversation", conv); put("client_id", AppGraph.prefs.clientId) })
        }
    }

    /** 开新话题：**先发后清** —— /new 没出门就什么都不做并返回 false。 */
    fun newSession(): Boolean {
        if (!AppGraph.socket.send(messageFrame("/new", MAIN_CONV, null))) return false
        val s = store(MAIN_CONV)
        quote.value = null
        cancelUploads(s)
        s.blocks.clear(); s.assistantIdx = null; s.taskMap.clear()
        s.oldestId = null; s.hasMoreHistory = true; s.pagedOnce = false
        if (_activeConv.value != MAIN_CONV) switchConversation(MAIN_CONV) else refresh(MAIN_CONV)
        return true
    }

    fun toggleTrace(blockId: String) {
        val conv = _activeConv.value
        val s = store(conv)
        val i = s.blocks.indexOfFirst { it.id == blockId }
        val a = s.blocks.getOrNull(i) as? ChatBlock.Assistant ?: return
        s.blocks[i] = a.copy(traceOpen = !a.traceOpen)
        refresh(conv)
    }

    // MARK: - 授权卡

    private fun confirmFrame(confirmId: String, approved: Boolean) = buildJsonObject {
        put("type", "confirm_response"); put("confirm_id", confirmId); put("approved", approved)
    }

    /** 批准 / 拒绝。**发出去了才改卡**（umbra-ios#30）；返回 false = 没连上。 */
    fun handleConfirm(confirmId: String, approved: Boolean): Boolean {
        if (!AppGraph.socket.send(confirmFrame(confirmId, approved))) return false
        cardSentEpoch["confirm:$confirmId"] = AppGraph.socket.epoch
        resolveConfirm(confirmId, approved)
        return true
    }

    /** 总是允许：先批、批出去了再开自动批准（没连上时开关不该被悄悄翻掉）。 */
    fun handleConfirmAlways(confirmId: String): Boolean {
        if (!handleConfirm(confirmId, true)) return false
        AppGraph.prefs.autoApproveOperate = true
        return true
    }

    /** 满足自动批准就直接批准；发不出去就当没自动批（卡保持可点、不记去重表）。 */
    private fun autoApproveIfEnabled(confirmId: String): Boolean {
        if (!AppGraph.prefs.autoApproveOperate || confirmId in autoApprovedConfirms) return false
        if (!AppGraph.socket.send(confirmFrame(confirmId, true))) return false
        cardSentEpoch["confirm:$confirmId"] = AppGraph.socket.epoch
        autoApprovedConfirms += confirmId
        resolveConfirm(confirmId, true)
        return true
    }

    private fun cardLocal(key: String, handled: Boolean): ChatCardResend.Local {
        if (!handled) return ChatCardResend.Local.Open
        return if (cardSentEpoch[key] == AppGraph.socket.epoch) ChatCardResend.Local.HandledOnThisConnection else ChatCardResend.Local.Handled
    }

    /** 跨所有会话更新某张确认单（任务卡内嵌授权 + 独立确认卡都认 confirm_id）。 */
    private fun resolveConfirm(confirmId: String, approved: Boolean) {
        for ((conv, s) in stores) {
            var changed = false
            for (i in s.blocks.indices) {
                val b = s.blocks[i]
                if (b is ChatBlock.Task && b.confirmId == confirmId) {
                    s.blocks[i] = b.copy(confirmId = null, message = str(if (approved) R.string.chat_status_approved else R.string.chat_status_denied))
                    changed = true
                }
                if (b is ChatBlock.Confirm && b.confirmId == confirmId) {
                    s.blocks[i] = b.copy(resolved = if (approved) ConfirmStatus.Approved else ConfirmStatus.Denied)
                    changed = true
                }
            }
            if (changed) refresh(conv)
        }
    }

    // MARK: - 问答卡（按 cardId 找，不按下标 —— 补拉历史会往前插消息）

    private fun mutateQuestion(cardId: String, body: (ChatBlock.Question) -> ChatBlock.Question) {
        for ((conv, s) in stores) {
            var changed = false
            for (i in s.blocks.indices) {
                val q = s.blocks[i] as? ChatBlock.Question ?: continue
                if (q.cardId != cardId) continue
                s.blocks[i] = body(q); changed = true
            }
            // 改的是卡片自己的作答状态，不是新消息
            if (changed) refresh(conv)
        }
    }

    /** 选一个选项。多选=切换；单选=替换（再点一次可以取消）。 */
    fun pickAnswer(cardId: String, questionId: String, option: String, multi: Boolean) = mutateQuestion(cardId) { q ->
        if (q.done) q else {
            var cur = q.picked[questionId] ?: emptyList()
            cur = if (multi) (if (option in cur) cur - option else cur + option) else (if (option in cur) emptyList() else listOf(option))
            q.copy(picked = q.picked + (questionId to cur))
        }
    }

    fun setCustomAnswer(cardId: String, questionId: String, text: String) = mutateQuestion(cardId) { q ->
        if (q.done) q else q.copy(custom = q.custom + (questionId to text))
    }

    /** 翻题：夹在 0…题数-1，不环绕。 */
    fun moveQuestion(cardId: String, by: Int) = mutateQuestion(cardId) { q ->
        if (q.done) q else q.copy(at = (q.at + by).coerceIn(0, maxOf(0, q.questions.size - 1)))
    }

    /** 提交整张卡。返回 false **只**表示没连上；先发、发出去了才置 done（umbra-ios#30）。 */
    fun submitAnswers(cardId: String): Boolean {
        val q = questionBlock(cardId) ?: return true
        if (q.done || !q.allFilled) return true
        val pairs = mutableListOf<Pair<String, String>>()
        val payload = buildJsonObject {
            for (item in q.questions) {
                val vals = QuestionAnswerRules.values(q.picked[item.id] ?: emptyList(), q.custom[item.id])
                put(item.id, buildJsonArray { vals.forEach { add(JsonPrimitive(it)) } })
                pairs += item.text to (if (vals.isEmpty()) "（跳过）" else vals.joinToString("、"))
            }
        }
        if (!AppGraph.socket.send(buildJsonObject { put("type", "question_answer"); put("card_id", cardId); put("answers", payload) })) return false
        cardSentEpoch["question:$cardId"] = AppGraph.socket.epoch
        mutateQuestion(cardId) { it.copy(done = true, answered = pairs) }
        return true
    }

    private fun questionBlock(cardId: String): ChatBlock.Question? {
        for (s in stores.values) for (b in s.blocks) if (b is ChatBlock.Question && b.cardId == cardId) return b
        return null
    }

    // MARK: - 求助卡（指位 / 纠偏 / 暂停 / 继续：一律发出去了才定型）

    private fun locateFrame(askId: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("type", "operate_locate_response"); put("ask_id", askId); build()
    }

    fun handleLocate(askId: String, nx: Int, ny: Int): Boolean {
        if (!AppGraph.socket.send(locateFrame(askId) { put("nx", nx); put("ny", ny) })) return false
        resolveLocate(askId, LocateStatus.Located); return true
    }

    fun handleLocateFeedback(askId: String, text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (!AppGraph.socket.send(locateFrame(askId) { put("feedback", t) })) return false
        resolveLocate(askId, LocateStatus.FeedbackSent); return true
    }

    fun handleLocatePause(askId: String): Boolean {
        if (!AppGraph.socket.send(locateFrame(askId) { put("paused", true) })) return false
        resolveLocate(askId, LocateStatus.Paused); return true
    }

    /** 「继续」按 run_id 走（一次操控可能求助过好几次）。 */
    fun handleResume(runId: String, askId: String): Boolean {
        if (runId.isEmpty()) return true
        if (!AppGraph.socket.send(buildJsonObject { put("type", "operate_resume"); put("run_id", runId) })) return false
        resolveLocate(askId, LocateStatus.Resumed); return true
    }

    private fun resolveLocate(askId: String, status: LocateStatus) {
        val s = store(MAIN_CONV)
        for (i in s.blocks.indices) {
            val l = s.blocks[i] as? ChatBlock.Locate ?: continue
            if (l.askId != askId) continue
            // paused → resumed 允许再次更新；其它状态定型后不再改
            if (l.resolved == null || (l.resolved == LocateStatus.Paused && status == LocateStatus.Resumed)) s.blocks[i] = l.copy(resolved = status)
        }
        refresh(MAIN_CONV)
    }

    /** 这次 operate 运行跑在哪台设备上；没见过回 null。 */
    fun operateDevice(runId: String): String? = operateRunDevice[runId]

    /** 求助卡「实时接管」要的设备：按运行认；认不出就只剩一台带远程能力的在线设备时用它，否则 null（不瞎猜）。 */
    fun deviceForRun(runId: String?): KnownDevice? {
        val devs = _devices.value
        if (runId != null) operateRunDevice[runId]?.let { id -> devs.firstOrNull { it.device_id == id }?.let { return it } }
        val candidates = devs.filter { it.online && it.remote != null }
        return if (candidates.size == 1) candidates.first() else null
    }

    private fun noteOperateDevice(msg: JsonObject) {
        val conv = msg.str("conversation")
        val fromConv = conv?.takeIf { it.startsWith("device:") }?.removePrefix("device:")
        val dev = msg.str("device_id")?.takeIf { it.isNotEmpty() } ?: fromConv ?: return
        if (dev.isEmpty()) return
        val run = msg.str("run_id")
        if (!run.isNullOrEmpty()) operateRunDevice[run] = dev
        val task = msg.str("task_id")
        if (!task.isNullOrEmpty() && run != null) operateTaskDevice[task] = dev
    }

    // MARK: - 收帧

    private fun userMessageId(m: JsonObject): Long? = m.long("user_message_id")?.takeIf { it > 0 }

    private fun handleMessage(msg: JsonObject) {
        val type = msg.str("type") ?: return
        val streamConv = msg.str("conversation") ?: pendingConv
        // 「重读」失败回来的 error 只属于那颗重读中的气泡，先截走
        if (type == "error") {
            val uid = userMessageId(msg)
            if (uid != null && failVisionRetry(streamConv, uid, msg.str("message"))) return
        }
        // 收到任何一条服务端消息都重置静默计时（ws_ready / rc_* 除外，见 01 文档 §4）
        if (replyTimeout != null && type != "ws_ready" && !type.startsWith("rc_")) armReplyTimeout()
        if (type in setOf("vision_reading", "vision_done", "delta", "tool_call", "tool_result", "node", "reply", "reply_cancelled", "error")) {
            stopVisionTimeout(streamConv)
        }
        when (type) {
            "delta" -> currentAssistant(streamConv)?.let { a ->
                val v = a.vision?.let { if (it.timedOut) it.copy(timedOut = false) else it }
                updateAssistant(a.copy(text = a.text + (msg.str("text") ?: ""), thinking = false, vision = v), streamConv)
            }

            "vision_reading" -> currentAssistant(streamConv)?.let { a ->
                val v = (a.vision ?: VisionState()).copy(reading = true, readingLabel = msg.str("label") ?: "one", n = msg.long("n")?.toInt() ?: 1)
                updateAssistant(a.copy(vision = v), streamConv)
            }

            "vision_done" -> {
                val doneMid = msg.long("message_id")
                val a0 = assistantForVision(streamConv, doneMid) ?: return
                val isRetry = msg.bool("retry") ?: false
                val reason = msg.str("reason") ?: ""
                var v = (a0.vision ?: VisionState()).copy(
                    reading = false, retrying = 0, timedOut = false, messageId = doneMid,
                    label = msg.str("label") ?: "", total = msg.long("total")?.toInt() ?: 0,
                    ok = msg.long("ok")?.toInt() ?: 0, fail = msg.long("fail")?.toInt() ?: 0,
                    failedAtts = msg.arr("failed_atts").mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
                    reason = reason,
                    // 字段缺失（老服务端）才拿原话兜底
                    reasonKind = ChatReasonRules.resolve(if (msg.containsKey("reason_kind")) msg.str("reason_kind") ?: "" else null, reason),
                    topupUrl = msg.str("topup_url"),
                )
                if (v.n == 0) v = v.copy(n = v.total)
                var a = a0
                if (isRetry) {
                    v = v.copy(retried = true)
                    if (msg.bool("answered") == true) {
                        // 服务端紧接着补跑一轮模型：错误卡撤掉、占位回原位
                        a = a.copy(thinking = true, streaming = true)
                        rearmTurn(streamConv, a.id)
                    }
                } else {
                    v = v.copy(secs = msg.dbl("secs") ?: 0.0, wasAllFailed = v.label == "allFailed")
                }
                a = a.copy(vision = v)
                if (v.label == "allFailed" && !isRetry) {
                    a = a.copy(thinking = false, streaming = false)
                    finishTurn(streamConv)
                }
                updateAssistant(a, streamConv, allowFinished = true)
            }

            "tool_call" -> currentAssistant(streamConv)?.let { a0 ->
                var a = a0
                val t = a.text.trim()
                if (t.isNotEmpty()) a = a.copy(trace = a.trace + ("💭 $t"), text = "")
                val args = (msg["args"] as? JsonObject)?.toString()?.take(120) ?: ""
                a = a.copy(trace = a.trace + "🔧 ${msg.str("name") ?: "unknown"}($args)")
                updateAssistant(a, streamConv)
            }

            "tool_result" -> currentAssistant(streamConv)?.let { a ->
                val preview = msg.str("preview") ?: ""
                val truncated = if (preview.length > 160) preview.take(160) + "…" else preview
                updateAssistant(a.copy(trace = a.trace + "↳ ${msg.str("name") ?: "unknown"} → $truncated"), streamConv)
            }

            "reply" -> {
                stopReplyTimeout()
                currentAssistant(streamConv)?.let { a0 ->
                    var v = a0.vision
                    if (v?.reading == true) v = v.copy(reading = false)
                    if (v?.timedOut == true) v = v.copy(timedOut = false)
                    updateAssistant(
                        a0.copy(
                            vision = v, text = msg.str("text") ?: a0.text, thinking = false, streaming = false,
                            serverId = msg.long("message_id"), citations = KbCitations.parse(msg.obj("meta")?.get("citations")),
                        ), streamConv,
                    )
                }
                userMessageId(msg)?.let { claimUserMessageId(it, streamConv) }
                store(streamConv).assistantIdx = null
                setPreview(streamConv, msg.str("text") ?: "", ChatTimeRules.nowIso())
            }

            "reply_cancelled" -> {
                stopReplyTimeout()
                val s = store(streamConv)
                val idx = s.assistantIdx
                val text = msg.str("text") ?: ""
                val metaTools = tools(msg.obj("meta"))
                val blk: ChatBlock = if (msg.str("role") == "assistant") {
                    // 半截那份保留原占位攒下的轨迹（真跑过的）
                    val trace = (idx?.let { s.blocks.getOrNull(it) } as? ChatBlock.Assistant)?.trace ?: emptyList()
                    ChatBlock.Assistant(text = text, trace = trace, ts = ChatTimeRules.nowIso(), serverId = msg.long("id"), interrupted = true, toolsRun = metaTools)
                } else ChatBlock.Note(text = text, serverId = msg.long("id"), toolsRun = metaTools)
                if (idx != null && idx < s.blocks.size && s.blocks[idx] is ChatBlock.Assistant) s.blocks[idx] = blk else s.blocks += blk
                // 必须在清 assistantIdx 之前认领：认领从占位往前找
                userMessageId(msg)?.let { claimUserMessageId(it, streamConv) }
                s.assistantIdx = null
                setPreview(streamConv, text, ChatTimeRules.nowIso())
                reflect(streamConv)
            }

            "message_saved" -> msg.long("id")?.let { mid ->
                if ((msg.str("kind") ?: "text") == "image") {
                    claimImageMessageId(mid, msg.arr("atts").mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content })
                } else claimUserMessageId(mid, msg.str("conversation") ?: MAIN_CONV)
            }

            "message_deleted" -> msg.long("id")?.let { removeBlock(it) }

            // 回收站找回：往正确位置插旧消息的排序逻辑每端各写一遍必然各错各的 —— 重拉那个会话
            "message_restored" -> reloadConv(msg.str("conversation") ?: _activeConv.value)

            "device_presence" -> loadDevices(background = true)

            // 灵感 / 提醒 / 执勤 / 设置这些刷新信号由各自模块从同一条 frames 里挑，这里不转发

            "task_update" -> { noteOperateDevice(msg); handleTaskUpdate(msg) }

            "device_message" -> {
                val conv = msg.str("conversation") ?: MAIN_CONV
                val s = store(conv)
                val ts = msg.str("created_at") ?: ChatTimeRules.nowIso()
                // message_id：umbra#104 起落库成功时带（键名是 message_id，不是 id）
                val sid = ChatHistoryMerge.pushId(msg)
                val text = msg.str("text") ?: ""
                if (sid != null && s.blocks.any { it.serverId == sid }) {
                    // 已经有这一条了（历史里铺过 / 重复广播）
                } else if (msg.str("role") == "device") {
                    s.blocks += ChatBlock.Device(text = text, ts = ts, serverId = sid)
                } else s.blocks += ChatBlock.Assistant(text = text, ts = ts, serverId = sid)
                setPreview(conv, text, ts)
                reflect(conv)
            }

            "confirm_request" -> {
                val confirmId = msg.str("confirm_id") ?: return
                val conv = msg.str("conversation") ?: MAIN_CONV
                val s = store(conv)
                val idx = s.blocks.indexOfFirst { it is ChatBlock.Confirm && it.confirmId == confirmId }
                val local = if (idx < 0) ChatCardResend.Local.Absent
                else cardLocal("confirm:$confirmId", (s.blocks[idx] as ChatBlock.Confirm).resolved != null)
                when (ChatCardResend.merge(local)) {
                    ChatCardResend.Merge.Append -> {
                        s.blocks += ChatBlock.Confirm(confirmId = confirmId, summary = msg.str("summary") ?: str(R.string.chat_status_confirmRequired))
                        reflect(conv)
                    }
                    ChatCardResend.Merge.Reopen -> {
                        s.blocks[idx] = (s.blocks[idx] as ChatBlock.Confirm).copy(resolved = null)
                        // 自动批准的去重表也要撤：那一次同样没到
                        autoApprovedConfirms -= confirmId
                        reflect(conv)
                    }
                    ChatCardResend.Merge.Keep -> Unit
                }
                autoApproveIfEnabled(confirmId)
            }

            "operate_locate_request" -> {
                noteOperateDevice(msg)
                val askId = msg.str("ask_id") ?: return
                val img = msg.str("image_url") ?: return
                val s = store(MAIN_CONV)
                val idx = s.blocks.indexOfFirst { it is ChatBlock.Locate && it.askId == askId }
                val local = if (idx < 0) ChatCardResend.Local.Absent
                else if ((s.blocks[idx] as ChatBlock.Locate).resolved == null) ChatCardResend.Local.Open else ChatCardResend.Local.Handled
                when (ChatCardResend.merge(local)) {
                    ChatCardResend.Merge.Append -> {
                        s.blocks += ChatBlock.Locate(
                            askId = askId, runId = msg.str("run_id") ?: "", imageUrl = img, target = msg.str("target") ?: "",
                            hint = msg.str("hint") ?: str(R.string.operate_locate_hint),
                        )
                        reflect(MAIN_CONV)
                    }
                    ChatCardResend.Merge.Reopen -> {
                        s.blocks[idx] = (s.blocks[idx] as ChatBlock.Locate).copy(resolved = null)
                        reflect(MAIN_CONV)
                    }
                    ChatCardResend.Merge.Keep -> Unit
                }
            }

            "question_card" -> {
                val cardId = msg.str("card_id")?.takeIf { it.isNotEmpty() } ?: return
                val conv = msg.str("conversation") ?: MAIN_CONV
                val s = store(conv)
                val idx = s.blocks.indexOfFirst { it is ChatBlock.Question && it.cardId == cardId }
                val items = msg.arr("questions").mapNotNull { QuestionItem.of(it) }
                val local = if (idx < 0) ChatCardResend.Local.Absent
                else cardLocal("question:$cardId", (s.blocks[idx] as ChatBlock.Question).done)
                when (ChatCardResend.merge(local)) {
                    ChatCardResend.Merge.Append -> if (items.isNotEmpty()) {
                        val title = msg.str("title") ?: ""
                        s.blocks += ChatBlock.Question(cardId = cardId, title = title, questions = items)
                        setPreview(conv, title.ifEmpty { "有几个问题要确认" }, ChatTimeRules.nowIso())
                        reflect(conv)
                    }
                    ChatCardResend.Merge.Reopen -> {
                        // 选过的项、写过的字都留着：连上之后再点一次「提交」就行
                        s.blocks[idx] = (s.blocks[idx] as ChatBlock.Question).copy(done = false, answered = emptyList())
                        reflect(conv)
                    }
                    ChatCardResend.Merge.Keep -> Unit
                }
            }

            "question_resolved" -> msg.str("card_id")?.let { id -> mutateQuestion(id) { it.copy(done = true) } }

            "history_cleared" -> {
                val conv = msg.str("conversation") ?: MAIN_CONV
                val s = store(conv)
                cancelUploads(s)
                s.blocks.clear(); s.assistantIdx = null; s.taskMap.clear()
                s.oldestId = null; s.hasMoreHistory = false; s.loaded = true
                _previews.value = _previews.value - conv
                // 本端自己清的也会收到这条广播：拿 by 跟本端 clientId 比，不是自己清的才留一行说明
                if (msg.str("by") != AppGraph.prefs.clientId) s.blocks += ChatBlock.Note(text = "其它端清空了这段聊天历史")
                reflect(conv)
            }

            "confirm_resolved" -> resolveConfirm(msg.str("confirm_id") ?: "", msg.bool("approved") ?: false)

            "chat_message" -> handleChatMessage(msg)

            "error" -> {
                stopReplyTimeout()
                val s = store(streamConv)
                userMessageId(msg)?.let { claimUserMessageId(it, streamConv) }
                if (s.assistantIdx != null) {
                    currentAssistant(streamConv)?.let { updateAssistant(it.copy(thinking = false, streaming = false), streamConv) }
                    s.assistantIdx = null
                }
                // message 在新服务端已经是人话；类别只决定卡上给什么动作
                val kind = ChatReasonRules.resolve(msg.str("reason_kind"), null)
                s.blocks += ChatBlock.Error(text = msg.str("message") ?: str(R.string.chat_status_error), kind = kind, topup = ChatReasonRules.topupLink(kind, msg.str("topup_url")))
                reflect(streamConv)
            }

            else -> Unit
        }
    }

    /** 其它端发出的消息（跨端同步）。重连补发会重推同一条：按 id 去重。 */
    private fun handleChatMessage(msg: JsonObject) {
        val conv = msg.str("conversation") ?: MAIN_CONV
        val ts = msg.str("created_at") ?: ChatTimeRules.nowIso()
        val s = store(conv)
        val mid = msg.long("id")
        val meta = msg.obj("meta")
        val text = msg.str("text") ?: ""
        val role = msg.str("role")
        if ((msg.str("kind") ?: "text") == "image") {
            val atts = msg.arr("atts").mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.filter { it.isNotEmpty() }
            if (mid != null && s.blocks.any { it is ChatBlock.Image && it.serverId == mid }) return
            // 一张都没有就当没这条：只冒个红点、点进去什么都没有更糟
            if (atts.isEmpty()) return
            s.blocks += ChatBlock.Image(atts = atts, ts = ts, serverId = mid, state = ImageState.Sent)
            setPreview(conv, str(R.string.chat_imageMsgPreview), ts)
            reflect(conv)
            return
        }
        when {
            role == "system" || msg.str("kind") == "system" -> {
                if (mid != null && s.blocks.any { it is ChatBlock.Note && it.serverId == mid }) return
                s.blocks += ChatBlock.Note(text = text, serverId = mid, toolsRun = tools(meta), dutyNotice = meta?.obj("duty_notice"))
            }
            role == "user" -> {
                if (mid != null && s.blocks.any { it is ChatBlock.User && it.serverId == mid }) return
                val atts = msg.arr("atts").mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                s.blocks += ChatBlock.User(text = text, ts = ts, serverId = mid, quote = ChatQuote.of(meta?.get("quote")), atts = atts)
            }
            role == "assistant" -> {
                if (mid != null && s.blocks.any { it is ChatBlock.Assistant && it.serverId == mid }) return
                val resumeId = resumeTaskId(meta?.str("action"), meta?.str("task_id"))
                if (resumeId != null) noteResumeAsk(resumeId)
                s.blocks += ChatBlock.Assistant(
                    text = text, ts = ts, serverId = mid, interrupted = meta?.bool("interrupted") ?: false, toolsRun = tools(meta),
                    resumeTaskId = resumeId, citations = KbCitations.parse(meta?.get("citations")), duty = meta?.obj("duty"),
                )
            }
        }
        setPreview(conv, text, ts)
        reflect(conv)
    }

    /** 按服务端消息 id 移除（扫所有会话：广播不保证带会话，id 全局唯一）。 */
    private fun removeBlock(serverId: Long) {
        if (serverId <= 0) return
        for ((conv, s) in stores) {
            val i = s.blocks.indexOfFirst { it.serverId == serverId }
            if (i < 0) continue
            dropBlocks(i, s); refresh(conv); return
        }
    }

    /** 重拉一个会话（message_restored）。正在等回复 / 正在传图的会话不冲；没拉到什么都不动。 */
    private fun reloadConv(conv: String) {
        val s = stores[conv] ?: return
        fun busy() = s.assistantIdx != null || s.blocks.any { it is ChatBlock.Image && (it.state == ImageState.Uploading || it.state == ImageState.AwaitingReceipt) }
        if (busy()) return
        AppGraph.scope.launch {
            val messages = withContext(BackgroundWork) { fetchHistory(conv) } ?: return@launch
            if (busy()) return@launch
            s.blocks.clear()
            s.blocks.addAll(messages.mapNotNull { historyToBlock(it) })
            // 下标类状态全部作废（任务卡 / 确认卡 / 问答卡不落 messages 表，重拉后会消失 —— 两害相权取其轻，PC 同）
            s.taskMap.clear()
            s.paging = ChatHistoryPaging.afterFirst(messages.map { msgId(it) }, true, s.paging)
            refresh(conv)
        }
    }

    private fun currentAssistant(conv: String): ChatBlock.Assistant? {
        val s = store(conv)
        val idx = s.assistantIdx ?: return null
        return s.blocks.getOrNull(idx) as? ChatBlock.Assistant
    }

    /** 把改过的助手块写回去。allowFinished：完成行上的钮挂在已收工的气泡上（assistantIdx 早清了），按块 id 兜底找。 */
    private fun updateAssistant(a: ChatBlock.Assistant, conv: String, allowFinished: Boolean = false) {
        val s = store(conv)
        val idx = s.assistantIdx
        if (idx != null && idx < s.blocks.size && (s.blocks[idx] as? ChatBlock.Assistant)?.id == a.id) {
            s.blocks[idx] = a; reflect(conv); return
        }
        if (!allowFinished) return
        val i = s.blocks.indexOfFirst { it is ChatBlock.Assistant && it.id == a.id }
        if (i < 0) return
        s.blocks[i] = a
        reflect(conv)
    }

    // MARK: - 任务卡

    @Suppress("UNCHECKED_CAST")
    private fun taskResults(msg: JsonObject): List<Map<String, String>>? {
        val a = msg["results"] as? JsonArray ?: return null
        return a.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            o.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it } }.toMap()
        }
    }

    /** 任务进度卡按 task_id 建：操控落库就是一条单步任务，聊天卡和任务页指同一条。 */
    private fun handleTaskUpdate(msg: JsonObject) {
        val id = msg.str("task_id") ?: return
        val conv = msg.str("conversation") ?: MAIN_CONV
        val s = store(conv)
        val status = msg.str("status")
        val overall = msg.dbl("overall") ?: if (status == "done") 1.0 else 0.0
        val pct = (overall * 100).toInt().coerceIn(0, 100)
        val results = taskResults(msg)
        val idx = s.taskMap[id]
        if (idx != null && idx < s.blocks.size && s.blocks[idx] is ChatBlock.Task) {
            var j = s.blocks[idx] as ChatBlock.Task
            j = j.copy(pct = pct, status = status ?: j.status, message = msg.str("message") ?: j.message, goal = msg.str("goal") ?: j.goal)
            val confirmId = msg.str("confirm_id")
            if (confirmId != null && msg.bool("needs_confirm") == true) {
                // 同 iOS：卡还没写回去时就自动批，批出去了这张卡上就不挂授权钮（卡上那句仍是服务端的 message）
                j = j.copy(confirmId = if (autoApproveIfEnabled(confirmId)) null else confirmId)
            }
            if (results != null) j = j.copy(results = results)
            if (status == "failed") {
                j = j.copy(
                    failAction = ChatReasonRules.taskFailAction(msg.str("reason_kind"), msg.str("topup_url")),
                    failTopupKey = ChatReasonRules.topupButtonKey(ChatReasonRules.resolve(msg.str("reason_kind"), null)),
                )
            }
            if (status != null) {
                val info = if (status == "suspended") TaskSuspendInfo.of(msg) else TaskSuspendInfo()
                if (info.resumeReady && !j.suspend.resumeReady) j = j.copy(resumeOutcome = null, resumeFailText = null)
                j = j.copy(suspend = info)
                // 离开挂起：上一次「接着跑」没成的那一行跟着作废（台账 resume-fail-line-outlives-status）
                if (status != "suspended") j = j.copy(resuming = false, resumeOutcome = null, resumeFailText = null)
            }
            // 又跑起来了：上一次重试的那一行作废
            if (ChatTaskKit.isActive(j.status)) j = j.copy(retryOutcome = null, retrying = false)
            s.blocks[idx] = j
            if (status == "failed" || status == "cancelled") fillExecutor(id)
            if (status == "done") s.blocks += ChatBlock.Done(goal = j.goal, results = j.results ?: emptyList())
        } else {
            var j = ChatBlock.Task(
                taskId = id, goal = msg.str("goal") ?: str(R.string.chat_status_task), pct = pct, status = status ?: "running",
                message = msg.str("message") ?: "",
                confirmId = if (msg.bool("needs_confirm") == true) msg.str("confirm_id") else null,
                results = results,
                failAction = if (status == "failed") ChatReasonRules.taskFailAction(msg.str("reason_kind"), msg.str("topup_url")) else TaskFailAction.AsBefore,
            )
            if (status == "suspended") j = j.copy(suspend = TaskSuspendInfo.of(msg))
            s.taskMap[id] = s.blocks.size
            s.blocks += j
            if (status == "failed" || status == "cancelled") fillExecutor(id)
        }
        // 助手问话下那颗钮跟着同一条推送走
        val ask = _resumeAsks.value[id]
        if (status != null && ask != null) {
            val info = if (status == "suspended") TaskSuspendInfo.of(msg) else TaskSuspendInfo()
            var a = ask
            if (info.resumeReady && !a.info.resumeReady) a = a.copy(outcome = null, failText = null)
            a = a.copy(status = status, info = info)
            if (status != "suspended") a = a.copy(resuming = false, outcome = null, failText = null)
            _resumeAsks.value = _resumeAsks.value + (id to a)
        }
        setPreview(conv, msg.str("message") ?: msg.str("goal") ?: "", ChatTimeRules.nowIso())
        reflect(conv)
    }

    /** 改所有会话里指向这个任务的那张卡。**不标未读**（用户自己按的钮引起的变化）。 */
    private fun updateTaskCard(taskId: String, change: (ChatBlock.Task) -> ChatBlock.Task) {
        for ((conv, s) in stores) {
            val idx = s.taskMap[taskId] ?: continue
            val j = s.blocks.getOrNull(idx) as? ChatBlock.Task ?: continue
            s.blocks[idx] = change(j)
            refresh(conv)
        }
    }

    private fun taskCard(taskId: String): ChatBlock.Task? {
        for (s in stores.values) {
            val idx = s.taskMap[taskId] ?: continue
            (s.blocks.getOrNull(idx) as? ChatBlock.Task)?.let { return it }
        }
        return null
    }

    private suspend fun fetchTaskDetail(id: String): TaskDetail? = AppGraph.http.get<TaskDetail>("/tasks/${UmbraUrl.segment(id)}")

    /** 卡片落到失败 / 已取消时补拉一次详情，只为 executor（电脑操作任务的卡上该是「重新发起」）。 */
    private fun fillExecutor(taskId: String) {
        AppGraph.scope.launch {
            val d = withContext(BackgroundWork) { fetchTaskDetail(taskId) } ?: return@launch
            updateTaskCard(taskId) { it.copy(operate = ChatTaskKit.isOperate(d.task.executor)) }
        }
    }

    /** 重试与接着跑共用的那一次 POST：只取分档要的 status / code / state / detail。 */
    private suspend fun postTaskAction(path: String): TaskActionResponse = when (val r = AppGraph.http.raw("POST", path)) {
        is HttpResult.Ok -> TaskActionResponse(r.status, null, null, "HTTP ${r.status}")
        is HttpResult.Fail -> {
            val o = runCatching { UmbraJson.parseToJsonElement(r.body) as JsonObject }.getOrNull()
            TaskActionResponse(r.status, o?.str("code"), o?.str("state"), o?.str("detail")?.takeIf { it.isNotEmpty() } ?: "HTTP ${r.status}")
        }
        is HttpResult.Offline -> TaskActionResponse(0, null, null, r.message)
        is HttpResult.Blocked -> TaskActionResponse(0, null, null, "未登录")
    }

    /** 卡上「重试任务」：就地调接口、不跳详情。防连点的闸在这里（读的是现值）。null = 被闸掉了。 */
    suspend fun retryTaskCard(taskId: String): TaskRetryOutcome? {
        if (taskCard(taskId)?.retrying == true) return null
        updateTaskCard(taskId) { it.copy(retrying = true, retryOutcome = null) }
        val o = ChatTaskKit.retryOutcome(postTaskAction(ChatTaskKit.retryPath(taskId)))
        updateTaskCard(taskId) { j0 ->
            val j = j0.copy(retrying = false)
            when (o) {
                TaskRetryOutcome.Ok -> j.copy(status = "running", message = "")
                TaskRetryOutcome.Missing -> j.copy(gone = true)
                else -> j.copy(retryOutcome = o, operate = if (o == TaskRetryOutcome.Operate) true else j.operate)
            }
        }
        return o
    }

    private fun resumeTaskId(action: String?, taskId: String?): String? =
        if (action == "resume_task" && !taskId.isNullOrEmpty()) taskId else null

    /** 见到一条「要接着跑吗？」：补拉一次那个任务的详情（状态还不知道才拉；在途去重）。 */
    private fun noteResumeAsk(taskId: String) {
        if (_resumeAsks.value[taskId] == null) _resumeAsks.value = _resumeAsks.value + (taskId to ResumeAsk())
        if (_resumeAsks.value[taskId]?.status?.isEmpty() != true || taskId in resumeAskFetching) return
        resumeAskFetching += taskId
        refreshTaskState(taskId) { resumeAskFetching -= taskId }
    }

    private fun refetchResumeAsks() { for (id in _resumeAsks.value.keys.toList()) noteResumeAsk(id) }

    /** 重拉一个任务的详情，把状态与挂起字段同步到问话那颗钮和任务卡上。 */
    private fun refreshTaskState(taskId: String, done: (() -> Unit)? = null) {
        AppGraph.scope.launch {
            val d = withContext(BackgroundWork) { fetchTaskDetail(taskId) }
            done?.invoke()
            d ?: return@launch
            val info = TaskSuspendInfo.of(d.task)
            _resumeAsks.value[taskId]?.let { _resumeAsks.value = _resumeAsks.value + (taskId to it.copy(status = d.task.status, info = info)) }
            updateTaskCard(taskId) { it.copy(status = d.task.status, suspend = if (d.task.status == "suspended") info else TaskSuspendInfo()) }
        }
    }

    enum class ResumeSource { Card, Ask }

    /** 任务卡 / 助手问话下那颗「接着跑」。卡和问话两颗一起防连点；失败那行写在按下的那颗下面。 */
    suspend fun resumeTask(taskId: String, from: ResumeSource): TaskResumeOutcome? {
        if (taskCard(taskId)?.resuming == true || _resumeAsks.value[taskId]?.resuming == true) return null
        val before = if (from == ResumeSource.Ask && _resumeAsks.value[taskId] != null) _resumeAsks.value[taskId]!!.info
        else taskCard(taskId)?.suspend ?: _resumeAsks.value[taskId]?.info ?: TaskSuspendInfo()
        setResumeState(taskId, from) { it.copy(resuming = true, outcome = null, failText = null) }
        val o = ChatTaskKit.resumeOutcome(postTaskAction(ChatTaskKit.resumePath(taskId)))
        val text = o.message(before)
        setResumeState(taskId, from) { it.copy(resuming = false) }
        when (o) {
            TaskResumeOutcome.Ok -> {
                updateTaskCard(taskId) { it.copy(status = "running", message = "", suspend = TaskSuspendInfo()) }
                _resumeAsks.value[taskId]?.let { _resumeAsks.value = _resumeAsks.value + (taskId to it.copy(status = "running", info = TaskSuspendInfo())) }
            }
            TaskResumeOutcome.Missing -> {
                updateTaskCard(taskId) { it.copy(gone = true) }
                _resumeAsks.value[taskId]?.let { _resumeAsks.value = _resumeAsks.value + (taskId to it.copy(status = "missing")) }
                if (from == ResumeSource.Ask) setResumeState(taskId, ResumeSource.Ask) { it.copy(outcome = o, failText = text) }
            }
            else -> {
                if (o == TaskResumeOutcome.NotReady) {
                    updateTaskCard(taskId) { it.copy(suspend = ChatTaskKit.afterNotReady(it.suspend)) }
                    _resumeAsks.value[taskId]?.let { _resumeAsks.value = _resumeAsks.value + (taskId to it.copy(info = ChatTaskKit.afterNotReady(it.info))) }
                }
                setResumeState(taskId, from) { it.copy(outcome = o, failText = text) }
                if (o.refreshes) refreshTaskState(taskId)
            }
        }
        return o
    }

    private fun setResumeState(taskId: String, from: ResumeSource, change: (ResumeAsk) -> ResumeAsk) {
        when (from) {
            ResumeSource.Ask -> _resumeAsks.value = _resumeAsks.value + (taskId to change(_resumeAsks.value[taskId] ?: ResumeAsk()))
            ResumeSource.Card -> updateTaskCard(taskId) { j ->
                val a = change(ResumeAsk(resuming = j.resuming, outcome = j.resumeOutcome, failText = j.resumeFailText))
                j.copy(resuming = a.resuming, resumeOutcome = a.outcome, resumeFailText = a.failText)
            }
        }
    }

    // MARK: - 长按菜单里的几项动作

    /** 「记为灵感」：直接 POST /inspirations（与 iOS createInspiration 同一份请求体）。 */
    suspend fun saveIdea(text: String): Boolean {
        val r = AppGraph.http.call("POST", "/inspirations", buildJsonObject {
            put("raw", text); put("title", ""); put("summary", "")
            put("tags", JsonArray(emptyList())); put("research", false)
        })
        return r is ApiResult.Ok
    }
}
