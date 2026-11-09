package app.shell

import java.io.File
import java.util.zip.ZipInputStream

/**
 * Writes an upload into the apps folder.
 *
 * Every path is checked against the destination before anything is written, so a zip
 * containing ../../ entries cannot escape the mini-app folder.
 */
object Installer {

    fun install(appsRoot: File, name: String, uploads: List<UploadServer.Upload>): File {
        val dest = File(appsRoot, name)
        guard(appsRoot, dest)
        val zips = uploads.filter { it.fileName.endsWith(".zip", ignoreCase = true) }

        if (zips.isNotEmpty()) {
            // A bundle replaces the folder outright.
            if (dest.exists()) dest.deleteRecursively()
            dest.mkdirs()
            zips.forEach { unzip(it.tmp, dest) }
            flattenSingleFolder(dest)
        } else {
            // Loose files are dropped in beside whatever is already there.
            dest.mkdirs()
            uploads.forEach { up ->
                val target = File(dest, plainName(up.fileName))
                guard(dest, target)
                target.parentFile?.mkdirs()
                up.tmp.copyTo(target, overwrite = true)
            }
        }

        if (!File(dest, "index.html").exists()) {
            throw IllegalStateException("no index.html in $name, so it would not show up")
        }
        return dest
    }

    fun remove(appsRoot: File, name: String): Boolean {
        val dest = File(appsRoot, name)
        guard(appsRoot, dest)
        return dest.exists() && dest.deleteRecursively()
    }

    private fun unzip(zip: File, dest: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val out = File(dest, entry.name)
                guard(dest, out)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
    }

    /** Zips made by right-click "Send to compressed folder" often nest one extra folder. */
    private fun flattenSingleFolder(dest: File) {
        if (File(dest, "index.html").exists()) return
        val children = dest.listFiles()?.filter { it.name != "__MACOSX" } ?: return
        val only = children.singleOrNull()?.takeIf { it.isDirectory } ?: return
        if (!File(only, "index.html").exists()) return
        only.listFiles()?.forEach { it.renameTo(File(dest, it.name)) }
        only.deleteRecursively()
    }

    /**
     * Folder names double as the app's origin host (<folder>.homebaked.internal), so they
     * are kept to a valid DNS label: lowercase letters, digits and single hyphens.
     * "Tip_Calc" becomes "tip-calc". The desktop skill applies the same rule.
     */
    fun sanitize(raw: String): String =
        raw.trim().lowercase()
            .replace(Regex("[^a-z0-9-]+"), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
            .take(63)
            .trimEnd('-')

    private fun plainName(raw: String): String =
        raw.substringAfterLast('/').substringAfterLast('\\')

    fun guard(root: File, candidate: File) {
        val rootPath = root.canonicalPath
        val path = candidate.canonicalPath
        if (path != rootPath && !path.startsWith(rootPath + File.separator)) {
            throw SecurityException("path escapes the app folder: ${candidate.name}")
        }
    }
}
