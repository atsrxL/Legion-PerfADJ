@file:OptIn(ExperimentalMaterial3Api::class)

package cc.atsx.perftuner

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(this)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) { App() }
        }
    }
}

private val ProtectionAccent = Color(0xFFE08A1E)
private val Item = Modifier.fillMaxWidth()

private fun Mode.accent(): Color = when (this) {
    Mode.POWER_SAVE -> Color(0xFF2E9D57)
    Mode.BALANCE -> Color(0xFF2F7BE5)
    Mode.SAVAGE -> Color(0xFFE5483B)
}

private fun modeName(id: Int?): String = when (id) {
    4 -> "节能"
    1 -> "均衡"
    2 -> "野兽"
    8 -> "性能"
    16 -> "超神"
    null -> "未知"
    else -> "模式 $id"
}

@Composable
private fun App(store: Store = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(store.phase) {
        if (store.phase == Store.Phase.Ready) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    store.refreshLive()
                    delay(4000)
                }
            }
        }
    }
    LaunchedEffect(store) {
        store.messages.collect { snackbar.showSnackbar(it) }
    }
    var fine by rememberSaveable { mutableStateOf(false) }
    var modeIndex by rememberSaveable { mutableIntStateOf(Mode.SAVAGE.ordinal) }
    var confirm by remember { mutableStateOf<Boolean?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }
    val ready = store.phase == Store.Phase.Ready

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("档位调校") }, actions = {
                if (ready) {
                    Text("精细模式")
                    Checkbox(checked = fine, onCheckedChange = { fine = it }, enabled = !store.busy)
                    TextButton(onClick = { confirmRestore = true }, enabled = !store.busy && store.overridden) {
                        Text("恢复原厂")
                    }
                }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (ready) {
                when {
                    fine -> if (store.fineDirty) {
                        ActionBar("${store.fineChanges().size} 处修改尚未保存", store.busy, store::discardFine, "保存并应用") {
                            confirm = true
                        }
                    }
                    store.simpleDirty -> ActionBar("${store.simpleChanges().size} 处修改尚未保存", store.busy, store::discardSimple, "保存并应用") {
                        confirm = false
                    }
                    store.origin != Store.Origin.SIMPLE -> ActionBar(
                        "默认模式尚未生效，当前为${store.origin.title}配置", store.busy, null, "应用默认模式",
                    ) { confirm = false }
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (val p = store.phase) {
                Store.Phase.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is Store.Phase.Failed -> FailedView(p, store) { confirmRestore = true }
                Store.Phase.Ready -> {
                    val mode = Mode.entries[modeIndex]
                    val onMode = { i: Int -> modeIndex = i }
                    Dashboard(store, mode, onMode, fine)
                }
            }
            if (store.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }

    when (confirm) {
        true -> ApplyDialog(
            store.fineChanges(),
            "保存后精细模式的配置会取代默认模式。提高上限会增加发热和耗电；下次进入游戏时生效，可随时恢复原厂配置。",
            onDismiss = { confirm = null },
        ) {
            confirm = null
            store.applyFine()
        }
        null -> {}
        else -> {
            val lines = store.simpleChanges().toMutableList()
            if (store.origin != Store.Origin.SIMPLE) lines.add(0, "用默认模式取代当前的${store.origin.title}配置")
            ApplyDialog(
                lines,
                "下次进入游戏时生效，可随时恢复原厂配置。",
                onDismiss = { confirm = null },
            ) {
                confirm = null
                store.applySimple()
            }
        }
    }
    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("恢复原厂配置？") },
            text = { Text("游戏助手将改回系统自带的策略，下次进入游戏时生效。本应用里的默认模式设置会保留，可以再次应用。") },
            confirmButton = {
                Button(onClick = { confirmRestore = false; store.restoreFactory() }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun FailedView(p: Store.Phase.Failed, store: Store, onRestore: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(p.title, style = MaterialTheme.typography.headlineSmall)
        Text(
            p.detail,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 520.dp),
        )
        Button(onClick = store::load, enabled = !store.busy) { Text("重试") }
        if (p.canRestore) OutlinedButton(onClick = onRestore, enabled = !store.busy) { Text("恢复原厂配置") }
    }
}

@Composable
private fun Dashboard(store: Store, mode: Mode, onMode: (Int) -> Unit, fine: Boolean) {
    val fac = store.factory ?: return
    val eff = store.effective ?: return
    val cfg = store.simpleDraft ?: return
    val setup = cfg.modes.getValue(mode)
    var slot by rememberSaveable(mode) { mutableIntStateOf(setup.active) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("机身 ${store.live.skinTemp?.let { String.format(Locale.ROOT, "%.1f℃", it) } ?: "—"}  ·  ${if (store.live.inGame) modeName(store.live.gameMode) else "未在游戏中"}  ·  生效：${store.origin.title}",
                style = MaterialTheme.typography.bodyMedium)
            ModePicker(mode, onMode, "${mode.summary} · 默认配置：${Simple.profileName(cfg, mode, setup.active)}", mode.accent())
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProfileSelector(cfg, mode, slot) { slot = it }
                        ProfileEditor(store, cfg, fac, eff.tables, mode, slot)
                    }
                    Column(Modifier.weight(1f)) { ProtectionPanel(store, !fine) }
                }
            } else {
                ProfileSelector(cfg, mode, slot) { slot = it }
                ProfileEditor(store, cfg, fac, eff.tables, mode, slot)
                ProtectionPanel(store, !fine)
            }
            if (fine) {
                Text("精细模式 · 保存以下温度分段将取代默认配置与共用保护", style = MaterialTheme.typography.titleMedium)
                val stages = store.draft[mode].orEmpty()
                val factory = store.factoryStages(mode)
                for (start in stages.indices step (if (wide) 2 else 1)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (i in start until minOf(start + if (wide) 2 else 1, stages.size)) {
                            Box(Modifier.weight(1f)) { StageCard(store, mode, i, stages, stages[i], factory?.getOrNull(i), eff.tables) }
                        }
                    }
                }
                TextButton(onClick = { store.resetMode(mode) }, enabled = !store.busy) { Text("将${mode.title}分段恢复为原厂值") }
            }
            Text("仅修改默认游戏策略；独立游戏策略与超神不变。温度为机身温度，原厂温控仍生效。保存后下次进入游戏生效。", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ProfileSelector(cfg: SimpleConfig, mode: Mode, slot: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        for (s in 0 until Simple.SLOTS) {
            SegmentedButton(selected = slot == s, onClick = { onSelect(s) }, shape = SegmentedButtonDefaults.itemShape(s, Simple.SLOTS)) {
                Text(Simple.profileName(cfg, mode, s) + if (cfg.modes.getValue(mode).active == s) " · 默认" else "")
            }
        }
    }
}

@Composable
private fun ProfileEditor(store: Store, cfg: SimpleConfig, fac: PolicyFile, tables: FreqTables, mode: Mode, slot: Int) {
    val factoryLimits = Simple.factoryLimits(fac, mode)
    val limits = Simple.profileLimits(cfg, fac, mode, slot)
    val active = cfg.modes.getValue(mode).active
    Card(Item) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (slot == 0) {
                Column {
                    Text(Simple.FACTORY_NAME, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "官方「${mode.title}」档第一段温控的参数，不可修改。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                OutlinedTextField(
                    value = Simple.profileName(cfg, mode, slot),
                    onValueChange = { store.renameProfile(mode, slot, it) },
                    label = { Text("配置名称") },
                    singleLine = true,
                    enabled = !store.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            for (c in Cluster.entries) {
                val b = limits[c] ?: continue
                ClusterRow(c, b, factoryLimits[c], "Factory", tables, mode.accent(), slot != 0 && !store.busy) { lo, hi ->
                    store.setProfileBound(mode, slot, c, lo, hi)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (slot == active) {
                    Text("✓ 「${mode.title}」档当前使用这组配置", color = mode.accent(), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Button(onClick = { store.setActive(mode, slot) }, enabled = !store.busy) { Text("设为默认") }
                }
                Spacer(Modifier.weight(1f))
                if (slot != 0) {
                    TextButton(
                        onClick = { store.resetProfile(mode, slot) },
                        enabled = !store.busy && !Simple.sameLimits(tables, limits, factoryLimits),
                    ) { Text("重置为 Factory 值") }
                }
            }
        }
    }
}

@Composable
private fun ProtectionPanel(store: Store, enabled: Boolean) {
    val fac = store.factory ?: return
    val eff = store.effective ?: return
    val cfg = store.simpleDraft ?: return
    val defaults = Simple.defaultProtection(fac)
    Card(Item) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("共用保护", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TempStepper(cfg.trigger, Simple.MIN_TRIGGER..Simple.MAX_TRIGGER, "${cfg.trigger}℃", ProtectionAccent,
                    Simple.DEFAULT_TRIGGER, "默认", enabled && !store.busy, store::setTrigger)
            }
            Text(if (enabled) "达到阈值启用，低于 ${cfg.trigger - 1}℃ 恢复；与各档默认配置取较低限制。" else "精细模式启用时，共用保护不参与保存；请在下方编辑各温度段。", style = MaterialTheme.typography.bodySmall)
            for (c in Cluster.entries) {
                val b = cfg.protection[c] ?: continue
                ClusterRow(c, b, defaults[c], "默认", eff.tables, ProtectionAccent, enabled && !store.busy) { lo, hi -> store.setProtection(c, lo, hi) }
            }
            TextButton(onClick = store::resetProtection, enabled = enabled && !store.busy) { Text("恢复保护默认值") }
        }
    }
}

@Composable
private fun StageCard(
    store: Store,
    mode: Mode,
    index: Int,
    stages: List<Stage>,
    stage: Stage,
    factory: Stage?,
    tables: FreqTables,
) {
    Card(Item) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("机身 ${stageTitle(stages, index)}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "温度段 ${index + 1} / ${stages.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                store.tempRange(mode, index)?.let { range ->
                    val t = stage.temp.toInt()
                    TempStepper(t, range, "${t}℃ 起", mode.accent(), factory?.temp?.toInt(), "原厂", !store.busy) {
                        store.setTemp(mode, index, it)
                    }
                }
            }
            for (c in Cluster.entries) {
                val b = stage.bounds[c] ?: continue
                ClusterRow(c, b, factory?.bounds?.get(c), "原厂", tables, mode.accent(), !store.busy) { lo, hi ->
                    store.setBound(mode, index, c, lo, hi)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- shared pieces


@Composable
private fun ModePicker(mode: Mode, onSelect: (Int) -> Unit, caption: String, captionColor: Color) {
    Column(Item, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Mode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = m == mode,
                    onClick = { onSelect(i) },
                    shape = SegmentedButtonDefaults.itemShape(i, Mode.entries.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = m.accent().copy(alpha = 0.22f),
                        activeContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    label = { Text(m.title, fontWeight = FontWeight.Medium) },
                )
            }
        }
        Text(
            caption,
            style = MaterialTheme.typography.bodyMedium,
            color = captionColor,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun TempStepper(
    value: Int,
    range: IntRange,
    text: String,
    accent: Color,
    reference: Int?,
    referenceLabel: String,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    val changed = reference != null && reference != value
    Column(horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { onChange(value - 1) }, enabled = enabled && value > range.first) { Text("−") }
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                color = if (changed) accent else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            FilledTonalButton(onClick = { onChange(value + 1) }, enabled = enabled && value < range.last) { Text("+") }
        }
        if (changed) {
            Text("$referenceLabel ${reference}℃", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ClusterRow(
    c: Cluster,
    b: Bound,
    reference: Bound?,
    referenceLabel: String,
    tables: FreqTables,
    accent: Color,
    enabled: Boolean,
    onChange: (Int, Int) -> Unit,
) {
    val steps = tables.steps(c)
    val top = steps.size - 1
    val (p1, p2) = tables.positions(c, b)
    val lo = minOf(p1, p2)
    val hi = maxOf(p1, p2)
    val changed = reference != null && !tables.sameEffect(c, b, reference)
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(c.title, style = MaterialTheme.typography.titleSmall)
            if (c.detail.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(c.detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            Text(
                tables.describe(c, b),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (changed) accent else MaterialTheme.colorScheme.onSurface,
            )
        }
        RangeSlider(
            value = lo.toFloat()..hi.toFloat(),
            onValueChange = { r -> onChange(r.start.roundToInt(), r.endInclusive.roundToInt()) },
            valueRange = 0f..top.toFloat(),
            steps = (top - 1).coerceAtLeast(0),
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
        Row {
            Text(tables.format(c, steps.first()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (changed) "$referenceLabel ${tables.describe(c, reference)}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Text(
                "不限（${tables.format(c, steps.last())}）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


@Composable
private fun ActionBar(text: String, busy: Boolean, onDiscard: (() -> Unit)?, actionLabel: String, onAction: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            if (onDiscard != null) TextButton(onClick = onDiscard, enabled = !busy) { Text("放弃") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onAction, enabled = !busy) { Text(actionLabel) }
        }
    }
}

@Composable
private fun ApplyDialog(changes: List<String>, note: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存并应用？") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                changes.take(20).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                if (changes.size > 20) Text("…另有 ${changes.size - 20} 处", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
