package io.ymusic.android

import android.webkit.CookieManager
import android.webkit.WebSettings
import io.ymusic.ui.SessionCookie
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * A [CookieJar] backed by the Android WebView [CookieManager] — the single cookie store shared by
 * the scraping engine's HTTP, the MSE YouTube player, and the login WebView. So the anonymous
 * visitor identity (VISITOR_INFO1_LIVE / YSC / PREF / CONSENT / SOCS) the player earns while playing
 * is the same one the InnerTube calls send, and vice versa — and it persists across launches for
 * free (the WebView store is on disk).
 *
 * On Android `kmphttp.CookieJar` *is* `okhttp3.CookieJar` and `kmphttp.Cookie` *is* `okhttp3.Cookie`,
 * so this plugs straight into `KmpHttpClient.create { cookieJar = … }` and the engine's shared jar.
 */
class WebViewCookieJar(private val webViewMajorVersion: Int?) : CookieJar {

    private val manager: CookieManager = CookieManager.getInstance().apply { setAcceptCookie(true) }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val u = url.toString()
        for (cookie in cookies) manager.setCookie(u, setCookieString(cookie))
        manager.flush()
    }

    /**
     * Chromium 51-66 silently drops any cookie marked `SameSite=None` (every YouTube identity and
     * sign-in cookie), and un-updated Android 9 head units still run WebView 66, so on those the
     * attribute is left out. Newer WebViews keep it: without it a cookie defaults to Lax, which the
     * login WebView's cross-site requests need it not to be.
     */
    private fun setCookieString(cookie: Cookie): String = buildString {
        append(cookie.name).append('=').append(cookie.value)
        if (cookie.persistent) append("; max-age=").append(((cookie.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0))
        if (!cookie.hostOnly) append("; domain=").append(cookie.domain)
        append("; path=").append(cookie.path)
        if (cookie.secure) append("; secure")
        if (cookie.httpOnly) append("; httponly")
        val sameSite = cookie.sameSite
        if (sameSite != null && (webViewMajorVersion ?: Int.MAX_VALUE) >= 67) append("; samesite=").append(sameSite)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        // getCookie returns the request-header form "name=value; name2=value2" (no attributes),
        // already scoped to the URL by the store; rebuild Cookie objects for okhttp to emit.
        val header = manager.getCookie(url.toString()) ?: return emptyList()
        return header.split(';').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()
            if (name.isEmpty()) null
            else Cookie.Builder().name(name).value(value).domain(url.host).build()
        }
    }

    fun exportSessionCookies(): List<SessionCookie> {
        val seen = LinkedHashMap<Pair<String, String>, SessionCookie>()
        fun take(url: String, domain: String) {
            val header = manager.getCookie(url) ?: return
            for (pair in header.split(';')) {
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                val name = pair.substring(0, eq).trim()
                val value = pair.substring(eq + 1).trim()
                if (name.isEmpty()) continue
                seen[name to domain] = SessionCookie(
                    name = name,
                    value = value,
                    domain = domain,
                    secure = true,
                    httpOnly = true,
                )
            }
        }
        take("https://www.youtube.com/", "youtube.com")
        take("https://m.youtube.com/", "youtube.com")
        take("https://youtube.com/", "youtube.com")
        take("https://accounts.youtube.com/", "youtube.com")
        take("https://www.google.com/", "google.com")
        take("https://accounts.google.com/", "google.com")
        return seen.values.toList()
    }
}

/** The system WebView's Chromium version ("66.0.3359.158") from its default user agent; null when unreadable. */
fun webViewChromiumVersion(context: android.content.Context): String? =
    Regex("""Chrome/([\d.]+)""").find(WebSettings.getDefaultUserAgent(context))?.groupValues?.get(1)
