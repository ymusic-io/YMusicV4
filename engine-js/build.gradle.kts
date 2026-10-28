import com.google.javascript.jscomp.CheckLevel
import com.google.javascript.jscomp.CommandLineRunner
import com.google.javascript.jscomp.CompilationLevel
import com.google.javascript.jscomp.Compiler
import com.google.javascript.jscomp.CompilerOptions
import com.google.javascript.jscomp.DiagnosticGroups
import com.google.javascript.jscomp.SourceFile

// All engine JS in one place, built into single artifacts shipped with the app and remotely
// replaceable as a whole. Pipeline: tsc --noEmit (typecheck) -> esbuild (bundle only, no minify)
// -> Closure Compiler ADVANCED (minify + full property mangling). Closure knows the stdlib via its
// builtin externs, so EVERYTHING internal mangles — identifiers and property names alike; the only
// preserved names are externs.js (the C natives, the __js* transport globals, `versions`) and
// quoted wire keys (Closure never renames quoted properties; the coding standard writes all wire
// objects quoted/bracketed).
//
// Prod artifacts, same pipeline, ADVANCED: engine.js (scrape, src/index.ts) and
// player.js (MSE player, src/player-index.ts). engine-test.js is both + fixtures.

// Closure Compiler (pure-JVM) on the build classpath, only for this module.
buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("com.google.javascript:closure-compiler:v20260629") }
}

plugins {
    base
}

// Each bundle's own revision — Versions.engineJsVersion / playerJsVersion (buildSrc), bumped there
// on every remote push of that bundle. Inlined by esbuild --define as BACKEND_VERSION (`declare
// const` in types.d.ts): the "init" health check reports it; the host logs it on swaps and stamps
// it into error reports. The test bundle carries the engine's.

// Compile-time defines: bare `declare const` identifiers substituted inline at bundle time —
// no runtime global, no extern. PRODUCTION is the same -Pproduction bake as Kotlin
// BuildFlags.PRODUCTION: `debug(function () { return concat; })` does not call the thunk
// when true (esbuild inlines; Closure DCE drops the dead branch).
val production = isProductionBuild()
// Single-quote the string literal: Java's Windows ProcessBuilder loses embedded `"` in argv,
// which esbuild then reads as the bare identifier `engine`. 'engine' is a valid JS string literal
// and survives every layer unchanged.
fun esbuildDefines(bundleName: String, backendVersion: Long) = arrayOf(
    "--define:BUNDLE_NAME='$bundleName'",
    "--define:BACKEND_VERSION=$backendVersion",
    "--define:PRODUCTION=$production",
)
val engineDefines = esbuildDefines("engine", Versions.engineJsVersion)
val playerDefines = esbuildDefines("player", Versions.playerJsVersion)

val srcDir = layout.projectDirectory.dir("src")
val tsconfig = layout.projectDirectory.file("tsconfig.json")
val externsJs = layout.projectDirectory.file("externs.js")
val rawBundleFile = layout.buildDirectory.file("raw/engine.raw.js")
val rawBundleMeta = layout.buildDirectory.file("raw/engine.meta.json")
val rawTestBundleMeta = layout.buildDirectory.file("raw/engine-test.meta.json")
val sizeReportDir = layout.buildDirectory.dir("reports/engine-js")
val bundleFile = layout.buildDirectory.file("dist/engine.js")
val bundleMapFile = layout.buildDirectory.file("dist/engine.js.map")
val rawPlayerBundleFile = layout.buildDirectory.file("raw/player.raw.js")
val rawPlayerBundleMeta = layout.buildDirectory.file("raw/player.meta.json")
val playerBundleFile = layout.buildDirectory.file("dist/player.js")
val playerBundleMapFile = layout.buildDirectory.file("dist/player.js.map")
val testBundleFile = layout.buildDirectory.file("dist/engine-test.js")

private val onWindows = org.gradle.internal.os.OperatingSystem.current().isWindows

// On Windows npx is npx.cmd: ProcessBuilder appends only .exe when it searches PATH, and a .cmd
// would re-parse the whole line through cmd.exe. So run npm's npx-cli.js with node.exe directly.
// (Embedded `"` are lost on Windows either way: ProcessBuilder doesn't escape them, so the C runtime
// strips them; hence esbuildDefines' single quotes.) Resolved when a task runs, so a machine
// without node still configures this project.
fun npxArgv(): List<String> {
    if (!onWindows) return listOf("npx")
    val nodeOnPath = (System.getenv("PATH") ?: "").split(File.pathSeparatorChar)
        .map { File(it.trim('"'), "node.exe") }
        .firstOrNull { it.isFile }
        ?: throw GradleException("node.exe not found on PATH")
    fun npxCli(node: File) = File(node.parentFile, "node_modules/npm/bin/npx-cli.js")
    // A version manager's shim (e.g. Volta) has no npm beside it; the node it launches does.
    val node = nodeOnPath.takeIf { npxCli(it).isFile }
        ?: File(providers.exec { commandLine(nodeOnPath.absolutePath, "-p", "process.execPath") }.standardOutput.asText.get().trim())
    check(npxCli(node).isFile) { "npx-cli.js not found next to ${node.absolutePath}" }
    return listOf(node.absolutePath, npxCli(node).absolutePath)
}

fun Exec.unixPath() {
    if (onWindows) return
    environment(
        "PATH",
        "/opt/homebrew/bin:/usr/local/bin:" + (System.getenv("PATH") ?: "/usr/bin:/bin"),
    )
}

val typecheckJs by tasks.registering(Exec::class) {
    inputs.dir(srcDir)
    inputs.file(tsconfig)
    outputs.file(layout.buildDirectory.file("typecheck.ok"))
    workingDir = projectDir
    unixPath()
    doFirst { commandLine(npxArgv() + listOf("-y", "-p", "typescript@${Versions.typescript}", "tsc", "--noEmit", "-p", ".")) }
    doLast { outputs.files.singleFile.writeText("ok") }
}

// esbuild resolves TS + modules into one plain script. target=esnext ON PURPOSE: lowering would
// rewrite class fields into __publicField(this, "_x", …) — a QUOTED define whose dotted accesses
// Closure then renames apart (a split property = undefined at runtime). Closure parses the native
// syntax fine. No minification here — Closure owns that.
val bundleRawJs by tasks.registering(Exec::class) {
    dependsOn(typecheckJs)
    inputs.dir(srcDir)
    inputs.property("defines", engineDefines.joinToString(" ")) // Exec doesn't track commandLine
    outputs.files(rawBundleFile, rawBundleMeta)
    workingDir = projectDir
    unixPath()
    doFirst {
        commandLine(
            npxArgv() + listOf(
                "-y", "esbuild@${Versions.esbuild}",
                "src/index.ts", "--bundle",
                "--format=iife", "--target=esnext", "--charset=utf8",
                "--loader:.raw.js=text",
                *engineDefines,
                "--metafile=${rawBundleMeta.get().asFile.absolutePath}",
                "--outfile=${rawBundleFile.get().asFile.absolutePath}",
            ),
        )
    }
}

// One Closure invocation, shared by both artifacts. Level ADVANCED = full mangling (production);
// SIMPLE = locals only, globals/properties preserved — the interim level for the test bundle so
// the externally-evaled test snippets keep working while they migrate to fixtures, with Closure's
// parser/emitter still validating the output every build. The fixture-migration end state flips
// the test bundle to ADVANCED too.
// What went INTO the raw bundle, per source directory (esbuild's metafile): the shape of the
// growth. Closure's output has no module boundaries left, so the breakdown can only be measured
// before it — the ratio to the final size is what it is, but the ranking survives minification.
fun sourceBreakdown(meta: java.io.File): List<Pair<String, Long>> {
    @Suppress("UNCHECKED_CAST")
    val json = groovy.json.JsonSlurper().parse(meta) as Map<String, Any>

    @Suppress("UNCHECKED_CAST")
    val outputs = (json["outputs"] as Map<String, Map<String, Any>>).values
        .first { (it["inputs"] as? Map<*, *>)?.isNotEmpty() == true }

    @Suppress("UNCHECKED_CAST")
    val inputs = outputs["inputs"] as Map<String, Map<String, Any>>
    return inputs.entries
        .groupBy { it.key.substringBeforeLast('/') }
        .map { (dir, entries) -> dir to entries.sumOf { (it.value["bytesInOutput"] as Number).toLong() } }
        .sortedByDescending { it.second }
}

fun reportSize(out: java.io.File, meta: java.io.File) {
    val size = out.length()
    val report = buildString {
        appendLine("${out.name}: $size bytes")
        sourceBreakdown(meta).forEach { (dir, bytes) -> appendLine("  %7d  %s".format(bytes, dir)) }
        appendLine("  (pre-Closure source bytes, from esbuild's metafile)")
    }
    sizeReportDir.get().asFile.apply { mkdirs() }.resolve("${out.name}.size.txt").writeText(report)
    logger.lifecycle(report.trimEnd())
}

fun closureCompile(raw: java.io.File, out: java.io.File, map: java.io.File, meta: java.io.File, level: CompilationLevel) {
    val options = CompilerOptions().apply {
        setLanguageIn(CompilerOptions.LanguageMode.ECMASCRIPT_NEXT)
        setLanguageOut(CompilerOptions.LanguageMode.NO_TRANSPILE)
        // CUSTOM = pure-ECMAScript builtin externs (no DOM) — QuickJS has no browser surface,
        // and anything not in externs is fair game for renaming.
        setEnvironment(CompilerOptions.Environment.CUSTOM)
        sourceMapOutputPath = map.absolutePath
    }
    level.setOptionsForCompilationLevel(options)
    // ADVANCED enables quoted->dotted normalization, which would feed the QUOTED wire keys into
    // the renamer and corrupt every JSON contract with the host. Quoted must stay quoted.
    options.setConvertToDottedProperties(false)
    // esnext class fields (assigned via field syntax) false-positive this check en masse;
    // renaming stays consistent regardless. Undefined-variable checks (missing externs) remain.
    options.setWarningLevel(DiagnosticGroups.MISSING_PROPERTIES, CheckLevel.OFF)
    // The input is TS with types ERASED (~64% inferable): the type-BASED property passes split
    // one runtime property into several names on bad inference (observed: _headers in build()).
    // Name-based renaming (same name -> same rename, everywhere) is the sound mode here.
    options.setDisambiguateProperties(false)
    options.setAmbiguateProperties(false)
    options.setInlineProperties(false)
    val externs = CommandLineRunner.getBuiltinExterns(options.environment) +
        SourceFile.fromFile(externsJs.asFile.absolutePath)
    val compiler = Compiler()
    val result = compiler.compile(externs, listOf(SourceFile.fromFile(raw.absolutePath)), options)
    // Warnings surface loudly: an undeclared name here usually means a missing extern — under
    // ADVANCED that is a future runtime breakage, never noise.
    result.warnings.forEach { logger.warn("closure: $it") }
    if (!result.success) {
        throw GradleException("closure $level failed:\n" + result.errors.joinToString("\n"))
    }
    out.parentFile.mkdirs()
    val minified = compiler.toSource()
    check(minified.isNotBlank()) { "closure produced empty output" }
    out.writeText(minified + "\n//# sourceMappingURL=${map.name}\n")
    map.writer().use { compiler.sourceMap.appendTo(it, out.name) }
    reportSize(out, meta)
}

val closureJs by tasks.registering {
    dependsOn(bundleRawJs)
    inputs.file(rawBundleFile)
    inputs.file(externsJs)
    outputs.files(bundleFile, bundleMapFile)
    doLast {
        closureCompile(
            rawBundleFile.get().asFile,
            bundleFile.get().asFile,
            bundleMapFile.get().asFile,
            rawBundleMeta.get().asFile,
            CompilationLevel.ADVANCED_OPTIMIZATIONS,
        )
    }
}

val bundleRawPlayerJs by tasks.registering(Exec::class) {
    dependsOn(typecheckJs)
    inputs.dir(srcDir)
    inputs.property("defines", playerDefines.joinToString(" "))
    outputs.files(rawPlayerBundleFile, rawPlayerBundleMeta)
    workingDir = projectDir
    unixPath()
    doFirst {
        commandLine(
            npxArgv() + listOf(
                "-y", "esbuild@${Versions.esbuild}",
                "src/player-index.ts", "--bundle",
                "--format=iife", "--target=esnext", "--charset=utf8",
                "--loader:.raw.js=text",
                *playerDefines,
                "--metafile=${rawPlayerBundleMeta.get().asFile.absolutePath}",
                "--outfile=${rawPlayerBundleFile.get().asFile.absolutePath}",
            ),
        )
    }
}

val closurePlayerJs by tasks.registering {
    dependsOn(bundleRawPlayerJs)
    inputs.file(rawPlayerBundleFile)
    inputs.file(externsJs)
    outputs.files(playerBundleFile, playerBundleMapFile)
    doLast {
        closureCompile(
            rawPlayerBundleFile.get().asFile,
            playerBundleFile.get().asFile,
            playerBundleMapFile.get().asFile,
            rawPlayerBundleMeta.get().asFile,
            CompilationLevel.ADVANCED_OPTIMIZATIONS,
        )
    }
}

// The TEST bundle: production + fixtures, same pipeline and same ADVANCED level — tests run on
// output as mangled as production's. The few remaining load() snippets touch only externed
// natives, which is exactly what survives.
val rawTestBundleFile = layout.buildDirectory.file("raw/engine-test.raw.js")
val testBundleMapFile = layout.buildDirectory.file("dist/engine-test.js.map")

val bundleRawTestJs by tasks.registering(Exec::class) {
    dependsOn(typecheckJs)
    inputs.dir(srcDir)
    inputs.property("defines", engineDefines.joinToString(" ")) // Exec doesn't track commandLine
    outputs.files(rawTestBundleFile, rawTestBundleMeta)
    workingDir = projectDir
    unixPath()
    doFirst {
        commandLine(
            npxArgv() + listOf(
                "-y", "esbuild@${Versions.esbuild}",
                "src/test/index.ts", "--bundle",
                "--format=iife", "--target=esnext", "--charset=utf8",
                "--loader:.raw.js=text",
                *engineDefines,
                "--metafile=${rawTestBundleMeta.get().asFile.absolutePath}",
                "--outfile=${rawTestBundleFile.get().asFile.absolutePath}",
            ),
        )
    }
}

val bundleTestJs by tasks.registering {
    dependsOn(bundleRawTestJs)
    inputs.file(rawTestBundleFile)
    inputs.file(externsJs)
    outputs.files(testBundleFile, testBundleMapFile)
    doLast {
        closureCompile(
            rawTestBundleFile.get().asFile,
            testBundleFile.get().asFile,
            testBundleMapFile.get().asFile,
            rawTestBundleMeta.get().asFile,
            CompilationLevel.ADVANCED_OPTIMIZATIONS,
        )
    }
}

tasks.named("assemble") { dependsOn(closureJs, closurePlayerJs, bundleTestJs) }

// Consumable artifacts: engineJs = scrape; enginePlayerJs = MSE player;
// engineTestJs = both + fixtures.
val engineJs by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
val enginePlayerJs by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
val engineTestJs by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts {
    add(engineJs.name, bundleFile) { builtBy(closureJs) }
    add(enginePlayerJs.name, playerBundleFile) { builtBy(closurePlayerJs) }
    add(engineTestJs.name, testBundleFile) { builtBy(bundleTestJs) }
}
