package cc.atsx.perftuner

import java.util.Locale

/*
 * Model and editor for TAssistent's /system/etc/gameperfconfig.xml.
 *
 * Only the "default" game policy is edited: it applies to every game-mode app the vendor did
 * not tune individually. Within it, each mode has temperature stages; a stage holds one
 * "low_high" pair per cluster:
 *   - CPU (Gold / Prime): indices into the ascending frequency table in <PreEnv>.
 *   - GPU: KGSL power levels passed through unchanged; 0 is the fastest level.
 *   - -1 on either side means TAssistent does not lock that side at all.
 * Stage temperatures are the skin sensor (quiet-therm) in °C.
 *
 * Parsing records the character span of every editable value so rendering replaces only those
 * digits and keeps the rest of the vendor file byte-identical.
 */

class PolicyException(message: String) : Exception(message)

enum class Mode(val id: Int, val title: String, val summary: String) {
    POWER_SAVE(4, "节能", "省电优先，适合轻度游戏"),
    BALANCE(1, "均衡", "游戏助手的默认档位"),
    SAVAGE(2, "野兽", "性能优先，发热和耗电最高");

    companion object {
        fun byId(id: Int): Mode? = entries.firstOrNull { it.id == id }
    }
}

enum class Cluster(val key: String, val title: String, val detail: String) {
    GOLD("Gold", "中核", "6 核"),
    PRIME("Prime", "大核", "2 核"),
    GPU("Gpu", "GPU", "");

    companion object {
        fun byKey(key: String): Cluster? = entries.firstOrNull { it.key == key }
    }
}

/** A "low_high" pair exactly as stored in the file. */
data class Bound(val low: Int, val high: Int) {
    override fun toString() = "${low}_$high"
}

data class Stage(val level: Int, val temp: Float, val bounds: Map<Cluster, Bound>)

data class GameEntry(val name: String, val pkg: String)

/** Frequency tables from <PreEnv>: CPU in kHz (ascending), GPU in Hz (fastest first). */
class FreqTables(private val gold: List<Long>, private val prime: List<Long>, private val gpu: List<Long>) {
    fun of(c: Cluster): List<Long> = when (c) {
        Cluster.GOLD -> gold
        Cluster.PRIME -> prime
        Cluster.GPU -> gpu
    }

    /** Slider steps from slowest to fastest, in Hz. */
    fun steps(c: Cluster): List<Long> = when (c) {
        Cluster.GPU -> gpu.reversed()
        else -> of(c).map { it * 1000 }
    }

    /** Slider positions (0 = slowest) for a stored bound; -1 maps to the matching extreme. */
    fun positions(c: Cluster, b: Bound): Pair<Int, Int> {
        val top = of(c).size - 1
        val lo = when {
            b.low < 0 -> 0
            c == Cluster.GPU -> top - b.low
            else -> b.low
        }.coerceIn(0, top)
        val hi = when {
            b.high < 0 -> top
            c == Cluster.GPU -> top - b.high
            else -> b.high
        }.coerceIn(0, top)
        return lo to hi
    }

    /** Inverse of [positions]: a slider at either extreme means "not locked" (-1). */
    fun bound(c: Cluster, lo: Int, hi: Int): Bound {
        val top = of(c).size - 1
        val low = when {
            lo <= 0 -> -1
            c == Cluster.GPU -> top - lo
            else -> lo
        }
        val high = when {
            hi >= top -> -1
            c == Cluster.GPU -> top - hi
            else -> hi
        }
        return Bound(low, high)
    }

    /** True when two bounds produce the same effective limits (e.g. GPU level 18 vs -1). */
    fun sameEffect(c: Cluster, a: Bound, b: Bound): Boolean = positions(c, a) == positions(c, b)

    fun format(c: Cluster, hz: Long, unit: Boolean = true): String = if (c == Cluster.GPU) {
        "${hz / 1_000_000}" + if (unit) " MHz" else ""
    } else {
        String.format(Locale.ROOT, "%.2f", hz / 1e9) + if (unit) " GHz" else ""
    }

    fun describe(c: Cluster, b: Bound): String {
        val s = steps(c)
        val (lo, hi) = positions(c, b)
        return if (b.high < 0) {
            "${format(c, s[lo])} – 不限"
        } else {
            "${format(c, s[minOf(lo, hi)], unit = false)} – ${format(c, s[maxOf(lo, hi)])}"
        }
    }
}

class PolicyFile private constructor(
    val text: String,
    val version: Long,
    private val versionSpan: IntRange,
    val tables: FreqTables,
    val modes: Map<Mode, List<Stage>>,
    private val spans: Map<Mode, List<StageSpans>>,
    val dedicatedGames: List<GameEntry>,
) {
    private class StageSpans(val temp: IntRange, val items: Map<Cluster, IntRange>)

    /**
     * Returns the file text with [edited] stages and [newVersion] applied. Only values that
     * differ from this file are rewritten; stage and cluster sets must match this file.
     */
    fun render(edited: Map<Mode, List<Stage>>, newVersion: Long): String {
        val edits = ArrayList<Pair<IntRange, String>>()
        if (newVersion != version) edits += versionSpan to newVersion.toString()
        for ((mode, stages) in edited) {
            val orig = modes[mode] ?: throw PolicyException("配置中没有「${mode.title}」档")
            val sp = spans.getValue(mode)
            if (stages.size != orig.size) throw PolicyException("「${mode.title}」档的温度段数量不一致")
            stages.forEachIndexed { i, s ->
                if (s.temp != orig[i].temp) edits += sp[i].temp to formatTemp(s.temp)
                for ((c, b) in s.bounds) {
                    if (b == orig[i].bounds[c]) continue
                    val r = sp[i].items[c] ?: throw PolicyException("「${mode.title}」档缺少${c.title}设置")
                    edits += r to b.toString()
                }
            }
        }
        val sb = StringBuilder(text)
        edits.sortedByDescending { it.first.first }.forEach { (r, v) -> sb.replace(r.first, r.last + 1, v) }
        return sb.toString()
    }

    companion object {
        private val ATTR = Regex("""([\w-]+)\s*=\s*"([^"]*)"""")
        private val VERSION = Regex("""<GameOptPolicy\b[^>]*?\bversion\s*=\s*"(\d+)"""")
        private val CLUSTER = Regex("""<cluster\b([^>]*)>([^<]*)</cluster>""")
        private val GAME = Regex("""<Game\b([^>]*)>""")
        private val MODE = Regex("""<Mode\b([^>]*)>""")
        private val TEMP_LEVEL = Regex("""<TempLevel\b([^>]*)>""")
        private val TEMP_ATTR = Regex("""\btemp\s*=\s*"([^"]+)"""")
        private val ITEM = Regex("""<item\b([^>]*)>([^<]*)</item>""")
        private val PAIR = Regex("""^(-?\d+)_(-?\d+)$""")

        private fun attrs(s: String) = ATTR.findAll(s).associate { it.groupValues[1] to it.groupValues[2] }

        fun formatTemp(t: Float): String = if (t == t.toInt().toFloat()) t.toInt().toString() else t.toString()

        fun parse(text: String): PolicyFile {
            val vm = VERSION.find(text) ?: throw PolicyException("找不到配置版本号")
            val version = vm.groupValues[1].toLong()

            val tableMap = HashMap<String, List<Long>>()
            for (m in CLUSTER.findAll(text)) {
                val name = attrs(m.groupValues[1])["name"] ?: continue
                if (name in tableMap) continue
                val values = m.groupValues[2].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                tableMap[name] = values.map { it.toLongOrNull() ?: throw PolicyException("频率表 $name 格式错误") }
            }
            val tables = FreqTables(
                table(tableMap, "Gold"), table(tableMap, "Prime"), table(tableMap, "Gpu"),
            )

            var blockStart = -1
            var blockEnd = -1
            val dedicated = ArrayList<GameEntry>()
            for (m in GAME.findAll(text)) {
                val a = attrs(m.groupValues[1])
                val pkg = a["pkg"] ?: continue // <BaseInfo> entries carry no pkg
                if (pkg == "default") {
                    if (blockStart < 0) {
                        blockStart = m.range.last + 1
                        blockEnd = text.indexOf("</Game>", blockStart)
                    }
                } else {
                    dedicated += GameEntry(a["name"] ?: pkg, pkg)
                }
            }
            if (blockStart < 0 || blockEnd < 0) throw PolicyException("找不到默认策略")

            val modes = LinkedHashMap<Mode, List<Stage>>()
            val spans = HashMap<Mode, List<StageSpans>>()
            for (mm in MODE.findAll(text, blockStart)) {
                if (mm.range.first >= blockEnd) break
                val mode = attrs(mm.groupValues[1])["id"]?.toIntOrNull()?.let(Mode::byId) ?: continue
                if (mode in modes) continue
                val modeEnd = text.indexOf("</Mode>", mm.range.last).takeIf { it in 0 until blockEnd }
                    ?: throw PolicyException("「${mode.title}」档结构不完整")
                val stages = ArrayList<Pair<Stage, StageSpans>>()
                for (sm in TEMP_LEVEL.findAll(text, mm.range.last)) {
                    if (sm.range.first >= modeEnd) break
                    val attrGroup = sm.groups[1]!!
                    val level = attrs(attrGroup.value)["level"]?.toIntOrNull()
                        ?: throw PolicyException("「${mode.title}」档温度段缺少编号")
                    val tm = TEMP_ATTR.find(attrGroup.value)
                        ?: throw PolicyException("「${mode.title}」档温度段缺少温度")
                    val temp = tm.groupValues[1].toFloatOrNull()
                        ?: throw PolicyException("「${mode.title}」档温度格式错误")
                    val tr = tm.groups[1]!!.range
                    val tempSpan = (attrGroup.range.first + tr.first)..(attrGroup.range.first + tr.last)
                    val stageEnd = text.indexOf("</TempLevel>", sm.range.last).takeIf { it in 0 until modeEnd }
                        ?: throw PolicyException("「${mode.title}」档温度段结构不完整")
                    val bounds = LinkedHashMap<Cluster, Bound>()
                    val itemSpans = HashMap<Cluster, IntRange>()
                    for (im in ITEM.findAll(text, sm.range.last)) {
                        if (im.range.first >= stageEnd) break
                        val a = attrs(im.groupValues[1])
                        if (a["type"] != "freq") continue
                        val c = Cluster.byKey(a["name"] ?: continue) ?: continue
                        val p = PAIR.matchEntire(im.groupValues[2])
                            ?: throw PolicyException("「${mode.title}」档${c.title}格式错误：${im.groupValues[2]}")
                        bounds[c] = Bound(p.groupValues[1].toInt(), p.groupValues[2].toInt())
                        itemSpans[c] = im.groups[2]!!.range
                    }
                    stages += Stage(level, temp, bounds) to StageSpans(tempSpan, itemSpans)
                }
                if (stages.isEmpty()) throw PolicyException("「${mode.title}」档没有温度段")
                stages.sortBy { it.first.level }
                modes[mode] = stages.map { it.first }
                spans[mode] = stages.map { it.second }
            }
            for (m in Mode.entries) if (m !in modes) throw PolicyException("默认策略中缺少「${m.title}」档")

            return PolicyFile(text, version, vm.groups[1]!!.range, tables, modes, spans, dedicated)
        }

        private fun table(map: Map<String, List<Long>>, name: String): List<Long> =
            map[name]?.takeIf { it.size >= 2 } ?: throw PolicyException("找不到 $name 频率表")
    }
}

/** Human title for stage [i] of [stages], e.g. "低于 42℃", "42 – 45℃", "47℃ 以上". */
fun stageTitle(stages: List<Stage>, i: Int): String {
    fun t(v: Float) = PolicyFile.formatTemp(v)
    return when {
        stages.size == 1 -> "所有温度"
        i == 0 -> "低于 ${t(stages[1].temp)}℃"
        i == stages.lastIndex -> "${t(stages[i].temp)}℃ 以上"
        else -> "${t(stages[i].temp)} – ${t(stages[i + 1].temp)}℃"
    }
}

/** True when both sides are locked and the floor is faster than the cap. */
fun inverted(c: Cluster, b: Bound): Boolean {
    if (b.low < 0 || b.high < 0) return false
    return if (c == Cluster.GPU) b.low < b.high else b.low > b.high
}

/** Returns a user-facing problem with [stages], or null when they can be saved. */
fun validate(mode: Mode, stages: List<Stage>): String? {
    for (i in 1 until stages.size) {
        if (stages[i].temp <= stages[i - 1].temp) return "「${mode.title}」档的温度分段必须逐级升高"
    }
    for (s in stages) for ((c, b) in s.bounds) {
        if (inverted(c, b)) return "「${mode.title}」档${c.title}的下限高于上限"
    }
    return null
}

fun sameStages(t: FreqTables, a: List<Stage>?, b: List<Stage>?): Boolean {
    if (a == null || b == null) return a == b
    if (a.size != b.size) return false
    return a.indices.all { i ->
        a[i].temp == b[i].temp && a[i].bounds.keys == b[i].bounds.keys &&
            a[i].bounds.all { (c, x) -> t.sameEffect(c, x, b[i].bounds.getValue(c)) }
    }
}

/** One line per effective difference between [from] and [to]. */
fun describeChanges(t: FreqTables, from: Map<Mode, List<Stage>>, to: Map<Mode, List<Stage>>): List<String> {
    val out = ArrayList<String>()
    for (m in Mode.entries) {
        val a = from[m] ?: continue
        val b = to[m] ?: continue
        b.forEachIndexed { i, s ->
            val o = a.getOrNull(i) ?: return@forEachIndexed
            if (s.temp != o.temp) {
                out += "${m.title} · 第 ${i + 1} 段起始温度：${PolicyFile.formatTemp(o.temp)}℃ → ${PolicyFile.formatTemp(s.temp)}℃"
            }
            for (c in Cluster.entries) {
                val x = o.bounds[c] ?: continue
                val y = s.bounds[c] ?: continue
                if (!t.sameEffect(c, x, y)) {
                    out += "${m.title} · ${stageTitle(a, i)} · ${c.title}：${t.describe(c, x)} → ${t.describe(c, y)}"
                }
            }
        }
    }
    return out
}
