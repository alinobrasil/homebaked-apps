package app.shell

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One installed mini-app, as described by its folder and optional manifest.json:
 *
 *   {
 *     "name": "Weather",                   // tile label, defaults to the folder name
 *     "category": "Utilities",             // launcher section, defaults to Games
 *     "version": "3",                      // shown in the info sheet, optional
 *     "emoji": "⛅",                        // tile glyph when there is no icon.png
 *     "color": "#1098AD",                  // tile background when there is no icon.png
 *     "network": ["api.open-meteo.com"],   // https hosts it may reach; none = offline
 *     "shared": ["rewards"],               // shared storage buckets it may read and write
 *     "permissions": ["photos", "location"] // phone abilities it may use, see Abilities
 *   }
 *
 * The folder name is also the app's origin host, so Installer.sanitize keeps it to
 * lowercase letters, digits and hyphens.
 */
data class AppInfo(
    val folder: String,
    val dir: File,
    val name: String,
    val category: String,
    val version: String,
    val emoji: String,
    val color: Long?,
    val network: List<String>,
    val shared: List<String>,
    val permissions: List<String> = emptyList()
) {
    val host: String get() = "$folder.${Brand.ORIGIN_SUFFIX}"
    val origin: String get() = "https://$host"
    val offline: Boolean get() = network.isEmpty()

    /** Exact host, or a "*.example.com" entry covering any subdomain of example.com. */
    fun allowsHost(host: String): Boolean {
        val h = host.lowercase()
        return network.any { rule ->
            if (rule.startsWith("*.")) h.endsWith(rule.substring(1)) else h == rule
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", folder)
        .put("name", name)
        .put("category", category)
        .put("version", version)
        .put("network", JSONArray(network))
        .put("shared", JSONArray(shared))
        .put("permissions", JSONArray(permissions))

    companion object {
        private val HOST_RULE = Regex("^(\\*\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)+$")
        val BUCKET = Regex("^[a-z0-9-]{1,40}$")

        fun read(dir: File): AppInfo {
            var name = dir.name
            var category = ""
            var version = ""
            var emoji = ""
            var color: Long? = null
            var network = emptyList<String>()
            var shared = emptyList<String>()
            var permissions = emptyList<String>()
            val manifest = File(dir, "manifest.json")
            if (manifest.exists()) {
                runCatching {
                    val json = JSONObject(manifest.readText())
                    name = json.optString("name", dir.name).ifBlank { dir.name }
                    category = json.optString("category", "")
                    version = json.opt("version")?.toString().orEmpty()
                    // One glyph (an emoji can be several code points), kept short.
                    emoji = json.optString("emoji", "").trim().take(16)
                    color = parseColor(json.optString("color", ""))
                    network = strings(json.optJSONArray("network"))
                        .map(::cleanHost)
                        .filter { HOST_RULE.matches(it) }
                        .distinct()
                    permissions = strings(json.optJSONArray("permissions"))
                        .map { it.trim().lowercase() }
                        .filter { it in Abilities.ALL }
                        .distinct()
                    shared = strings(json.optJSONArray("shared"))
                        .map { it.trim().lowercase() }
                        .filter { BUCKET.matches(it) }
                        .distinct()
                }
            }
            return AppInfo(dir.name, dir, name, category, version, emoji, color, network, shared, permissions)
        }

        /** "#RRGGBB" to an opaque ARGB value, or null. */
        private fun parseColor(raw: String): Long? {
            val hex = raw.trim().removePrefix("#")
            if (!Regex("^[0-9a-fA-F]{6}$").matches(hex)) return null
            return 0xFF000000L or hex.toLong(16)
        }

        /** Tolerates "https://api.example.com/v1" and similar, keeping only the host. */
        private fun cleanHost(raw: String): String =
            raw.trim().lowercase()
                .substringAfter("://")
                .substringBefore('/')
                .substringBefore(':')

        private fun strings(arr: JSONArray?): List<String> =
            if (arr == null) emptyList()
            else (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
    }
}
