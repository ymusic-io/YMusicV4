import org.gradle.api.Project
import java.io.File

/**
 * The Inno Setup Compiler (ISCC.exe) desktopApp's packageReleaseInnoSetup task runs:
 * `-Pinno.iscc=` override, else `inno.iscc` in local.properties, else ISCC on PATH.
 * Null off Windows, or when none resolves to an existing file.
 */
fun Project.innoSetupCompiler(): File? {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return null
    val configured = sequenceOf(findProperty("inno.iscc") as String?, localProp("inno.iscc"))
        .filterNotNull()
        .map(::File)
    val onPath = System.getenv("PATH").orEmpty()
        .splitToSequence(File.pathSeparatorChar)
        .map { File(it.trim(), "ISCC.exe") }
    return (configured + onPath).firstOrNull { it.isFile }
}

const val INNO_SETUP_MISSING =
    "Inno Setup Compiler not found. Install from https://jrsoftware.org/isdl.php, then set " +
        "inno.iscc=<path to ISCC.exe> in local.properties (or add Inno Setup to PATH, or pass " +
        "-Pinno.iscc=<path> on the Gradle command line)."
