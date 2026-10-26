import org.gradle.api.Project
import java.io.File

/**
 * The MinGW-w64 install the Windows host CMake JNI builds compile with — the C sources use
 * GCC-only syntax MSVC rejects: `mingw.dir` in local.properties, else MINGW_HOME, else MSYS2's
 * default install. Null off Windows, or when none has bin/gcc.exe + bin/mingw32-make.exe.
 */
fun Project.mingwToolchainHome(): File? {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return null
    return listOfNotNull(localProp("mingw.dir"), System.getenv("MINGW_HOME"), "C:/msys64/mingw64", "C:/mingw64")
        .map(::File)
        .firstOrNull { File(it, "bin/gcc.exe").isFile && File(it, "bin/mingw32-make.exe").isFile }
}

const val MINGW_MISSING =
    "No MinGW-w64 toolchain found for the host JNI build (its C is GCC-only; MSVC rejects it). Set " +
        "mingw.dir in local.properties, or MINGW_HOME, to a MinGW-w64 install with bin/gcc.exe and " +
        "bin/mingw32-make.exe (MSYS2's default: C:/msys64/mingw64)."
