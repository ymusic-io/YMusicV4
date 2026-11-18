import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * A C library a cinterop binds from the build host (headers + libs), found when its def is generated,
 * first hit wins: [overrideProperty] / [overrideEnv] (a prefix with include/ and lib/); on macOS the copy
 * a `linkingDef`'s library links (see [hostLibraryDef]); `pkg-config [pkgConfigModule]`; on macOS
 * `brew --prefix --installed [brewFormula]`. [libs] are linked when the hit is a prefix; pkg-config
 * brings its own. The first of [libs] is the one looked for among the linking library's dependencies.
 * A target other than the host's own (`-PkmpTargets=all`) is found only through the override.
 */
data class HostNativeLibrary(
    val displayName: String,
    val pkgConfigModule: String,
    val brewFormula: String?,
    val probeHeader: String,
    val libs: List<String>,
    val overrideProperty: String,
    val overrideEnv: String,
    val installHint: String,
)

/**
 * Registers `generate<Interop>Def<Target>`, which writes [baseDef] plus the located [library]'s
 * `compilerOpts`/`linkerOpts`, and returns that def for `definitionFile`. The lookup runs only when the
 * task executes. Linker options live in the def because cinterop drops DSL `linkerOpts`, and only the
 * def's reach the klib and every binary linking it.
 */
fun Project.hostLibraryDef(
    targetName: String,
    interopName: String,
    baseDef: File,
    library: HostNativeLibrary,
    linkingDef: Provider<RegularFile>? = null,
): Provider<RegularFile> {
    val capitalized = interopName.replaceFirstChar { it.uppercase() } + targetName.replaceFirstChar { it.uppercase() }
    val task = tasks.register("generate${capitalized}Def", GenerateHostLibraryDef::class.java) {
        this.baseDef.set(baseDef)
        this.targetName.set(targetName)
        hostTargetName.set(hostDesktopTargetName)
        displayName.set(library.displayName)
        pkgConfigModule.set(library.pkgConfigModule)
        brewFormula.set(library.brewFormula)
        probeHeader.set(library.probeHeader)
        libs.set(library.libs)
        overrideName.set("-P${library.overrideProperty} or ${library.overrideEnv}")
        prefixOverride.set(
            providers.gradleProperty(library.overrideProperty)
                .orElse(providers.environmentVariable(library.overrideEnv)),
        )
        installHint.set(library.installHint)
        linkingDef?.let { this.linkingDef.set(it) }
        outputDef.set(layout.buildDirectory.file("cinterop-def/$targetName/$interopName.def"))
    }
    return task.flatMap { it.outputDef }
}

abstract class GenerateHostLibraryDef @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val baseDef: RegularFileProperty

    @get:Input abstract val targetName: Property<String>

    /** The one target the lookups below can answer for; any other needs [prefixOverride]. */
    @get:Input @get:Optional
    abstract val hostTargetName: Property<String>

    @get:Input abstract val displayName: Property<String>

    @get:Input abstract val pkgConfigModule: Property<String>

    @get:Input @get:Optional
    abstract val brewFormula: Property<String>

    @get:Input abstract val probeHeader: Property<String>

    @get:Input abstract val libs: ListProperty<String>

    @get:Input abstract val overrideName: Property<String>

    @get:Input @get:Optional
    abstract val prefixOverride: Property<String>

    @get:Input abstract val installHint: Property<String>

    /** Another generated def whose library decides which copy of this one fits (libcurl's TLS backend). */
    @get:InputFile @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val linkingDef: RegularFileProperty

    @get:OutputFile abstract val outputDef: RegularFileProperty

    init {
        // The answer depends on the host (pkg-config, brew, installed packages), which Gradle can't track.
        outputs.upToDateWhen { false }
    }

    private class Found(val source: String, val compilerOpts: List<String>, val linkerOpts: List<String>)

    private class NotStarted(message: String, cause: Throwable) : GradleException(message, cause)

    private fun isMacHost() = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    @TaskAction
    fun generate() {
        val searched = mutableListOf<String>()
        val found = locate(searched) ?: throw GradleException(
            buildString {
                append("${targetName.get()}: ${displayName.get()} not found (needed by ${baseDef.get().asFile.name}).\n")
                append("Searched:\n")
                searched.forEach { append("  - $it\n") }
                append("Install it (${installHint.get()}), or point ${overrideName.get()} at a prefix holding ")
                append("include/${probeHeader.get()} and lib/.")
            },
        )
        logger.info("${targetName.get()}: ${displayName.get()} from ${found.source}")
        val text = buildString {
            append(baseDef.get().asFile.readText().trimEnd())
            append("\n\n# ${displayName.get()} from ${found.source}\n")
            append("compilerOpts = ${found.compilerOpts.joinToString(" ", transform = ::defQuote)}\n")
            append("linkerOpts = ${found.linkerOpts.joinToString(" ", transform = ::defQuote)}\n")
        }
        val out = outputDef.get().asFile
        if (!out.isFile || out.readText() != text) {
            out.parentFile.mkdirs()
            out.writeText(text)
        }
    }

    private fun locate(searched: MutableList<String>): Found? {
        prefixOverride.orNull?.takeIf { it.isNotBlank() }?.let { prefix ->
            // An explicit choice: a wrong one fails rather than quietly falling back to another copy.
            return fromPrefix(File(prefix), "${overrideName.get()}=$prefix")
                ?: throw GradleException(
                    "${targetName.get()}: ${overrideName.get()}=$prefix has no include/${probeHeader.get()} " +
                        "and lib/ under it. Point it at the ${displayName.get()} install prefix.",
                )
        }
        val host = hostTargetName.orNull
        if (targetName.get() != host) {
            // pkg-config, Homebrew and the linking library all answer with the host's copy, which links only for the host.
            throw GradleException(
                "${targetName.get()}: cannot locate ${displayName.get()} for a cross target from this " +
                    "${host ?: System.getProperty("os.name")} host: every lookup finds the host's own copy, which would " +
                    "fail at link time. Point ${overrideName.get()} at a ${displayName.get()} prefix built for " +
                    "${targetName.get()} (include/${probeHeader.get()} and lib/), or build ${targetName.get()} on its own host.",
            )
        }
        searched += "${overrideName.get()}: not set"
        if (linkingDef.isPresent) fromLinkingLibrary(linkingDef.get().asFile, searched)?.let { return it }
        fromPkgConfig(searched)?.let { return it }
        if (isMacHost()) {
            brewFormula.orNull?.let { formula -> fromBrew(formula, searched)?.let { return it } }
        } else {
            searched += "Homebrew: not consulted off macOS"
        }
        return null
    }

    /**
     * The copy the linking def's dylib was built against. Ahead of pkg-config, whose linked copy can be
     * another major version than the one libcurl links (Homebrew openssl@3 vs openssl@4).
     */
    private fun fromLinkingLibrary(def: File, searched: MutableList<String>): Found? {
        val dependency = "lib${libs.get().first()}."
        val label = "the $dependency* that the library of ${def.name} links"
        if (!isMacHost()) {
            searched += "$label: only inspected on macOS"
            return null
        }
        val linkerOpts = def.readLines().firstOrNull { it.startsWith("linkerOpts") }?.substringAfter('=').orEmpty().trim()
        val args = splitDefArgs(linkerOpts)
        val libNames = args.filter { it.startsWith("-l") }.map { it.removePrefix("-l") }
        val dylib = args.filter { it.startsWith("-L") }.map { File(it.removePrefix("-L")) }
            .flatMap { dir -> libNames.map { File(dir, "lib$it.dylib") } }
            .firstOrNull { it.isFile }
        if (dylib == null) {
            searched += "$label: no dylib under its linkerOpts ($linkerOpts)"
            return null
        }
        val otool = run(listOf("otool", "-L", dylib.path), emptyMap()).getOrElse {
            searched += "$label: otool -L $dylib: ${it.message}"
            return null
        }
        val linked = otool.lineSequence().drop(1).map { it.trim().substringBefore(" (") }
            .firstOrNull { File(it).name.startsWith(dependency) }
        if (linked == null || linked.startsWith("@")) {
            searched += "$label: $dylib links ${linked ?: "no $dependency*"}, no absolute prefix to use"
            return null
        }
        val prefix = File(linked).parentFile.parentFile
        fromPrefix(prefix, "the copy ${dylib.name} links ($prefix)")?.let { return it }
        searched += "$label: $prefix (from $dylib) has no include/${probeHeader.get()} and lib/"
        return null
    }

    private fun fromPrefix(prefix: File, source: String): Found? {
        val include = File(prefix, "include")
        val lib = File(prefix, "lib")
        if (!File(include, probeHeader.get()).isFile || !lib.isDirectory) return null
        return Found(
            source = source,
            compilerOpts = listOf("-I${include.absolutePath}"),
            linkerOpts = listOf("-L${lib.absolutePath}") + libs.get().map { "-l$it" },
        )
    }

    private fun fromPkgConfig(searched: MutableList<String>): Found? {
        val binary = System.getenv("PKG_CONFIG")?.takeIf { it.isNotBlank() } ?: "pkg-config"
        val module = pkgConfigModule.get()
        val label = "pkg-config $module (PKG_CONFIG_PATH=${System.getenv("PKG_CONFIG_PATH").orEmpty()})"
        // Kotlin/Native compiles and links against its own sysroot, so the dirs pkg-config normally
        // drops as "system" (/usr/include, /usr/lib/<multiarch>) must be spelled out.
        val env = mapOf("PKG_CONFIG_ALLOW_SYSTEM_CFLAGS" to "1", "PKG_CONFIG_ALLOW_SYSTEM_LIBS" to "1")
        fun query(vararg args: String): Result<String> = run(listOf(binary) + args + module, env)

        val cflags = query("--cflags").getOrElse {
            searched += "$label: ${it.message}"
            return null
        }
        val libsOut = query("--libs").getOrElse {
            searched += "$label: ${it.message}"
            return null
        }
        val libdir = query("--variable=libdir").getOrNull()?.takeIf { it.isNotBlank() }?.let(::File)
        val includedir = query("--variable=includedir").getOrNull()?.takeIf { it.isNotBlank() }?.let(::File)

        if (isMacHost() && libdir?.absolutePath == "/usr/lib") {
            // macOS's own libcurl is built on LibreSSL/SecureTransport: the openssl binding would hand
            // its callbacks a foreign SSL_CTX. Only a separately installed, OpenSSL-backed copy works.
            searched += "$label: only macOS's own copy in /usr/lib, which is not built on OpenSSL"
            return null
        }
        val compilerOpts = splitArgs(cflags).toMutableList()
        val includeDirs = compilerOpts.filter { it.startsWith("-I") }.map { File(it.removePrefix("-I")) }
            .plus(listOfNotNull(includedir))
        val headerDir = includeDirs.firstOrNull { File(it, probeHeader.get()).isFile }
        if (headerDir == null) {
            searched += "$label: no ${probeHeader.get()} under ${includeDirs.joinToString().ifEmpty { "its cflags" }}"
            return null
        }
        if (compilerOpts.none { it == "-I${headerDir.path}" }) compilerOpts += "-I${headerDir.path}"
        // Debian/Ubuntu split headers into <includedir>/<multiarch tuple> (opensslconf.h), which the
        // host compiler searches implicitly and Kotlin/Native's clang does not.
        libdir?.name?.let { tuple ->
            includeDirs.map { File(it, tuple) }.filter { it.isDirectory }.forEach { dir ->
                if (compilerOpts.none { it == "-I${dir.path}" }) compilerOpts += "-I${dir.path}"
            }
        }
        return Found("pkg-config $module", compilerOpts, splitArgs(libsOut))
    }

    private fun fromBrew(formula: String, searched: MutableList<String>): Found? {
        val label = "brew --prefix --installed $formula"
        val env = mapOf("HOMEBREW_NO_AUTO_UPDATE" to "1")
        // PATH first; HOMEBREW_PREFIX covers a daemon started without brew's shellenv on PATH.
        val fallback = System.getenv("HOMEBREW_PREFIX")?.takeIf { it.isNotBlank() }?.let { "$it/bin/brew" }
        var output = run(listOf("brew", "--prefix", "--installed", formula), env)
        if (output.exceptionOrNull() is NotStarted && fallback != null) {
            output = run(listOf(fallback, "--prefix", "--installed", formula), env)
        }
        val prefix = output.getOrElse {
            searched += "$label: ${it.message}"
            return null
        }.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
        if (prefix == null) {
            searched += "$label: printed no prefix"
            return null
        }
        fromPrefix(File(prefix), "Homebrew $formula ($prefix)")?.let { return it }
        searched += "$label: $prefix has no include/${probeHeader.get()} and lib/"
        return null
    }

    /** stdout of [command], or a failure carrying why: not on PATH, or its exit code and stderr. */
    private fun run(command: List<String>, env: Map<String, String>): Result<String> {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val result = try {
            exec.exec {
                commandLine(command)
                environment(env)
                standardOutput = stdout
                errorOutput = stderr
                isIgnoreExitValue = true
            }
        } catch (e: Exception) {
            return Result.failure(NotStarted("could not run ${command.first()} (${e.message})", e))
        }
        if (result.exitValue != 0) {
            val why = stderr.toString().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString(" ").take(300).ifEmpty { "no output" }
            return Result.failure(GradleException("exit ${result.exitValue}: $why"))
        }
        return Result.success(stdout.toString().trim())
    }

    /** pkg-config's output: whitespace-separated, a backslash escapes the next character. */
    private fun splitArgs(text: String): List<String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        for (char in text) {
            when {
                escaped -> current.append(char).also { escaped = false }
                char == '\\' -> escaped = true
                char.isWhitespace() -> if (current.isNotEmpty()) {
                    args += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
        }
        if (current.isNotEmpty()) args += current.toString()
        return args
    }

    private fun splitDefArgs(text: String): List<String> =
        Regex("\"([^\"]*)\"|(\\S+)").findAll(text).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
}

/** One argument of a cinterop `.def`'s `compilerOpts`/`linkerOpts`: the def parser splits on whitespace outside double quotes. */
fun defQuote(arg: String): String = if (arg.none { it.isWhitespace() }) arg else "\"$arg\""
