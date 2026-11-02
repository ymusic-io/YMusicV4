package io.ymusic.ui

import io.ymusic.dynamicengine.JsConfigProvider
import io.ymusic.dynamicengine.SignedBundleRef
import io.ymusic.remoteconfig.BundleVersion
import io.ymusic.remoteconfig.RemoteConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One JS bundle's view of the remote config — [bundle] is [RemoteConfig.engineJs] or [RemoteConfig.playerJs]. */
fun RemoteConfig.bundleConfig(bundle: Flow<BundleVersion?>): JsConfigProvider {
    val remoteConfig = this
    return object : JsConfigProvider {
        override val published: Flow<SignedBundleRef?> = bundle.map { version ->
            version?.let { SignedBundleRef(it.version, it.url, it.signature) }
        }

        override fun checkUpdate(acceptMaxAgeMs: Long) = remoteConfig.checkUpdate(acceptMaxAgeMs)
    }
}
