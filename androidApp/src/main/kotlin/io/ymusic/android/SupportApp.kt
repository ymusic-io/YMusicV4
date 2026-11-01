package io.ymusic.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import kmpcommon.log.Logger

/**
 * The "Support Development" app, sold on Google Play: having it installed is premium, and it hosts
 * the donation screen. Nothing is verified — installed is enough.
 */
internal object SupportApp {
    const val PACKAGE = "com.kdev.ymusic.support"

    /** Its DonationActivity's action (YMusicLegacy's ACTION_SHOW_DONATION_DIALOG). */
    private const val ACTION_DONATE = "io.ymusic.android.plugin.ACTION_4"

    fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Its donation screen; or, when it is missing or too old to have one, [fallbackUrl] if the remote
     * config sets one, else its Play Store page.
     * Started from [activity], in YMusic's own task: the dialog is translucent and sits over the app.
     */
    fun openDonation(activity: Activity, fallbackUrl: String?, logger: Logger?) {
        // Its intent filter has no CATEGORY_DEFAULT, so neither resolveActivity nor an implicit
        // start finds it: query without that requirement and start it by class name.
        val donation = activity.packageManager.queryIntentActivities(Intent(ACTION_DONATE).setPackage(PACKAGE), 0).firstOrNull()
        if (donation != null) {
            try {
                activity.startActivity(Intent(ACTION_DONATE).setClassName(donation.activityInfo.packageName, donation.activityInfo.name))
                return
            } catch (error: ActivityNotFoundException) {
                logger?.fork("donation")?.log(Logger.Level.WARN, "the donation screen failed to start", error)
            }
        }
        if (fallbackUrl != null) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)))
                return
            } catch (error: ActivityNotFoundException) {
                logger?.fork("donation-url", mapOf("url" to fallbackUrl))
                    ?.log(Logger.Level.WARN, "nothing opens the donation url; falling back to the store page", error)
            }
        }
        val opened = activity.startFirstActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE")),
        )
        if (!opened) logger?.fork("donation")?.log(Logger.Level.WARN, "neither a store app nor a browser opens the store page")
    }
}
