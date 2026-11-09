package app.shell

/**
 * Every place the product name shows up, derived from gradle.properties. The desktop skill
 * has the same values in its config.py; change both together.
 */
object Brand {
    val NAME: String = BuildConfig.BRAND_NAME

    /** QR links look like <SCHEME>://link?v=1&id=...&k=... */
    val SCHEME: String = BuildConfig.BRAND_SCHEME

    /** Each mini-app is served from its own origin, https://<folder>.<ORIGIN_SUFFIX>/ */
    val ORIGIN_SUFFIX: String = "${BuildConfig.BRAND_SCHEME}.internal"

    /** Advertised on the LAN while the app is open, so a linked computer can find the phone. */
    val NSD_TYPE: String = "_${BuildConfig.BRAND_SCHEME.take(14)}._tcp"

    const val DEFAULT_PORT = 8080
}
