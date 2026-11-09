package app.shell

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * The phone's end of a push. Runs only while the launcher or a mini-app is in front, and only
 * answers devices on the local network (or Tailscale): a phone on mobile data with a public
 * IPv6 address is not reachable from the internet through this.
 *
 * Every request that changes or reveals anything is signed by a linked computer, with the key
 * agreed when it was linked (see Linker). Headers:
 *
 *   X-Shell-Device: <computer id>     X-Shell-Time: <unix seconds>
 *   X-Shell-Nonce:  16+ random hex chars, fresh per request
 *   X-Shell-File:   upload only: the file name in the multipart body
 *   X-Shell-Sig:    hex HMAC-SHA256(key, "<verb>\n<name>\n<time>\n<sha256>\n<nonce>\n<file>")
 *
 * with <name>, <sha256> and <file> empty where a call has none. Each signature is accepted
 * once (remembered on disk, so reopening the app does not reopen a replay window).
 *
 * Answers to signed requests are signed too, so the computer can tell the real phone from
 * something on the network pretending to be it:
 *
 *   X-Shell-Reply-Sig: hex HMAC-SHA256(key, "reply\n<request nonce>\n<status>\n<sha256(body)>")
 *
 *   GET  /hello                 verb "hello"   → {"phone","phoneId","app","version","port"}
 *   GET  /apps                  verb "apps"    → [{"id","name","network",...}]
 *   POST /upload?name=<app>     verb "upload"  X-Shell-Sha256 = sha256 of the one file field.
 *                                              A .zip replaces the folder, any other file is
 *                                              merged into it.
 *   POST /delete?name=<app>     verb "delete"
 *   GET  /link?id=<computer id> unsigned       → link info incl. the phone's public key, for a
 *                                              computer whose link callback was blocked; the
 *                                              answer is signed with the new key.
 *
 * 409 means the computer is not (or no longer) linked; it has to show a new QR code.
 */
class UploadServer(port: Int, private val hooks: Hooks, private val replayFile: File) : NanoHTTPD(port) {

    interface Hooks {
        fun deviceKey(id: String): ByteArray?
        fun hello(): String
        fun listApps(): String
        fun upload(name: String, files: List<Upload>, deviceId: String): Outcome
        fun delete(name: String, deviceId: String): Outcome
        /** Link info body and key for a linked computer, or null. */
        fun linkInfo(id: String): Pair<ByteArray, ByteArray>?
    }

    /** One file from the request, already spooled to a temp file by NanoHTTPD. */
    data class Upload(val fileName: String, val tmp: File)

    data class Outcome(val ok: Boolean, val message: String)

    private class Denied(val response: Response) : Exception()

    /** A verified request: who sent it, and what its answer gets signed with. */
    private class Auth(val device: String, val key: ByteArray, val nonce: String)

    /** Signatures seen recently, so a sniffed request cannot be sent again. */
    private val seen = LinkedHashMap<String, Long>()

    init {
        loadSeen()
    }

    override fun serve(session: IHTTPSession): Response {
        if (!isLocal(session.remoteIpAddress)) {
            return text(Response.Status.FORBIDDEN, "only reachable from the local network")
        }
        return try {
            route(session)
        } catch (d: Denied) {
            d.response
        }
    }

    private fun route(session: IHTTPSession): Response {
        val get = session.method == Method.GET
        val post = session.method == Method.POST
        return when {
            get && session.uri == "/" -> text(
                Response.Status.OK,
                "${Brand.NAME} is running on this phone. Link a computer by scanning the QR code Claude shows."
            )
            get && session.uri == "/hello" -> {
                val auth = authenticate(session, "hello", "", "", "")
                signed(auth, Response.Status.OK, "application/json", hooks.hello())
            }
            get && session.uri == "/apps" -> {
                val auth = authenticate(session, "apps", "", "", "")
                signed(auth, Response.Status.OK, "application/json", hooks.listApps())
            }
            get && session.uri == "/link" -> {
                val id = session.parms["id"].orEmpty().lowercase()
                val (body, key) = hooks.linkInfo(id)
                    ?: return text(Response.Status.NOT_FOUND, "not linked")
                val r = newFixedLengthResponse(Response.Status.OK, "application/json",
                                               body.inputStream(), body.size.toLong())
                r.addHeader("X-Shell-Reply-Sig", hmacHex(key, "link\n$id\n${sha256Hex(body)}"))
                r
            }
            post && session.uri == "/upload" -> upload(session)
            post && session.uri == "/delete" -> {
                val raw = session.parms["name"].orEmpty()
                val auth = authenticate(session, "delete", raw, "", "")
                val name = Installer.sanitize(raw)
                if (name.isEmpty()) return signed(auth, Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "missing ?name=\n")
                outcome(auth, hooks.delete(name, auth.device))
            }
            else -> text(Response.Status.NOT_FOUND, "not found")
        }
    }

    private fun upload(session: IHTTPSession): Response {
        // Query parameters are already parsed here. Read the name before parseBody, because
        // multipart field names land in the same map.
        val raw = session.parms["name"].orEmpty()
        val sha = session.headers["x-shell-sha256"].orEmpty().lowercase()
        val file = session.headers["x-shell-file"].orEmpty()
        if (raw.isEmpty() || !sha.matches(HEX64) || file.isEmpty()) {
            return text(Response.Status.BAD_REQUEST, "uploads need ?name=, X-Shell-Sha256 and X-Shell-File")
        }
        // Authenticate before reading the body, so a stranger never gets to spool a file
        // onto the phone.
        val auth = authenticate(session, "upload", raw, sha, file)

        val tmpPaths = HashMap<String, String>()
        try {
            session.parseBody(tmpPaths)
        } catch (e: Exception) {
            return signed(auth, Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "could not read upload: ${e.message}\n")
        }
        val uploads = tmpPaths.map { (field, path) -> Upload(session.parms[field] ?: field, File(path)) }

        // The signature covers the file's hash and its name (the name decides whether it
        // replaces the app or is merged in), so check the body really has both.
        if (uploads.size != 1 || uploads[0].fileName != file || sha256Hex(uploads[0].tmp) != sha) {
            uploads.forEach { it.tmp.delete() }
            return signed(auth, Response.Status.BAD_REQUEST, MIME_PLAINTEXT,
                          "send exactly one file, matching X-Shell-File and X-Shell-Sha256\n")
        }

        val name = Installer.sanitize(raw)
        if (name.isEmpty()) {
            uploads.forEach { it.tmp.delete() }
            return signed(auth, Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "app name has no usable characters\n")
        }
        return outcome(
            auth,
            try {
                hooks.upload(name, uploads, auth.device)
            } catch (e: Exception) {
                Outcome(false, "install failed: ${e.message}")
            }
        )
    }

    /** Returns the verified request, or throws [Denied] carrying the error response. */
    private fun authenticate(session: IHTTPSession, verb: String, name: String, sha: String, file: String): Auth {
        val id = session.headers["x-shell-device"]
            ?: throw Denied(text(Response.Status.UNAUTHORIZED, "not signed"))
        val key = hooks.deviceKey(id)
            ?: throw Denied(text(Response.Status.CONFLICT, "this computer is not linked to the phone, link it again"))
        val time = session.headers["x-shell-time"]?.toLongOrNull()
            ?: throw Denied(text(Response.Status.UNAUTHORIZED, "missing X-Shell-Time"))
        val nonce = session.headers["x-shell-nonce"].orEmpty().lowercase()
        if (!nonce.matches(NONCE)) throw Denied(text(Response.Status.UNAUTHORIZED, "missing X-Shell-Nonce"))
        val sig = session.headers["x-shell-sig"].orEmpty().lowercase()
        val now = System.currentTimeMillis() / 1000
        if (abs(now - time) > MAX_SKEW_SECONDS) {
            throw Denied(text(Response.Status.UNAUTHORIZED, "clock skew: phone and computer disagree on the time"))
        }
        val expected = hmacHex(key, "$verb\n$name\n$time\n$sha\n$nonce\n$file")
        if (!MessageDigest.isEqual(expected.toByteArray(), sig.toByteArray())) {
            throw Denied(text(Response.Status.UNAUTHORIZED, "bad signature"))
        }
        if (!firstSighting(sig, now)) {
            throw Denied(text(Response.Status.UNAUTHORIZED, "replayed request"))
        }
        return Auth(id, key, nonce)
    }

    @Synchronized
    private fun firstSighting(sig: String, now: Long): Boolean {
        seen.entries.removeAll { now - it.value > REPLAY_WINDOW_SECONDS }
        if (seen.containsKey(sig)) return false
        seen[sig] = now
        runCatching { replayFile.writeText(seen.entries.joinToString("\n") { "${it.key} ${it.value}" }) }
        return true
    }

    private fun loadSeen() {
        val now = System.currentTimeMillis() / 1000
        runCatching {
            replayFile.readLines().forEach { line ->
                val parts = line.split(' ')
                val t = parts.getOrNull(1)?.toLongOrNull() ?: return@forEach
                if (now - t <= REPLAY_WINDOW_SECONDS) seen[parts[0]] = t
            }
        }
    }

    private fun signed(auth: Auth, status: Response.Status, mime: String, body: String): Response {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val r = newFixedLengthResponse(status, mime, bytes.inputStream(), bytes.size.toLong())
        r.addHeader("X-Shell-Reply-Sig",
                    hmacHex(auth.key, "reply\n${auth.nonce}\n${status.requestStatus}\n${sha256Hex(bytes)}"))
        return r
    }

    private fun outcome(auth: Auth, o: Outcome): Response =
        signed(auth, if (o.ok) Response.Status.OK else Response.Status.FORBIDDEN, MIME_PLAINTEXT, o.message + "\n")

    private fun text(status: Response.Status, msg: String): Response =
        newFixedLengthResponse(status, MIME_PLAINTEXT, msg + "\n")

    companion object {
        private const val MAX_SKEW_SECONDS = 300L
        private const val REPLAY_WINDOW_SECONDS = 600L
        private val HEX64 = Regex("[0-9a-f]{64}")
        private val NONCE = Regex("[0-9a-f]{16,64}")

        /** Loopback, private IPv4, Tailscale's 100.64/10, and IPv6 link-local / unique-local. */
        fun isLocal(ip: String?): Boolean {
            val addr = runCatching { InetAddress.getByName(ip ?: return false) }.getOrNull() ?: return false
            if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress) return true
            val b = addr.address
            return when (b.size) {
                4 -> (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
                16 -> (b[0].toInt() and 0xFE) == 0xFC
                else -> false
            }
        }

        fun hmacHex(key: ByteArray, message: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(message.toByteArray(Charsets.UTF_8)).toHex()
        }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        fun sha256Hex(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().toHex()
        }

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
