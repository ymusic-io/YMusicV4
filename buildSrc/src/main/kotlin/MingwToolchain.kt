import org.gradle.api.Project
import java.io.File

/**
 * The MinGW-w64 install the Windows host CMake JNI builds compile with — the C sources use
 * GCC/Clang-only syntax MSVC rejects: `mingw.dir` in local.properties, else MINGW_HOME, else MSYS2's
 * default install for the JVM's arch. Null off Windows, or when none has a C compiler +
 * bin/mingw32-make.exe.
 */
fun Project.mingwToolchainHome(): File? {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return null
    // The JVM's arch, not the OS's: the JNI DLL must load into this JVM (an x64 JVM runs emulated on
    // Windows on ARM), and the other arch's toolchain builds DLLs it can't load.
    val defaults = if (hostIsArm64) listOf("C:/msys64/clangarm64") else listOf("C:/msys64/mingw64", "C:/mingw64")
    return (listOfNotNull(localProp("mingw.dir"), System.getenv("MINGW_HOME")) + defaults)
        .map(::File)
        .firstOrNull { mingwCCompiler(it).isFile && File(it, "bin/mingw32-make.exe").isFile }
}

/**
 * CMake configure args that build with [mingwToolchainHome]'s compiler and make on a Windows host
 * (the single-config `MinGW Makefiles` generator); empty elsewhere. Fails with [MINGW_MISSING] on
 * Windows without a toolchain.
 */
fun Project.mingwCmakeArgs(): List<String> {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return emptyList()
    val home = mingwToolchainHome() ?: error(MINGW_MISSING)
    // Forward slashes: CMake treats backslashes as escapes in the paths it writes into its cache.
    fun cmakePath(f: File) = f.absolutePath.replace('\\', '/')
    return listOf(
        "-G",
        "MinGW Makefiles",
        "-DCMAKE_C_COMPILER=${cmakePath(mingwCCompiler(home))}",
        "-DCMAKE_MAKE_PROGRAM=${cmakePath(File(home, "bin/mingw32-make.exe"))}",
    )
}

/** gcc, else clang: MSYS2's CLANGARM64 (Windows on ARM) ships clang only. */
fun mingwCCompiler(home: File): File = File(home, "bin/gcc.exe").takeIf { it.isFile } ?: File(home, "bin/clang.exe")

const val MINGW_MISSING =
    "No MinGW-w64 toolchain found for the host JNI build (its C is GCC/Clang-only; MSVC rejects it). " +
        "Set mingw.dir in local.properties, or MINGW_HOME, to a MinGW-w64 install with bin/gcc.exe " +
        "(or bin/clang.exe) and bin/mingw32-make.exe (MSYS2's default: C:/msys64/mingw64, or " +
        "C:/msys64/clangarm64 on ARM)."
