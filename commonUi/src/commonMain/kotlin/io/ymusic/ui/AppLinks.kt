package io.ymusic.ui

import io.ymusic.remoteconfig.RemoteConfigData

/** The app's links and contact address: the remote config's value when it sends one, else the built-in one. */
internal class AppLinks(config: RemoteConfigData?) {
    val website: String = config?.features?.websiteUrl ?: "https://ymusic.io"
    val telegram: String = config?.features?.telegramUrl ?: "https://t.me/ymusic_io"
    val feedbackEmail: String = config?.features?.feedbackEmail ?: "feedback@ymusic.io"
    val feedbackIssue: String = config?.features?.feedbackIssueUrl ?: "https://github.com/ymusic-io/YMusicV4/issues/new"
    val sourceCode: String = config?.features?.sourceCodeUrl ?: "https://github.com/ymusic-io/YMusicV4"
    val terms: String = config?.features?.termsUrl ?: "https://github.com/ymusic-io/YMusicV4/blob/master/TERMS.md"
    val changelog: String = config?.features?.changelogUrl ?: "https://github.com/ymusic-io/YMusicV4/blob/master/CHANGELOG.md"
}
