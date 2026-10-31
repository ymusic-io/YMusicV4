package io.ymusic.remoteconfig

import io.ymusic.remoteconfig.AppUpdate.Companion.value
import io.ymusic.remoteconfig.BundleVersion.Companion.value
import kmphttp.json.JsonWriter
import kotlinx.serialization.json.JsonObject
import projectcommon.json.boolOrNull
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
 * The answer from `GET /v1/config`.
 *
 * Every field has a default and unknown ones are skipped, so the service can add a flag without
 * breaking a client that predates it, and a client can be built against a field the deployed service
 * does not send yet. That is the whole point of the defaults — do not remove them to make a field
 * "required".
 */
data class RemoteConfigData(
    val variableA: Int = 0,
    val featureX: Boolean = false,
    /** The drawer's "Telegram announcements" link; null = the app's built-in one. */
    val telegramUrl: String? = null,
    /** Settings › About's "Changelog" link; null hides the row (the "updated" notice uses the app's built-in one). */
    val changelogUrl: String? = null,
    /** The drawer header's website link; null = the app's built-in one. */
    val websiteUrl: String? = null,
    /** The Feedback screen's email address; null = the app's built-in one. */
    val feedbackEmail: String? = null,
    /** The Feedback screen's "new issue" page (the info is added as a `body` query parameter); null = the app's built-in one. */
    val feedbackIssueUrl: String? = null,
    /** Settings › About's "Source code" link; null = the app's built-in one. */
    val sourceCodeUrl: String? = null,
    /** Settings › About's "Terms" link; null = the app's built-in one. */
    val termsUrl: String? = null,
    /** Where "Donate" opens when the supporter app isn't installed; null = its store page. */
    val donationUrl: String? = null,
    /** The official download site, where a failed or impossible in-app update sends the user. */
    val downloadPageUrl: String? = null,
    val appUpdate: AppUpdate? = null,
    val engineJs: BundleVersion? = null,
    val playerJs: BundleVersion? = null,
    /** How many of the newest log lines an error event sends along; null = the app's default, 0 = none. */
    val errorLogLines: Int? = null,
    // client populated timestamp at time data parsed from server
    val updatedAt: Long,
) {
    companion object {
        /** What the app runs on before the first successful fetch, and with no cache to fall back on. */
        val DEFAULTS = RemoteConfigData(updatedAt = 0L)
        private const val KEY_FEATURES = "features"
        private const val KEY_VARIABLE_A = "variableA"
        private const val KEY_FEATURE_X = "featureX"
        private const val KEY_TELEGRAM_URL = "telegramUrl"
        private const val KEY_CHANGELOG_URL = "changelogUrl"
        private const val KEY_WEBSITE_URL = "websiteUrl"
        private const val KEY_FEEDBACK_EMAIL = "feedbackEmail"
        private const val KEY_FEEDBACK_ISSUE_URL = "feedbackIssueUrl"
        private const val KEY_SOURCE_CODE_URL = "sourceCodeUrl"
        private const val KEY_TERMS_URL = "termsUrl"
        private const val KEY_DONATION_URL = "donationUrl"
        private const val KEY_DOWNLOAD_PAGE_URL = "downloadPageUrl"
        private const val KEY_APP_UPDATE = "appUpdate"
        private const val KEY_ENGINE_JS = "engineJs"
        private const val KEY_PLAYER_JS = "playerJs"
        private const val KEY_TELEMETRY = "telemetry"
        private const val KEY_ERROR_LOG_LINES = "errorLogLines"
        private const val KEY_UPDATED_AT = "updatedAt"

        /**
         * [KEY_UPDATED_AT] is when the app last heard from the service, and it is what the throttle
         * and [RemoteConfig.checkUpdate]'s max-age compare against.
         *
         * The cache carries it, so a restart knows how old its copy is. A server response does not,
         * so [now] stamps it. If a server ever DOES send it, it wins — which is worth knowing, since
         * that hands it the ability to pin clients to a document it declares perpetually fresh.
         */
        fun from(root: JsonObject, now: () -> Long): RemoteConfigData {
            val features = root.objectOrNull(KEY_FEATURES)
            return RemoteConfigData(
                variableA = features?.intOrNull(KEY_VARIABLE_A) ?: DEFAULTS.variableA,
                featureX = features?.boolOrNull(KEY_FEATURE_X) ?: DEFAULTS.featureX,
                telegramUrl = features?.stringOrNull(KEY_TELEGRAM_URL),
                changelogUrl = features?.stringOrNull(KEY_CHANGELOG_URL),
                websiteUrl = features?.stringOrNull(KEY_WEBSITE_URL),
                feedbackEmail = features?.stringOrNull(KEY_FEEDBACK_EMAIL),
                feedbackIssueUrl = features?.stringOrNull(KEY_FEEDBACK_ISSUE_URL),
                sourceCodeUrl = features?.stringOrNull(KEY_SOURCE_CODE_URL),
                termsUrl = features?.stringOrNull(KEY_TERMS_URL),
                donationUrl = features?.stringOrNull(KEY_DONATION_URL),
                downloadPageUrl = features?.stringOrNull(KEY_DOWNLOAD_PAGE_URL),
                appUpdate = root.objectOrNull(KEY_APP_UPDATE)?.let(AppUpdate::from),
                engineJs = root.objectOrNull(KEY_ENGINE_JS)?.let(BundleVersion::from),
                playerJs = root.objectOrNull(KEY_PLAYER_JS)?.let(BundleVersion::from),
                errorLogLines = root.objectOrNull(KEY_TELEMETRY)?.intOrNull(KEY_ERROR_LOG_LINES),
                updatedAt = root.longOrNull(KEY_UPDATED_AT) ?: now(),
            )
        }

        fun JsonWriter.value(data: RemoteConfigData): JsonWriter {
            beginObject()
            name(KEY_FEATURES).beginObject()
            name(KEY_VARIABLE_A).value(data.variableA)
            name(KEY_FEATURE_X).value(data.featureX)
            data.telegramUrl?.let { name(KEY_TELEGRAM_URL).value(it) }
            data.changelogUrl?.let { name(KEY_CHANGELOG_URL).value(it) }
            data.websiteUrl?.let { name(KEY_WEBSITE_URL).value(it) }
            data.feedbackEmail?.let { name(KEY_FEEDBACK_EMAIL).value(it) }
            data.feedbackIssueUrl?.let { name(KEY_FEEDBACK_ISSUE_URL).value(it) }
            data.sourceCodeUrl?.let { name(KEY_SOURCE_CODE_URL).value(it) }
            data.termsUrl?.let { name(KEY_TERMS_URL).value(it) }
            data.donationUrl?.let { name(KEY_DONATION_URL).value(it) }
            data.downloadPageUrl?.let { name(KEY_DOWNLOAD_PAGE_URL).value(it) }
            endObject()
            data.appUpdate?.let { name(KEY_APP_UPDATE).value(it) }
            data.engineJs?.let { name(KEY_ENGINE_JS).value(it) }
            data.playerJs?.let { name(KEY_PLAYER_JS).value(it) }
            data.errorLogLines?.let {
                name(KEY_TELEMETRY).beginObject()
                name(KEY_ERROR_LOG_LINES).value(it)
                endObject()
            }
            // Persisted so a restart knows how old this is, rather than refetching on every launch.
            name(KEY_UPDATED_AT).value(data.updatedAt)
            endObject()
            return this
        }
    }
}
