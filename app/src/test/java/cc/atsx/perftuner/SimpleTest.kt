package cc.atsx.perftuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SimpleTest {
    private fun stage(level: Int, temp: Int, gold: String, prime: String, gpu: String) = """
                    <TempLevel level="$level" temp="$temp">
                        <item type="freq" name="Gold">$gold</item>
                        <item type="freq" name="Prime">$prime</item>
                        <item type="freq" name="Gpu">$gpu</item>
                    </TempLevel>"""

    private fun mode(name: String, id: Int, vararg stages: String) = """
            <Mode name="$name" id="$id">
                <Policy>${stages.joinToString("")}
                </Policy>
            </Mode>"""

    // Four stages per mode, like the vendor file. Tables: Gold/Prime 4 steps, GPU levels 0–3.
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
    <GamePolicy>
        <Game name="default" pkg="default" param="0">${
        mode(
            "Balance", 1,
            stage(1, 0, "1_2", "1_2", "2_1"), stage(2, 40, "1_2", "1_2", "2_1"),
            stage(3, 42, "1_2", "1_2", "2_1"), stage(4, 44, "0_1", "0_1", "2_1"),
        )
    }${
        mode(
            "PowerSave", 4,
            stage(1, 0, "0_1", "0_1", "3_2"), stage(2, 38, "0_1", "0_1", "3_2"),
            stage(3, 40, "0_1", "0_1", "3_2"), stage(4, 42, "0_1", "0_0", "3_2"),
        )
    }${
        mode(
            "Savage", 2,
            stage(1, 0, "1_2", "1_-1", "2_1"), stage(2, 42, "1_2", "0_2", "2_1"),
            stage(3, 45, "1_2", "0_1", "2_1"), stage(4, 47, "1_1", "0_1", "3_2"),
        )
    }${mode("chaoshen", 16, stage(1, 0, "1_2", "1_-1", "2_1"))}
        </Game>
    </GamePolicy>
</GameOptPolicy>
""".trimStart()

    private val fac = PolicyFile.parse(fixture)
    private val t = fac.tables

    @Test
    fun defaultsUseFactoryFirstStageAndSavage47Protection() {
        val d = Simple.defaults(fac)
        assertEquals(47, d.trigger)
        assertEquals(mapOf(Cluster.GOLD to Bound(1, 1), Cluster.PRIME to Bound(0, 1), Cluster.GPU to Bound(3, 2)), d.protection)
        for (m in Mode.entries) {
            val s = d.modes.getValue(m)
            assertEquals(0, s.active)
            assertEquals(listOf("配置 2", "配置 3"), s.custom.map { it.name })
            assertEquals(fac.modes.getValue(m).first().bounds, s.custom[0].limits)
            assertEquals(fac.modes.getValue(m).first().bounds, Simple.activeLimits(d, fac, m))
        }
    }

    @Test
    fun deriveWritesProfileBelowTriggerAndProtectionFromTriggerOn() {
        val custom = mapOf(Cluster.GOLD to Bound(1, -1), Cluster.PRIME to Bound(1, -1), Cluster.GPU to Bound(2, 0))
        val d = Simple.defaults(fac)
        val savage = d.modes.getValue(Mode.SAVAGE)
        val cfg = d.copy(
            trigger = 45,
            modes = d.modes + (Mode.SAVAGE to savage.copy(custom = listOf(Profile("满血", custom), savage.custom[1]), active = 1)),
        )
        val out = Simple.derive(cfg, fac, fac.modes, t).getValue(Mode.SAVAGE)
        assertEquals(listOf(0f, 45f, 46f, 47f), out.map { it.temp })
        assertEquals(custom, out[0].bounds)
        // Protection Gold 0.79 fixed, Prime ≤1.13, GPU 160–646; floors drop to the profile's where lower.
        val expected = mapOf(Cluster.GOLD to Bound(1, 1), Cluster.PRIME to Bound(-1, 1), Cluster.GPU to Bound(-1, 2))
        for (s in out.drop(1)) assertEquals(expected, s.bounds)
        assertEquals(listOf(1, 2, 3, 4), out.map { it.level })
    }

    @Test
    fun protectionNeverExceedsTheModeProfile() {
        val cfg = Simple.defaults(fac)
        for (m in Mode.entries) {
            val cap = Simple.activeLimits(cfg, fac, m)
            for ((c, b) in Simple.protectionFor(cfg, fac, m, t)) {
                val (lo, hi) = t.positions(c, b)
                val (capLo, capHi) = t.positions(c, cap.getValue(c))
                assertTrue("$m $c floor", lo <= capLo)
                assertTrue("$m $c cap", hi <= capHi)
            }
        }
        // PowerSave's floors (0.38 / 0.77 GHz) are below protection's 0.79 / 0.77 floors and win; caps tie.
        val ps = Simple.protectionFor(cfg, fac, Mode.POWER_SAVE, t)
        assertEquals(Bound(-1, 1), ps[Cluster.GOLD])
        assertEquals(Bound(-1, 1), ps[Cluster.PRIME])
    }

    @Test(expected = PolicyException::class)
    fun deriveRejectsLayoutsTAssistentCannotSwitch() {
        val layout = fac.modes + (Mode.BALANCE to fac.modes.getValue(Mode.BALANCE).take(3))
        Simple.derive(Simple.defaults(fac), fac, layout, t)
    }

    @Test
    fun appliedConfigRendersAndParsesBack() {
        val cfg = Simple.defaults(fac).copy(trigger = 50)
        val derived = Simple.derive(cfg, fac, fac.modes, t)
        val out = fac.render(derived, fac.version + 1)
        val back = PolicyFile.parse(out)
        for (m in Mode.entries) {
            assertTrue(sameStages(t, derived[m], back.modes[m]))
            assertEquals(listOf(0f, 50f, 51f, 52f), back.modes.getValue(m).map { it.temp })
        }
        // 超神 is outside the edited modes and keeps its text.
        assertEquals(
            fixture.substringAfter("<Mode name=\"chaoshen\" id=\"16\">"),
            out.substringAfter("<Mode name=\"chaoshen\" id=\"16\">"),
        )
    }

    @Test
    fun encodeDecodeRoundTripsAndFallsBack() {
        val d = Simple.defaults(fac)
        val ps = d.modes.getValue(Mode.POWER_SAVE)
        val cfg = d.copy(
            trigger = 44,
            protection = d.protection + (Cluster.GPU to Bound(-1, 3)),
            modes = d.modes + (Mode.POWER_SAVE to ps.copy(custom = listOf(Profile("日常 = 50% 省电", ps.custom[0].limits), ps.custom[1]), active = 2)),
        )
        assertEquals(cfg, Simple.decode(Simple.encode(cfg), fac))
        val damaged = Simple.encode(cfg)
            .replace("trigger=44", "trigger=abc")
            .replace("mode.4.active=2", "mode.4.active=9")
        val fixed = Simple.decode(damaged, fac)!!
        assertEquals(47, fixed.trigger)
        assertEquals(0, fixed.modes.getValue(Mode.POWER_SAVE).active)
        assertEquals("日常 = 50% 省电", fixed.modes.getValue(Mode.POWER_SAVE).custom[0].name)
        assertNull(Simple.decode("v=2\ntrigger=40", fac))
        assertNull(Simple.decode(null, fac))
    }

    @Test
    fun changeListNamesWhatChanged() {
        val d = Simple.defaults(fac)
        val savage = d.modes.getValue(Mode.SAVAGE)
        val to = d.copy(
            trigger = 45,
            modes = d.modes + (Mode.SAVAGE to savage.copy(custom = listOf(savage.custom[0].copy(name = "满血"), savage.custom[1]), active = 1)),
        )
        assertEquals(
            listOf(
                "保护温度 · 触发温度：47℃ → 45℃",
                "野兽 · 第 2 组名称：配置 2 → 满血",
                "野兽 · 默认配置：Factory → 满血",
            ),
            Simple.describeChanges(t, fac, d, to),
        )
        assertTrue(Simple.same(t, d, d.copy(protection = d.protection + (Cluster.PRIME to Bound(-1, 1)))))
    }

    /** Runs against the device's vendor file when GAMEPERFCONFIG points at a copy of it. */
    @Test
    fun realVendorFileDefaults() {
        val path = System.getenv("GAMEPERFCONFIG")
        assumeTrue(path != null && File(path).isFile)
        val text = File(path!!).readText()
        val p = PolicyFile.parse(text)
        val cfg = Simple.defaults(p)
        assertEquals(p.modes.getValue(Mode.SAVAGE).first { it.temp == 47f }.bounds, cfg.protection)
        val derived = Simple.derive(cfg, p, p.modes, p.tables)
        val out = p.render(derived, p.version + 1)
        val back = PolicyFile.parse(out)
        for (m in Mode.entries) {
            assertTrue(sameStages(p.tables, derived[m], back.modes[m]))
            assertEquals(listOf(0f, 47f, 48f, 49f), back.modes.getValue(m).map { it.temp })
            assertEquals(p.modes.getValue(m).first().bounds, back.modes.getValue(m).first().bounds)
        }
        assertEquals(p.dedicatedGames, back.dedicatedGames)
        val chaoshen = "<Mode name=\"chaoshen\" id=\"16\">"
        assertEquals(text.substringAfter(chaoshen).substringBefore("</Mode>"), out.substringAfter(chaoshen).substringBefore("</Mode>"))
    }
}
