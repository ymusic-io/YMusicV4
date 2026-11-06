package io.ymusic.remoteconfig

import io.ymusic.remoteconfig.AppUpdate.Companion.value
import io.ymusic.remoteconfig.BundleVersion.Companion.value
import io.ymusic.remoteconfig.Features.Companion.value
import kmphttp.json.JsonWriter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import projectcommon.json.arrayOrNull
import projectcommon.json.boolOrNull
import projectcommon.json.doubleOrNull
import projectcommon.json.int
import projectcommon.json.intOrNull
import projectcommon.json.long
import projectcommon.json.longOrNull
import projectcommon.json.objectOrNull
import projectcommon.json.string
import projectcommon.json.stringOrNull

/**
 * A JS bundle the service publishes. [version] is compared against the revision already on disk;
 * [signature] authenticates the file at [url] for that version (`verifyBundleSignature`) — this
 * document arrives unsigned and is cached on disk, so it is never trusted on its own.
 */
data class BundleVersion(val version: Long, val url: String, val signature: String) {
    companion object {
        private const val KEY_VERSION = "version"
        private const val KEY_URL = "url"
        private const val KEY_SIGNATURE = "signature"

        /** Strict: half an entry is a publishing bug, not a value to guess at. */
        fun from(json: JsonObject): BundleVersion = BundleVersion(
            version = json.long(KEY_VERSION),
            url = json.string(KEY_URL),
            signature = json.string(KEY_SIGNATURE),
        )

        fun JsonWriter.value(data: BundleVersion): JsonWriter {
            beginObject()
            name(KEY_VERSION).value(data.version)
            name(KEY_URL).value(data.url)
            name(KEY_SIGNATURE).value(data.signature)
            endObject()
            return this
        }
    }
}

/**
 * A newer app build for this platform. [sha256] is of the installer at [url] (an APK, DMG, MSI or
 * DEB). [signature] authenticates it: ECDSA P-256 over `app-<platform>:<versionCode>:<sha256>`
 * with the update signing key — desktop refuses an installer without a valid one; Android leaves
 * that to the OS, which only installs an APK signed with the installed app's certificate.
 */
data class AppUpdate(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val url: String,
    val size: Long,
    val sha256: String,
    val signature: String,
    /** The full release notes, behind the dialog's "Full changelog". */
    val changelogUrl: String? = null,
    /** The web page "Download" opens when [installInApp] is false. */
    val downloadPage: String? = null,
    /**
     * Whether this build can upgrade the installed one in place. False when it cannot be installed
     * over it (e.g. signed with another key): the dialog then sends the user to [downloadPage].
     */
    val installInApp: Boolean = true,
) {
    companion object {
        private const val KEY_VERSION_CODE = "versionCode"
        private const val KEY_VERSION_NAME = "versionName"
        private const val KEY_NOTES = "notes"
        private const val KEY_URL = "url"
        private const val KEY_SIZE = "size"
        private const val KEY_SHA256 = "sha256"
        private const val KEY_SIGNATURE = "signature"
        private const val KEY_CHANGELOG_URL = "changelogUrl"
        private const val KEY_DOWNLOAD_PAGE = "downloadPage"
        private const val KEY_INSTALL_IN_APP = "installInApp"

        fun from(json: JsonObject): AppUpdate = AppUpdate(
            versionCode = json.int(KEY_VERSION_CODE),
            versionName = json.string(KEY_VERSION_NAME),
            notes = json.stringOrNull(KEY_NOTES).orEmpty(),
            url = json.string(KEY_URL),
            size = json.long(KEY_SIZE),
            sha256 = json.string(KEY_SHA256).lowercase(),
            signature = json.stringOrNull(KEY_SIGNATURE).orEmpty(),
            changelogUrl = json.stringOrNull(KEY_CHANGELOG_URL),
            downloadPage = json.stringOrNull(KEY_DOWNLOAD_PAGE),
            installInApp = json.boolOrNull(KEY_INSTALL_IN_APP) ?: true,
        )

        fun JsonWriter.value(data: AppUpdate): JsonWriter {
            beginObject()
            name(KEY_VERSION_CODE).value(data.versionCode)
            name(KEY_VERSION_NAME).value(data.versionName)
            name(KEY_NOTES).value(data.notes)
            name(KEY_URL).value(data.url)
            name(KEY_SIZE).value(data.size)
            name(KEY_SHA256).value(data.sha256)
            name(KEY_SIGNATURE).value(data.signature)
            data.changelogUrl?.let { name(KEY_CHANGELOG_URL).value(it) }
            data.downloadPage?.let { name(KEY_DOWNLOAD_PAGE).value(it) }
            name(KEY_INSTALL_IN_APP).value(data.installInApp)
            endObject()
            return this
        }
    }
}

/**
 * The service's `features` object, every key kept as sent so it survives the disk cache and engine JS
 * can read it through [getByKey]. Each value this build uses is a property here, with its default;
 * see [RemoteConfigData].
 */
class Features(private val flags: JsonObject = EMPTY_JSON) {
    val variableA: Int get() = flags.intOrNull("variableA") ?: 0
    val featureX: Boolean get() = flags.boolOrNull("featureX") ?: false

    /** The drawer's "Telegram announcements" link; null = the app's built-in one. */
    val telegramUrl: String? get() = flags.stringOrNull("telegramUrl")

    /** Settings › About's "Changelog" link; null hides the row (the "updated" notice uses the app's built-in one). */
    val changelogUrl: String? get() = flags.stringOrNull("changelogUrl")

    /** The drawer header's website link; null = the app's built-in one. */
    val websiteUrl: String? get() = flags.stringOrNull("websiteUrl")

    /** The Feedback screen's email address; null = the app's built-in one. */
    val feedbackEmail: String? get() = flags.stringOrNull("feedbackEmail")

    /** The Feedback screen's "new issue" page (the info is added as a `body` query parameter); null = the app's built-in one. */
    val feedbackIssueUrl: String? get() = flags.stringOrNull("feedbackIssueUrl")

    /** Settings › About's "Source code" link; null = the app's built-in one. */
    val sourceCodeUrl: String? get() = flags.stringOrNull("sourceCodeUrl")

    /** Settings › About's "Terms" link; null = the app's built-in one. */
    val termsUrl: String? get() = flags.stringOrNull("termsUrl")

    /** Where "Donate" opens when the supporter app isn't installed; null = its store page. */
    val donationUrl: String? get() = flags.stringOrNull("donationUrl")

    /** The official download site, where a failed or impossible in-app update sends the user. */
    val downloadPageUrl: String? get() = flags.stringOrNull("downloadPageUrl")

    /** How many of the newest log lines an error event sends along; 0 = none. */
    val errorLogLines: Int get() = flags.intOrNull("errorLogLines") ?: 500

    /** How many lines the app log keeps (trimmed once 10% over). */
    val logLines: Int get() = flags.intOrNull("logLines") ?: 5_000

    /** The share of non-fatal errors (`captureError`) sent to Sentry, 0–1; crashes always go. */
    val errorSampleRate: Double get() = flags.doubleOrNull("errorSampleRate") ?: 1.0

    /** Regexes over an error's "Type: message"; a match keeps a non-fatal error in the local log only. */
    val errorFilters: List<String>
        get() = flags.arrayOrNull("errorFilters").orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }

    /** Whether playback reads and fills the player cache; downloads still reuse what it holds. */
    val playerCacheEnabled: Boolean get() = flags.boolOrNull("playerCacheEnabled") ?: true

    fun getByKey(key: String): JsonElement? = flags[key]

    override fun equals(other: Any?): Boolean = other is Features && flags == other.flags

    override fun hashCode(): Int = flags.hashCode()

    override fun toString(): String = "Features($flags)"

    companion object {
        fun JsonWriter.value(data: Features): JsonWriter = element(data.flags)
    }
}

private val EMPTY_JSON = JsonObject(emptyMap())

private fun JsonWriter.element(element: JsonElement): JsonWriter {
    when (element) {
        is JsonObject -> {
            beginObject()
            element.forEach { (key, child) -> name(key).element(child) }
            endObject()
        }
        is JsonArray -> {
            beginArray()
            element.forEach { element(it) }
            endArray()
        }
        JsonNull -> nullValue()
        is JsonPrimitive -> when {
            element.isString -> value(element.content)
            element.booleanOrNull != null -> value(element.boolean)
            element.longOrNull != null -> value(element.long)
            else -> value(element.double)
        }
    }
    return this
}

/**
 * The answer from `GET /v1/config`, in its wire shape.
 *
 * Every field has a default and unknown ones are skipped, so the service can add a flag without
 * breaking a client that predates it, and a client can be built against a field the deployed service
 * does not send yet. That is the whole point of the defaults — do not remove them to make a field
 * "required".
 */
data class RemoteConfigData(
    val features: Features = Features(),
    val appUpdate: AppUpdate? = null,
    val engineJs: BundleVersion? = null,
    val playerJs: BundleVersion? = null,
    /** Tags this client puts on every Sentry event while the service sends them. */
    val sentryTags: Map<String, String> = emptyMap(),
    // client populated timestamp at time data parsed from server
    val updatedAt: Long = 0L,
) {
    companion object {
        private const val KEY_FEATURES = "features"
        private const val KEY_APP_UPDATE = "appUpdate"
        private const val KEY_ENGINE_JS = "engineJs"
        private const val KEY_PLAYER_JS = "playerJs"
        private const val KEY_SENTRY_TAGS = "sentryTags"
        private const val KEY_UPDATED_AT = "updatedAt"

        /**
         * [KEY_UPDATED_AT] is when the app last heard from the service, and it is what the throttle
         * and [RemoteConfig.checkUpdate]'s max-age compare against.
         *
         * The cache carries it, so a restart knows how old its copy is. A server response does not,
         * so [now] stamps it. If a server ever DOES send it, it wins — which is worth knowing, since
         * that hands it the ability to pin clients to a document it declares perpetually fresh.
         */
        fun from(root: JsonObject, now: () -> Long): RemoteConfigData = RemoteConfigData(
            features = root.objectOrNull(KEY_FEATURES)?.let(::Features) ?: Features(),
            appUpdate = root.objectOrNull(KEY_APP_UPDATE)?.let(AppUpdate::from),
            engineJs = root.objectOrNull(KEY_ENGINE_JS)?.let(BundleVersion::from),
            playerJs = root.objectOrNull(KEY_PLAYER_JS)?.let(BundleVersion::from),
            sentryTags = root.objectOrNull(KEY_SENTRY_TAGS).orEmpty().filterValues { it !is JsonNull }
                .mapValues { (_, tag) -> (tag as? JsonPrimitive)?.content ?: tag.toString() },
            updatedAt = root.longOrNull(KEY_UPDATED_AT) ?: now(),
        )

        fun JsonWriter.value(data: RemoteConfigData): JsonWriter {
            beginObject()
            name(KEY_FEATURES).value(data.features)
            data.appUpdate?.let { name(KEY_APP_UPDATE).value(it) }
            data.engineJs?.let { name(KEY_ENGINE_JS).value(it) }
            data.playerJs?.let { name(KEY_PLAYER_JS).value(it) }
            name(KEY_SENTRY_TAGS).beginObject()
            data.sentryTags.forEach { (key, tag) -> name(key).value(tag) }
            endObject()
            // Persisted so a restart knows how old this is, rather than refetching on every launch.
            name(KEY_UPDATED_AT).value(data.updatedAt)
            endObject()
            return this
        }
    }
}
