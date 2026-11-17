package cc.atsx.perftuner

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Root operations on TAssistent's policy copy. TAssistent reads [DATA] and replaces it with
 * [SYSTEM] only when the system copy carries a higher version, so a raised version persists.
 */
object Root {
    const val TASSISTENT = "com.zui.tassistent"
    const val SYSTEM = "/system/etc/gameperfconfig.xml"
    const val DATA = "/data/user/0/com.zui.tassistent/files/gameperfconfig.xml"
    private const val CONTROL = "/sys/devices/system/cpu/cpu0/thermal/control"
    private const val D = '$'

    class Result(val code: Int?, val out: String, val err: String) {
        val ok get() = code == 0
        fun detail(): String = listOf(err.trim(), out.trim()).filter { it.isNotEmpty() }.joinToString("\n")
            .ifEmpty { if (code == null) "执行超时" else "退出码 $code" }
    }

    sealed interface Read {
        data class Found(val text: String) : Read
        data object Missing : Read
        data class Failed(val detail: String) : Read
    }

    /**
     * Runs [script] in a root shell fed through stdin. `--mount-master` is required: with app data
     * isolation, this app's own mount namespace hides other apps' directories under /data/user/0,
     * so TAssistent's policy would look missing.
     */
    fun sh(script: String, timeoutSec: Long = 20): Result {
        val p = try {
            ProcessBuilder("su", "--mount-master").start()
        } catch (e: IOException) {
            return Result(-1, "", "无法执行 su：${e.message}")
        }
        val out = StringBuilder()
        val err = StringBuilder()
        val t1 = thread { runCatching { out.append(p.inputStream.bufferedReader().readText()) } }
        val t2 = thread { runCatching { err.append(p.errorStream.bufferedReader().readText()) } }
        try {
            p.outputStream.bufferedWriter().use { it.write(script); it.write("\nexit\n") }
        } catch (_: IOException) {
            // su refused or exited early; the exit code and stderr say why.
        }
        val done = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!done) p.destroyForcibly()
        t1.join(2000)
        t2.join(2000)
        return Result(if (done) p.exitValue() else null, out.toString(), err.toString())
    }

    fun available(): Boolean = sh("id -u", 10).let { it.ok && it.out.trim() == "0" }

    fun readData(): Read {
        val r = sh("if [ -f '$DATA' ]; then echo FOUND; cat '$DATA'; else echo MISSING; fi", 30)
        if (!r.ok) return Read.Failed(r.detail())
        return when {
            r.out.startsWith("FOUND\n") -> Read.Found(r.out.removePrefix("FOUND\n"))
            r.out.startsWith("MISSING") -> Read.Missing
            else -> Read.Failed("读取结果无法识别")
        }
    }

    fun readSystem(): String? = runCatching { File(SYSTEM).readText() }.getOrNull()
        ?: sh("cat '$SYSTEM'", 30).takeIf { it.ok }?.out

    /** Non-zero while a game mode has a thermal scene applied, i.e. a game is running. */
    fun thermalScene(): Int? = sh("cat '$CONTROL'", 10).takeIf { it.ok }?.out?.trim()?.toIntOrNull()

    /** Atomically installs [src] as TAssistent's policy, then restarts TAssistent. */
    fun install(src: File): Result = replaceWith(src.absolutePath)

    fun restoreFactory(): Result = replaceWith(SYSTEM)

    private fun replaceWith(src: String): Result = sh(
        """
        set -e
        T='$DATA'
        [ -d "${D}{T%/*}" ] || { echo "游戏助手的策略目录不存在，请先进入一次游戏" >&2; exit 3; }
        cp '$src' "${D}T.tmp"
        chown system:system "${D}T.tmp"
        chmod 600 "${D}T.tmp"
        restorecon "${D}T.tmp" >/dev/null 2>&1 || true
        mv -f "${D}T.tmp" "${D}T"
        am force-stop $TASSISTENT
        echo "SHA=${D}(sha256sum "${D}T" | cut -d' ' -f1)"
        echo "CTX=${D}(ls -Z "${D}T" | cut -d' ' -f1)"
        """.trimIndent(),
        30,
    )

    @Volatile private var skinZone: String? = null

    /** Skin temperature (quiet-therm) in °C and the current thermal scene. */
    fun live(): Pair<Float?, Int?> {
        val zone = skinZone ?: sh(
            "for z in /sys/class/thermal/thermal_zone*; do " +
                "if [ \"${D}(cat ${D}z/type 2>/dev/null)\" = quiet-therm ]; then echo ${D}z; break; fi; done",
            15,
        ).out.trim().takeIf { it.startsWith("/sys/") }?.also { skinZone = it }
        val r = sh("echo \"Q=${D}(cat '${zone ?: "/dev/null"}/temp' 2>/dev/null)\"; echo \"C=${D}(cat '$CONTROL' 2>/dev/null)\"", 10)
        val lines = r.out.lines()
        val q = lines.firstOrNull { it.startsWith("Q=") }?.removePrefix("Q=")?.trim()?.toFloatOrNull()?.div(1000f)
        val c = lines.firstOrNull { it.startsWith("C=") }?.removePrefix("C=")?.trim()?.toIntOrNull()
        return q to c
    }
}
