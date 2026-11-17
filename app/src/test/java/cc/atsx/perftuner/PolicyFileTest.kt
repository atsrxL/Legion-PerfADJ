package cc.atsx.perftuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PolicyFileTest {
    // Same layout as the vendor file: a tuned game precedes "default" and shares its values,
    // so an edit that leaks outside the default policy would show up there.
    private val fixture = """
<?xml version="1.0" encoding="UTF-8"?>
<GameOptPolicy version = "20260508">
    <PreEnv>
        <CPU>
            <cluster id="2" name="Gold" min="0x40800000" max="0x40804000">384000 787200 1440000 2496000</cluster>
            <cluster id="7" name="Prime" min="0x40800200" max="0x40804200">768000 1132800 2438400 4608000</cluster>
        </CPU>
        <GPU>
            <cluster id="0" name="Gpu" min="0x42804000" max="0x42808000">1200000000 902000000 646000000 160000000</cluster>
        </GPU>
    </PreEnv>
    <BaseInfo>
        <Game name="com.tencent.tmgp.sgame">
            <SceneList><scene>1</scene></SceneList>
        </Game>
    </BaseInfo>
    <GamePolicy>
        <Game name="王者荣耀" pkg="com.tencent.tmgp.sgame" param="0">
            <Mode name="Savage" id="2">
                <Policy>
                    <TempLevel level="1" temp="0">
                        <item type="freq" name="Gold">1_2</item>
                        <item type="freq" name="Prime">1_-1</item>
                        <item type="freq" name="Gpu">2_1</item>
                    </TempLevel>
                </Policy>
            </Mode>
        </Game>
        <Game name="default" pkg="default" param="0">
            <ThermalTempType>11</ThermalTempType>
            <Mode name="Balance" id="1">
                <Policy>
                    <TempLevel level="1" temp="0">
                        <item type="freq" name="Gold">1_2</item>
                        <item type="freq" name="Prime">1_2</item>
                        <item type="freq" name="Gpu">2_1</item>
                    </TempLevel>
                    <TempLevel level="2" temp="44">
                        <item type="freq" name="Gold">0_1</item>
                        <item type="freq" name="Prime">0_1</item>
                        <item type="freq" name="Gpu">2_1</item>
                    </TempLevel>
                </Policy>
            </Mode>
            <Mode name="PowerSave" id="4">
                <Policy>
                    <TempLevel level="1" temp="0">
                        <item type="freq" name="Gold">0_1</item>
                        <item type="freq" name="Prime">0_1</item>
                        <item type="freq" name="Gpu">4_2</item>
                    </TempLevel>
                </Policy>
            </Mode>
            <Mode name="Savage" id="2">
                <Policy>
                    <TempLevel level="2" temp="42">
                        <item type="freq" name="Gold">1_2</item>
                        <item type="freq" name="Prime">0_2</item>
                        <item type="freq" name="Gpu">3_2</item>
                    </TempLevel>
                    <TempLevel level="1" temp="0">
                        <item type="freq" name="Gold">1_2</item>
                        <item type="freq" name="Prime">1_-1</item>
                        <item type="freq" name="Gpu">2_1</item>
                    </TempLevel>
                </Policy>
            </Mode>
            <Mode name="chaoshen" id="16">
                <Policy>
                    <TempLevel level="1" temp="0">
                        <item type="freq" name="Gold">1_2</item>
                    </TempLevel>
                </Policy>
            </Mode>
        </Game>
    </GamePolicy>
</GameOptPolicy>
""".trimStart()

    private val policy = PolicyFile.parse(fixture)

    @Test
    fun parsesOnlyTheDefaultPolicy() {
        assertEquals(20260508L, policy.version)
        assertEquals(setOf(Mode.POWER_SAVE, Mode.BALANCE, Mode.SAVAGE), policy.modes.keys)
        assertEquals(listOf(GameEntry("王者荣耀", "com.tencent.tmgp.sgame")), policy.dedicatedGames)
        val savage = policy.modes.getValue(Mode.SAVAGE)
        assertEquals(listOf(1, 2), savage.map { it.level }) // sorted by level, not file order
        assertEquals(Bound(1, -1), savage[0].bounds[Cluster.PRIME])
        assertEquals(42f, savage[1].temp)
    }

    @Test
    fun renderRewritesOnlyEditedValues() {
        val savage = policy.modes.getValue(Mode.SAVAGE)
        val first = savage[0]
        val edited = savage.toMutableList()
        edited[0] = first.copy(bounds = first.bounds + (Cluster.GOLD to Bound(1, 3)) + (Cluster.GPU to Bound(2, 0)))
        edited[1] = savage[1].copy(temp = 43f)
        val out = policy.render(mapOf(Mode.SAVAGE to edited), 20260509L)

        val reparsed = PolicyFile.parse(out)
        assertEquals(20260509L, reparsed.version)
        assertEquals(edited, reparsed.modes[Mode.SAVAGE])
        assertEquals(policy.modes[Mode.BALANCE], reparsed.modes[Mode.BALANCE])

        val expected = fixture
            .replace("version = \"20260508\"", "version = \"20260509\"")
            .replace("level=\"2\" temp=\"42\"", "level=\"2\" temp=\"43\"")
            .replaceAfterLast("<Mode name=\"Savage\" id=\"2\">", "")
        // Everything up to and including the default Savage header is untouched, including the
        // tuned game's identical "1_2" / "2_1" values.
        assertTrue(out.startsWith(expected))
        val tail = out.substringAfterLast("<Mode name=\"Savage\" id=\"2\">")
        assertTrue(tail.contains("<item type=\"freq\" name=\"Gold\">1_3</item>"))
        assertTrue(tail.contains("<item type=\"freq\" name=\"Gpu\">2_0</item>"))
        assertEquals(fixture.length, out.length) // every replacement here keeps its width
    }

    @Test
    fun unchangedRenderKeepsBytes() {
        assertEquals(fixture, policy.render(policy.modes, policy.version))
    }

    @Test
    fun gpuSliderRunsSlowToFastAndExtremesMeanUnlocked() {
        val t = policy.tables
        assertEquals(listOf(160_000_000L, 646_000_000L, 902_000_000L, 1_200_000_000L), t.steps(Cluster.GPU))
        // Level 2 (646 MHz) floor and level 1 (902 MHz) cap.
        assertEquals(1 to 2, t.positions(Cluster.GPU, Bound(2, 1)))
        assertEquals(Bound(2, 1), t.bound(Cluster.GPU, 1, 2))
        // Out-of-range levels clamp to the slowest step, and both ends of the slider are -1.
        assertEquals(0 to 3, t.positions(Cluster.GPU, Bound(18, -1)))
        assertEquals(Bound(-1, -1), t.bound(Cluster.GPU, 0, 3))
        assertTrue(t.sameEffect(Cluster.GPU, Bound(18, 1), Bound(-1, 1)))
        assertEquals("646 – 902 MHz", t.describe(Cluster.GPU, Bound(2, 1)))
    }

    @Test
    fun cpuSliderMapsIndicesAndUnlockedCap() {
        val t = policy.tables
        assertEquals(1 to 3, t.positions(Cluster.PRIME, Bound(1, -1)))
        assertEquals(Bound(1, -1), t.bound(Cluster.PRIME, 1, 3))
        assertEquals("1.13 GHz – 不限", t.describe(Cluster.PRIME, Bound(1, -1)))
        assertEquals("0.79 – 2.50 GHz", t.describe(Cluster.GOLD, Bound(1, 3)))
    }

    @Test
    fun validationRejectsUnorderedStagesAndInvertedBounds() {
        val savage = policy.modes.getValue(Mode.SAVAGE)
        assertNull(validate(Mode.SAVAGE, savage))
        val hot = savage.toMutableList().also { it[1] = it[1].copy(temp = 0f) }
        assertNotNull(validate(Mode.SAVAGE, hot))
        val cpu = savage.toMutableList().also { it[0] = it[0].copy(bounds = it[0].bounds + (Cluster.GOLD to Bound(3, 1))) }
        assertNotNull(validate(Mode.SAVAGE, cpu))
        val gpu = savage.toMutableList().also { it[0] = it[0].copy(bounds = it[0].bounds + (Cluster.GPU to Bound(1, 2))) }
        assertNotNull(validate(Mode.SAVAGE, gpu))
    }

    @Test
    fun changeListUsesEffectNotSpelling() {
        val t = policy.tables
        val savage = policy.modes.getValue(Mode.SAVAGE)
        val respelled = savage.toMutableList().also {
            it[0] = it[0].copy(bounds = it[0].bounds + (Cluster.PRIME to Bound(1, 3))) // top index == unlocked
        }
        assertTrue(sameStages(t, savage, respelled))
        val raised = savage.toMutableList().also {
            it[0] = it[0].copy(bounds = it[0].bounds + (Cluster.GOLD to Bound(1, -1)))
        }
        assertFalse(sameStages(t, savage, raised))
        assertEquals(
            listOf("野兽 · 低于 42℃ · 中核：0.79 – 1.44 GHz → 0.79 GHz – 不限"),
            describeChanges(t, policy.modes, policy.modes + (Mode.SAVAGE to raised)),
        )
    }

    /** Runs against the device's vendor file when GAMEPERFCONFIG points at a copy of it. */
    @Test
    fun realVendorFileRoundTrips() {
        val path = System.getenv("GAMEPERFCONFIG")
        assumeTrue(path != null && File(path).isFile)
        val text = File(path!!).readText()
        val p = PolicyFile.parse(text)
        assertEquals(text, p.render(p.modes, p.version))
        val savage = p.modes.getValue(Mode.SAVAGE)
        val raised = savage.toMutableList().also {
            it[0] = it[0].copy(bounds = it[0].bounds + (Cluster.GOLD to Bound(3, 20)) + (Cluster.GPU to Bound(12, 3)))
        }
        val out = p.render(mapOf(Mode.SAVAGE to raised), p.version + 1)
        val changed = text.lines().zip(out.lines()).count { (a, b) -> a != b }
        assertEquals(3, changed) // version + two items
        assertEquals(raised, PolicyFile.parse(out).modes[Mode.SAVAGE])
    }
}
