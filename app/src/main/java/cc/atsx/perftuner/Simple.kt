package cc.atsx.perftuner

import java.net.URLDecoder
import java.net.URLEncoder

/*
 * 默认模式: for each of 节能 / 均衡 / 野兽 one chosen profile applies below the trigger temperature
 * T, and one shared set of protection limits applies from T upward.
 *
 * Each mode has three profiles: slot 0 is "Factory" (the vendor's first temperature stage of that
 * mode, read-only) and slots 1–2 are user-named copies. Settings live in this app; applying them
 * rewrites the mode's existing stages as  0℃ → profile,  T / T+1 / T+2 → protection.
 *
 * Why every stage from the second one on gets the protection limits: TAssistent counts the stage
 * thresholds above 0 and only switches stages when there are 1, 3 or 4 of them (1 never leaves
 * stage 1; 3 never reaches stage 4). With identical limits on stages 2–4, any skin temperature
 * >= T lands on protection whichever stage TAssistent picks; it returns below T−1℃.
 */

typealias Limits = Map<Cluster, Bound>

data class Profile(val name: String, val limits: Limits)

/** [custom] holds slots 1 and 2; [active] is the applied slot (0 = Factory). */
data class ModeSetup(val custom: List<Profile>, val active: Int)

data class SimpleConfig(val trigger: Int, val protection: Limits, val modes: Map<Mode, ModeSetup>)

object Simple {
    const val DEFAULT_TRIGGER = 47
    const val MIN_TRIGGER = 30
    const val MAX_TRIGGER = 60
    const val MAX_NAME = 16
    const val FACTORY_NAME = "Factory"
    const val SLOTS = 3
    private const val FACTORY_PROTECTION_TEMP = 47f
    private const val FORMAT = "1"
    private val PAIR = Regex("""^(-?\d+)_(-?\d+)$""")

    fun factoryLimits(factory: PolicyFile, mode: Mode): Limits = factory.modes.getValue(mode).first().bounds

    /** Savage's "47℃ and above" stage of the vendor file. */
    fun defaultProtection(factory: PolicyFile): Limits {
        val savage = factory.modes.getValue(Mode.SAVAGE)
        return (savage.firstOrNull { it.temp == FACTORY_PROTECTION_TEMP } ?: savage.last()).bounds
    }

    fun defaultCustom(factory: PolicyFile, mode: Mode, slot: Int) = Profile("配置 ${slot + 1}", factoryLimits(factory, mode))

    fun defaults(factory: PolicyFile) = SimpleConfig(
        DEFAULT_TRIGGER,
        defaultProtection(factory),
        Mode.entries.associateWith { m -> ModeSetup(listOf(defaultCustom(factory, m, 1), defaultCustom(factory, m, 2)), 0) },
    )

    fun profileName(cfg: SimpleConfig, mode: Mode, slot: Int): String =
        if (slot == 0) FACTORY_NAME else cfg.modes.getValue(mode).custom[slot - 1].name

    fun profileLimits(cfg: SimpleConfig, factory: PolicyFile, mode: Mode, slot: Int): Limits =
        if (slot == 0) factoryLimits(factory, mode) else cfg.modes.getValue(mode).custom[slot - 1].limits

    fun activeLimits(cfg: SimpleConfig, factory: PolicyFile, mode: Mode): Limits =
        profileLimits(cfg, factory, mode, cfg.modes.getValue(mode).active)

    /** The lower of [protection] and [cap] on both sides, so protection never raises a mode's limits. */
    fun clamp(t: FreqTables, c: Cluster, protection: Bound, cap: Bound): Bound {
        val (pl, ph) = t.positions(c, protection)
        val (cl, ch) = t.positions(c, cap)
        val hi = minOf(ph, ch)
        val lo = minOf(pl, cl, hi)
        return t.bound(c, lo, hi)
    }

    /** Limits that apply to [mode] at or above the trigger temperature. */
    fun protectionFor(cfg: SimpleConfig, factory: PolicyFile, mode: Mode, t: FreqTables): Limits {
        val active = activeLimits(cfg, factory, mode)
        return cfg.protection.mapValues { (c, p) -> active[c]?.let { clamp(t, c, p, it) } ?: p }
    }

    /** Stages to write, keeping the stage layout of [layout] (the current policy file). */
    fun derive(cfg: SimpleConfig, factory: PolicyFile, layout: Map<Mode, List<Stage>>, t: FreqTables): Map<Mode, List<Stage>> =
        Mode.entries.associateWith { m ->
            val base = layout[m] ?: throw PolicyException("配置中没有「${m.title}」档")
            if (base.size !in 4..5) throw PolicyException("「${m.title}」档有 ${base.size} 个温度段，默认模式需要 4 或 5 个")
            val active = activeLimits(cfg, factory, m)
            val prot = protectionFor(cfg, factory, m, t)
            base.mapIndexed { i, s ->
                if (i == 0) {
                    s.copy(temp = 0f, bounds = s.bounds.mapValues { (c, b) -> active[c] ?: b })
                } else {
                    s.copy(temp = (cfg.trigger + i - 1).toFloat(), bounds = s.bounds.mapValues { (c, b) -> prot[c] ?: b })
                }
            }
        }

    fun validate(cfg: SimpleConfig): String? {
        if (cfg.trigger !in MIN_TRIGGER..MAX_TRIGGER) return "触发温度需在 $MIN_TRIGGER–$MAX_TRIGGER℃ 之间"
        for ((c, b) in cfg.protection) if (inverted(c, b)) return "保护温度中${c.title}的下限高于上限"
        for ((m, s) in cfg.modes) {
            s.custom.forEachIndexed { i, p ->
                if (p.name.isBlank()) return "「${m.title}」档第 ${i + 2} 组配置需要名称"
                for ((c, b) in p.limits) if (inverted(c, b)) return "「${m.title}」档「${p.name}」中${c.title}的下限高于上限"
            }
        }
        return null
    }

    fun sameLimits(t: FreqTables, a: Limits, b: Limits): Boolean =
        a.keys == b.keys && a.all { (c, x) -> t.sameEffect(c, x, b.getValue(c)) }

    fun same(t: FreqTables, a: SimpleConfig, b: SimpleConfig): Boolean =
        a.trigger == b.trigger && sameLimits(t, a.protection, b.protection) &&
            Mode.entries.all { m ->
                val x = a.modes.getValue(m)
                val y = b.modes.getValue(m)
                x.active == y.active && x.custom.indices.all { i ->
                    x.custom[i].name == y.custom[i].name && sameLimits(t, x.custom[i].limits, y.custom[i].limits)
                }
            }

    fun describeChanges(t: FreqTables, factory: PolicyFile, from: SimpleConfig, to: SimpleConfig): List<String> {
        val out = ArrayList<String>()
        if (from.trigger != to.trigger) out += "保护温度 · 触发温度：${from.trigger}℃ → ${to.trigger}℃"
        for (c in Cluster.entries) {
            val x = from.protection[c] ?: continue
            val y = to.protection[c] ?: continue
            if (!t.sameEffect(c, x, y)) out += "保护温度 · ${c.title}：${t.describe(c, x)} → ${t.describe(c, y)}"
        }
        for (m in Mode.entries) {
            val x = from.modes.getValue(m)
            val y = to.modes.getValue(m)
            for (i in x.custom.indices) {
                val a = x.custom[i]
                val b = y.custom[i]
                if (a.name != b.name) out += "${m.title} · 第 ${i + 2} 组名称：${a.name} → ${b.name}"
                for (c in Cluster.entries) {
                    val p = a.limits[c] ?: continue
                    val q = b.limits[c] ?: continue
                    if (!t.sameEffect(c, p, q)) out += "${m.title} · ${b.name} · ${c.title}：${t.describe(c, p)} → ${t.describe(c, q)}"
                }
            }
            if (x.active != y.active) {
                out += "${m.title} · 默认配置：${profileName(from, m, x.active)} → ${profileName(to, m, y.active)}"
            }
        }
        return out
    }

    fun encode(cfg: SimpleConfig): String = buildString {
        appendLine("v=$FORMAT")
        appendLine("trigger=${cfg.trigger}")
        for ((c, b) in cfg.protection) appendLine("protection.${c.key}=$b")
        for ((m, s) in cfg.modes) {
            appendLine("mode.${m.id}.active=${s.active}")
            s.custom.forEachIndexed { i, p ->
                val slot = i + 1
                appendLine("mode.${m.id}.$slot.name=${URLEncoder.encode(p.name, "UTF-8")}")
                for ((c, b) in p.limits) appendLine("mode.${m.id}.$slot.${c.key}=$b")
            }
        }
    }

    /** Parses [text]; fields that are missing or invalid fall back to [defaults]. Null for an unknown format. */
    fun decode(text: String?, factory: PolicyFile): SimpleConfig? {
        if (text == null) return null
        val kv = text.lines().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }.toMap()
        if (kv["v"] != FORMAT) return null
        val d = defaults(factory)
        fun bound(key: String): Bound? = kv[key]?.let(PAIR::matchEntire)?.let { Bound(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        fun limits(prefix: String, fallback: Limits): Limits = fallback.mapValues { (c, b) -> bound("$prefix${c.key}") ?: b }
        val trigger = kv["trigger"]?.toIntOrNull()?.takeIf { it in MIN_TRIGGER..MAX_TRIGGER } ?: d.trigger
        val modes = Mode.entries.associateWith { m ->
            val dm = d.modes.getValue(m)
            val custom = dm.custom.mapIndexed { i, dp ->
                val slot = i + 1
                val name = kv["mode.${m.id}.$slot.name"]?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                    ?.takeIf { it.isNotBlank() }?.take(MAX_NAME) ?: dp.name
                Profile(name, limits("mode.${m.id}.$slot.", dp.limits))
            }
            val active = kv["mode.${m.id}.active"]?.toIntOrNull()?.takeIf { it in 0 until SLOTS } ?: dm.active
            ModeSetup(custom, active)
        }
        return SimpleConfig(trigger, limits("protection.", d.protection), modes)
    }
}
