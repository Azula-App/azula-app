package dev.azula.persist

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Desktop media export: copy every received file into `~/Downloads/Azula/`
 * (or `$AZULA_DATA_DIR/Downloads/Azula/` when the isolation override is set --
 * see [azulaHomeDir]), where the OS file managers already surface it. No
 * gallery concept on desktop, so images/video land in the same folder as
 * everything else. Collision-safe: `report.pdf` -> `report (1).pdf` ->
 * `report (2).pdf` …
 */
actual fun platformMediaExporter(): MediaExporter? = JvmMediaExporter()

class JvmMediaExporter : MediaExporter {
    private val dir: File by lazy { mediaExportDir().also { it.mkdirs() } }

    override suspend fun export(path: String, name: String, mime: String): String? {
        val src = File(path)
        if (!src.exists()) return null
        return runCatching {
            val dest = uniqueFile(dir, name.ifBlank { "file" })
            Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            "Downloads/Azula/${dest.name}"
        }.getOrNull()
    }
}

/** `$AZULA_DATA_DIR/Downloads/Azula` when the isolation override is set (see
 *  [azulaHomeDir]), else `~/Downloads/Azula` as before. */
internal fun mediaExportDir(env: (String) -> String? = System::getenv): File =
    File(azulaHomeDir(env), "Downloads/Azula")

/** Returns a not-yet-existing [File] in [dir] for [name], inserting ` (n)` before
 *  the extension on collision (`a.png` -> `a (1).png`, extension-less -> `a (1)`). */
private fun uniqueFile(dir: File, name: String): File {
    val candidate = File(dir, name)
    if (!candidate.exists()) return candidate
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var n = 1
    while (true) {
        val f = File(dir, "$base ($n)$ext")
        if (!f.exists()) return f
        n++
    }
}
