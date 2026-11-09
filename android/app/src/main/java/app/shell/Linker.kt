package app.shell

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Linking a computer by QR code.
 *
 * The computer (the desktop skill's `pair` command) shows:
 *
 *   homebaked://link?v=1&id=<hex>&k=<base64url 32-byte secret>&c=<base64url X25519 public key>
 *                   &n=<computer name>&cb=<ip:port>,<ip:port>&exp=<unix seconds>
 *
 * On Link the phone makes its own X25519 key pair and derives the long-term key from
 *
 *   key = HMAC-SHA256(k, "shell-link-v1" || c || p || X25519(phone private, c))
 *
 * where p is the phone's public key. The computer derives the same key from its own private
 * key. So the key never appears anywhere: not in the QR code, not on the network. A photo
 * of the QR code is not enough to push apps; it would also take the computer's private key,
 * which never leaves it.
 *
 * The phone then POSTs a hello, carrying p and signed with the new key, to one of the
 * callback addresses, so the computer learns the key, where the phone is, and that it worked.
 * If a desktop firewall stops the callback, the computer can fetch p later from GET /link.
 */
object Linker {

    class Offer(
        val id: String,
        val name: String,
        val secret: ByteArray,
        val computerPublic: ByteArray,
        val callbacks: List<String>,
        val expires: Long
    )

    /** What the phone keeps after the person taps Link. */
    class Linked(val offer: Offer, val key: ByteArray, val phonePublic: ByteArray)

    class BadLink(message: String) : Exception(message)

    private val ID = Regex("^[0-9a-f]{8,32}$")
    private val CALLBACK = Regex("^[0-9.]{7,15}:[0-9]{1,5}$")
    private const val B64 = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    /** Seconds of disagreement between the phone's and computer's clocks we let slide. */
    private const val CLOCK_GRACE = 120L

    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, B64)

    private fun unb64(s: String?): ByteArray? = runCatching { Base64.decode(s.orEmpty(), B64) }.getOrNull()

    fun parse(raw: String): Offer {
        val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull()
        if (uri == null || uri.scheme != Brand.SCHEME || uri.host != "link") {
            throw BadLink("That QR code isn't a ${Brand.NAME} link.")
        }
        if (uri.getQueryParameter("v") != "1") {
            throw BadLink("This link is from a newer version. Update ${Brand.NAME} on this phone.")
        }
        val id = uri.getQueryParameter("id").orEmpty().lowercase()
        val secret = unb64(uri.getQueryParameter("k"))
        val computerPublic = unb64(uri.getQueryParameter("c"))
        val exp = uri.getQueryParameter("exp")?.toLongOrNull()
        if (!ID.matches(id) || secret?.size != 32 || computerPublic?.size != 32 || exp == null) {
            throw BadLink("That link is damaged. Ask Claude for a new QR code.")
        }
        if (System.currentTimeMillis() / 1000 > exp + CLOCK_GRACE) {
            throw BadLink("That QR code has expired. Ask Claude for a new one.")
        }
        val name = uri.getQueryParameter("n").orEmpty()
            .replace(Regex("[^A-Za-z0-9._ -]"), "")
            .trim()
            .take(40)
            .ifEmpty { "computer" }
        val callbacks = uri.getQueryParameter("cb").orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { CALLBACK.matches(it) && isLocal(it.substringBefore(':')) }
            .take(6)
        return Offer(id, name, secret, computerPublic, callbacks, exp)
    }

    /** The key agreement. Pure computation, a few milliseconds. */
    fun accept(offer: Offer): Linked {
        val mine = X25519.newPrivateKey()
        val phonePublic = X25519.publicKey(mine)
        val shared = try {
            X25519.sharedSecret(mine, offer.computerPublic)
        } catch (e: IllegalArgumentException) {
            throw BadLink("That link is damaged. Ask Claude for a new QR code.")
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(offer.secret, "HmacSHA256"))
        mac.update("shell-link-v1".toByteArray())
        mac.update(offer.computerPublic)
        mac.update(phonePublic)
        val key = mac.doFinal(shared)
        mine.fill(0)
        return Linked(offer, key, phonePublic)
    }

    /**
     * Only home-network and Tailscale addresses. A QR code must not be able to make the
     * phone announce itself to some server on the internet.
     */
    private fun isLocal(ip: String): Boolean {
        val b = runCatching { InetAddress.getByName(ip).address }.getOrNull() ?: return false
        if (b.size != 4) return false
        val a0 = b[0].toInt() and 0xFF
        val a1 = b[1].toInt() and 0xFF
        return a0 == 10 ||
            (a0 == 172 && a1 in 16..31) ||
            (a0 == 192 && a1 == 168) ||
            (a0 == 100 && a1 in 64..127) ||
            (a0 == 169 && a1 == 254)
    }

    /** The body of the link callback and of GET /link: who this phone is, and its key half. */
    fun linkInfo(phonePublic: ByteArray, port: Int): ByteArray = JSONObject()
        .put("app", Brand.NAME)
        .put("version", BuildConfig.VERSION_NAME)
        .put("phone", PushHub.phoneName())
        .put("phoneId", PushHub.phoneId())
        .put("port", port)
        .put("pub", b64(phonePublic))
        .toString()
        .toByteArray(Charsets.UTF_8)

    /**
     * Tells the computer we linked. Network, so call it off the main thread.
     * Returns the address that answered, or null if none did.
     */
    fun announce(linked: Linked, port: Int): String? {
        val body = linkInfo(linked.phonePublic, port)
        for (cb in linked.offer.callbacks) {
            val time = System.currentTimeMillis() / 1000
            val sig = UploadServer.hmacHex(
                linked.key, "linked\n${PushHub.phoneId()}\n$time\n${UploadServer.sha256Hex(body)}"
            )
            val ok = runCatching {
                val conn = URL("http://$cb/linked").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 5000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-Shell-Device", linked.offer.id)
                conn.setRequestProperty("X-Shell-Time", time.toString())
                conn.setRequestProperty("X-Shell-Sig", sig)
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                conn.disconnect()
                code == 200
            }.getOrDefault(false)
            if (ok) return cb
        }
        return null
    }
}
