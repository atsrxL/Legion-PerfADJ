package cc.atsx.perftuner

import android.app.Application
import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Two editors write the same policy file:
 *  - 默认模式 ([simpleDraft]): profiles + protection, kept in this app's preferences and turned
 *    into stages by [Simple.derive] when applied.
 *  - 精细模式 ([draft]): the file's stages edited directly.
 * [origin] tells which of them (or the vendor file) is currently live.
 */
class Store(app: Application) : AndroidViewModel(app) {
    sealed interface Phase {
        data object Loading : Phase
        data class Failed(val title: String, val detail: String, val canRestore: Boolean = false) : Phase
        data object Ready : Phase
    }

    enum class Origin(val title: String) { FACTORY("原厂"), SIMPLE("默认模式"), FINE("精细模式") }

    data class Live(val skinTemp: Float? = null, val gameMode: Int? = null, val inGame: Boolean = false)

    var phase by mutableStateOf<Phase>(Phase.Loading)
        private set
    var factory by mutableStateOf<PolicyFile?>(null)
        private set
    var effective by mutableStateOf<PolicyFile?>(null)
        private set

    /** True when TAssistent's copy differs from the system file. */
    var overridden by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var live by mutableStateOf(Live())
        private set

    /** Saved 默认模式 settings and the edited copy. */
    var simple by mutableStateOf<SimpleConfig?>(null)
        private set
    var simpleDraft by mutableStateOf<SimpleConfig?>(null)
        private set

    /** 精细模式: edited stages; compared with [effective] to find unsaved changes. */
    val draft = mutableStateMapOf<Mode, List<Stage>>()

    private val prefs = app.getSharedPreferences("perftuner", Context.MODE_PRIVATE)

    /** One-shot user messages (snackbar). */
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    private fun notify(text: String) {
        messageChannel.trySend(text)
    }

    init {
        load()
    }

    private class Loaded(val factory: PolicyFile, val effective: PolicyFile, val overridden: Boolean, val simple: SimpleConfig)

    fun load() {
        viewModelScope.launch {
            phase = Phase.Loading
            when (val result = withContext(Dispatchers.IO) { loadFiles() }) {
                is Loaded -> {
                    // Unsaved 默认模式 edits survive a reload (e.g. after saving 精细模式).
                    val keepDraft = simpleDraft.takeIf { simpleDirty }
                    factory = result.factory
                    effective = result.effective
                    overridden = result.overridden
                    simple = result.simple
                    simpleDraft = keepDraft ?: result.simple
                    draft.clear()
                    draft.putAll(result.effective.modes)
                    phase = Phase.Ready
                }
                is Phase.Failed -> phase = result
            }
        }
    }

    private fun loadFiles(): Any {
        if (!Root.available()) {
            return Phase.Failed(
                "需要 root 权限",
                "请打开 KernelSU 管理器 →「超级用户」，为「档位调校」开启授权，然后点「重试」。",
            )
        }
        val systemText = Root.readSystem() ?: return Phase.Failed(
            "找不到游戏助手策略",
            "这台设备上没有 ${Root.SYSTEM}。本工具只适用于带游戏助手的联想拯救者平板。",
        )
        val fac = try {
            PolicyFile.parse(systemText)
        } catch (e: PolicyException) {
            return Phase.Failed("无法识别系统自带策略", e.message.orEmpty())
        }
        val cfg = Simple.decode(prefs.getString(PREF_SIMPLE, null), fac) ?: Simple.defaults(fac)
        return when (val data = Root.readData()) {
            is Root.Read.Failed -> Phase.Failed("读取当前策略失败", data.detail)
            Root.Read.Missing -> Loaded(fac, fac, false, cfg)
            is Root.Read.Found -> try {
                Loaded(fac, PolicyFile.parse(data.text), data.text != systemText, cfg)
            } catch (e: PolicyException) {
                Phase.Failed("当前策略文件无法识别", "${e.message}\n可以先恢复原厂配置再使用。", canRestore = true)
            }
        }
    }

    suspend fun refreshLive() {
        val (temp, scene) = withContext(Dispatchers.IO) { Root.live() }
        val mode = runCatching {
            Settings.Global.getInt(getApplication<Application>().contentResolver, "game_helper_game_mode")
        }.getOrNull()
        live = Live(temp, mode, scene != null && scene != 0)
    }

    val origin: Origin
        get() {
            val eff = effective ?: return Origin.FACTORY
            val fac = factory ?: return Origin.FACTORY
            if (!overridden) return Origin.FACTORY
            val cfg = simple ?: return Origin.FINE
            val derived = runCatching { Simple.derive(cfg, fac, eff.modes, eff.tables) }.getOrNull() ?: return Origin.FINE
            return if (Mode.entries.all { sameStages(eff.tables, eff.modes[it], derived[it]) }) Origin.SIMPLE else Origin.FINE
        }

    // ---- 默认模式 ----

    val simpleDirty: Boolean
        get() {
            val t = effective?.tables ?: return false
            val a = simple ?: return false
            val b = simpleDraft ?: return false
            return !Simple.same(t, a, b)
        }

    fun simpleChanges(): List<String> {
        val t = effective?.tables ?: return emptyList()
        val fac = factory ?: return emptyList()
        val a = simple ?: return emptyList()
        val b = simpleDraft ?: return emptyList()
        return Simple.describeChanges(t, fac, a, b)
    }

    private fun editSimple(f: (SimpleConfig) -> SimpleConfig) {
        simpleDraft = simpleDraft?.let(f)
    }

    private fun editMode(mode: Mode, f: (ModeSetup) -> ModeSetup) =
        editSimple { cfg -> cfg.copy(modes = cfg.modes + (mode to f(cfg.modes.getValue(mode)))) }

    private fun editCustom(mode: Mode, slot: Int, f: (Profile) -> Profile) {
        if (slot !in 1 until Simple.SLOTS) return
        editMode(mode) { s -> s.copy(custom = s.custom.toMutableList().also { it[slot - 1] = f(it[slot - 1]) }) }
    }

    /** Keeps the saved spelling of a bound when the slider lands on the same effect. */
    private fun keep(c: Cluster, b: Bound, saved: Bound?): Bound {
        val t = effective?.tables ?: return b
        return if (saved != null && t.sameEffect(c, saved, b)) saved else b
    }

    fun setTrigger(value: Int) {
        if (value in Simple.MIN_TRIGGER..Simple.MAX_TRIGGER) editSimple { it.copy(trigger = value) }
    }

    fun setProtection(c: Cluster, lo: Int, hi: Int) {
        val t = effective?.tables ?: return
        val b = keep(c, t.bound(c, lo, hi), simple?.protection?.get(c))
        editSimple { it.copy(protection = it.protection + (c to b)) }
    }

    fun resetProtection() {
        val fac = factory ?: return
        editSimple { it.copy(protection = Simple.defaultProtection(fac)) }
    }

    fun setActive(mode: Mode, slot: Int) {
        if (slot in 0 until Simple.SLOTS) editMode(mode) { it.copy(active = slot) }
    }

    fun renameProfile(mode: Mode, slot: Int, name: String) {
        editCustom(mode, slot) { it.copy(name = name.replace("\n", "").take(Simple.MAX_NAME)) }
    }

    fun setProfileBound(mode: Mode, slot: Int, c: Cluster, lo: Int, hi: Int) {
        val t = effective?.tables ?: return
        val saved = simple?.modes?.get(mode)?.custom?.getOrNull(slot - 1)?.limits?.get(c)
        val b = keep(c, t.bound(c, lo, hi), saved)
        editCustom(mode, slot) { it.copy(limits = it.limits + (c to b)) }
    }

    fun resetProfile(mode: Mode, slot: Int) {
        val fac = factory ?: return
        editCustom(mode, slot) { it.copy(limits = Simple.factoryLimits(fac, mode)) }
    }

    fun discardSimple() {
        simpleDraft = simple
    }

    fun applySimple() {
        val eff = effective ?: return
        val fac = factory ?: return
        val cfg = simpleDraft ?: return
        Simple.validate(cfg)?.let { notify(it); return }
        val stages = try {
            Simple.derive(cfg, fac, eff.modes, eff.tables)
        } catch (e: PolicyException) {
            notify(e.message.orEmpty())
            return
        }
        for (m in Mode.entries) validate(m, stages.getValue(m))?.let { notify(it); return }
        commit(stages) {
            if (!prefs.edit().putString(PREF_SIMPLE, Simple.encode(cfg)).commit()) error("配置已写入，但保存应用设置失败")
        }
    }

    // ---- 精细模式 ----

    val fineDirty: Boolean
        get() {
            val eff = effective ?: return false
            return Mode.entries.any { !sameStages(eff.tables, eff.modes[it], draft[it]) }
        }

    fun fineChanges(): List<String> {
        val eff = effective ?: return emptyList()
        return describeChanges(eff.tables, eff.modes, draft.toMap())
    }

    /** Factory stages for [mode], or null when the stage layout differs from the current file. */
    fun factoryStages(mode: Mode): List<Stage>? {
        val f = factory?.modes?.get(mode) ?: return null
        return f.takeIf { it.size == draft[mode]?.size }
    }

    fun differsFromFactory(mode: Mode): Boolean {
        val eff = effective ?: return false
        val f = factoryStages(mode) ?: return false
        return !sameStages(eff.tables, f, draft[mode])
    }

    fun setBound(mode: Mode, index: Int, cluster: Cluster, lo: Int, hi: Int) {
        val eff = effective ?: return
        val stages = draft[mode] ?: return
        var b = eff.tables.bound(cluster, lo, hi)
        // Keep the file's original spelling when the effect is unchanged (e.g. GPU 18 vs -1).
        val orig = eff.modes[mode]?.getOrNull(index)?.bounds?.get(cluster)
        if (orig != null && eff.tables.sameEffect(cluster, orig, b)) b = orig
        if (stages[index].bounds[cluster] == b) return
        draft[mode] = stages.toMutableList().also { it[index] = it[index].copy(bounds = it[index].bounds + (cluster to b)) }
    }

    /** Allowed start temperatures for stage [index] (stage 0 always starts at the bottom). */
    fun tempRange(mode: Mode, index: Int): IntRange? {
        val s = draft[mode] ?: return null
        if (index <= 0 || index > s.lastIndex) return null
        val lo = maxOf(MIN_TEMP, s[index - 1].temp.toInt() + 1)
        val hi = if (index < s.lastIndex) s[index + 1].temp.toInt() - 1 else MAX_TEMP
        return lo..hi
    }

    fun setTemp(mode: Mode, index: Int, temp: Int) {
        val range = tempRange(mode, index) ?: return
        if (temp !in range) return
        val stages = draft[mode] ?: return
        draft[mode] = stages.toMutableList().also { it[index] = it[index].copy(temp = temp.toFloat()) }
    }

    fun resetMode(mode: Mode) {
        factoryStages(mode)?.let { draft[mode] = it }
    }

    fun discardFine() {
        val eff = effective ?: return
        draft.clear()
        draft.putAll(eff.modes)
    }

    fun applyFine() {
        for (m in Mode.entries) validate(m, draft[m].orEmpty())?.let { notify(it); return }
        commit(draft.toMap()) {}
    }

    // ---- writing ----

    /** Renders [edited] into the current file, installs it, then runs [afterWrite] (on IO). */
    private fun commit(edited: Map<Mode, List<Stage>>, afterWrite: () -> Unit) {
        val eff = effective ?: return
        val fac = factory ?: return
        viewModelScope.launch {
            busy = true
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    if ((Root.thermalScene() ?: 0) != 0) error("游戏模式仍在运行，请先退出游戏再保存")
                    // TAssistent only keeps this copy while its version is not below the system file.
                    val text = eff.render(edited, maxOf(eff.version, fac.version + 1))
                    val check = PolicyFile.parse(text)
                    if (Mode.entries.any { !sameStages(check.tables, check.modes[it], edited[it]) }) {
                        error("生成的配置校验不一致")
                    }
                    writeAndVerify(text)
                    afterWrite()
                }.exceptionOrNull()
            }
            busy = false
            if (error == null) {
                notify("已保存。下次进入游戏时生效")
                load()
            } else {
                Log.w(TAG, "save failed", error)
                notify("保存失败：${error.message}")
            }
        }
    }

    fun restoreFactory() {
        viewModelScope.launch {
            busy = true
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    if ((Root.thermalScene() ?: 0) != 0) error("游戏模式仍在运行，请先退出游戏再恢复")
                    val sys = Root.readSystem() ?: error("读取系统自带策略失败")
                    val r = Root.restoreFactory()
                    if (!r.ok) error(r.detail())
                    if (!r.out.contains("SHA=${sha256(sys.toByteArray())}")) error("恢复后校验不一致")
                }.exceptionOrNull()
            }
            busy = false
            if (error == null) {
                notify("已恢复原厂配置。下次进入游戏时生效")
                load()
            } else {
                Log.w(TAG, "restore failed", error)
                notify("恢复失败：${error.message}")
            }
        }
    }

    private fun writeAndVerify(text: String) {
        val bytes = text.toByteArray()
        val tmp = File(getApplication<Application>().cacheDir, "gameperfconfig.xml")
        try {
            tmp.writeBytes(bytes)
            val r = Root.install(tmp)
            if (!r.ok) error(r.detail())
            if (!r.out.contains("SHA=${sha256(bytes)}")) error("写入后校验不一致")
            if (!r.out.contains("system_app_data_file")) error("文件安全标签异常：${r.out.trim()}")
        } finally {
            tmp.delete()
        }
        val back = Root.readData()
        if (back !is Root.Read.Found || back.text != text) error("读回内容与写入内容不一致")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val TAG = "PerfTuner"
        const val MIN_TEMP = 30
        const val MAX_TEMP = 65
        private const val PREF_SIMPLE = "simple_config"
    }
}
