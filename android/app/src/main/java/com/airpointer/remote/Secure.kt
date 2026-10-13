package com.airpointer.remote

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * This phone's key pair (ECDSA P-256), created once in the Android Keystore. The private key never leaves
 * the phone; computers store the public key when pairing and check signatures made with it.
 */
object DeviceKey {
    private const val ALIAS = "airpointer-device"

    private val keyStore: KeyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    @Synchronized
    private fun ensure() {
        if (keyStore.containsAlias(ALIAS)) return
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
        }.generateKeyPair()
    }

    /** SubjectPublicKeyInfo (DER). */
    val publicKey: ByteArray by lazy {
        ensure()
        keyStore.getCertificate(ALIAS).publicKey.encoded
    }

    /** SHA-256 of [publicKey]: how computers recognize this phone. */
    val id: ByteArray by lazy { Secure.sha256(publicKey) }

    fun sign(data: ByteArray): ByteArray {
        ensure()
        val key = keyStore.getKey(ALIAS, null) as PrivateKey
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(data)
            sign()
        }
    }
}

/**
 * A computer this phone paired with by scanning its QR code. [hosts] are its last known addresses, best first;
 * [internetId] is its iroh endpoint id (learned on each local connection while it allows internet access).
 */
class PairedPc(
    val id: ByteArray,
    val name: String,
    val os: String,
    val publicKey: ByteArray,
    val hosts: List<String>,
    val port: Int,
    val internetId: ByteArray? = null,
) {
    val idText: String get() = Secure.b64url(id)

    fun withHost(host: String, port: Int = this.port) =
        PairedPc(id, name, os, publicKey, listOf(host) + hosts.filter { it != host }, port, internetId)

    fun withInternetId(internetId: ByteArray?) = PairedPc(id, name, os, publicKey, hosts, port, internetId)

    fun toJson(): JSONObject = JSONObject()
        .put("id", idText).put("name", name).put("os", os)
        .put("key", Base64.encodeToString(publicKey, Base64.NO_WRAP))
        .put("hosts", JSONArray(hosts)).put("port", port)
        .apply { internetId?.let { put("net", Secure.b64url(it)) } }

    companion object {
        fun fromJson(o: JSONObject) = PairedPc(
            Secure.unb64url(o.getString("id")),
            o.getString("name"),
            o.optString("os"),
            Base64.decode(o.getString("key"), Base64.NO_WRAP),
            o.getJSONArray("hosts").let { a -> List(a.length()) { a.getString(it) } },
            o.optInt("port", Protocol.DEFAULT_PORT),
            o.optString("net").takeIf { it.isNotEmpty() }?.let { Secure.unb64url(it) },
        )
    }
}

/**
 * A connection to a computer before authentication: a pinned-TLS socket (Wi-Fi) or an iroh stream
 * (internet, encrypted end to end by iroh's QUIC). [serverId] is the key TLS already verified (Wi-Fi only).
 */
class Channel(
    val input: InputStream,
    val output: OutputStream,
    private val closer: Closeable,
    val serverId: ByteArray?,
    val host: String?,
    val internet: AirlinkPipe?,
    private val readTimeout: (Int) -> Unit,
) : Closeable {
    fun setReadTimeout(ms: Int) = readTimeout(ms)

    override fun close() {
        runCatching { closer.close() }
    }

    companion object {
        fun tls(c: Secure.TlsConnection) =
            Channel(c.socket.inputStream, c.socket.outputStream, c.socket, c.serverId, c.host, null) { c.socket.soTimeout = it }

        fun internet(p: AirlinkPipe) = Channel(p.input, p.output, p, null, null, p) { p.readTimeoutMs = it }
    }
}

/** The paired computers, kept in the app's private storage. */
object PcStore {
    private const val FILE = "paired"
    private const val KEY = "pcs"

    fun all(context: Context): List<PairedPc> {
        val json = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching { JSONArray(json).let { a -> List(a.length()) { PairedPc.fromJson(a.getJSONObject(it)) } } }
            .getOrDefault(emptyList())
    }

    fun find(context: Context, id: ByteArray): PairedPc? = all(context).firstOrNull { it.id.contentEquals(id) }

    /** Adds or replaces (same computer id). */
    @Synchronized
    fun save(context: Context, pc: PairedPc) {
        val list = all(context).filterNot { it.id.contentEquals(pc.id) } + pc
        write(context, list)
    }

    @Synchronized
    fun remove(context: Context, pc: PairedPc) = write(context, all(context).filterNot { it.id.contentEquals(pc.id) })

    private fun write(context: Context, list: List<PairedPc>) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY, JSONArray(list.map { it.toJson() }).toString()).apply()
    }
}

/**
 * Wi-Fi connections are TLS, but instead of certificate authorities the phone trusts exactly the keys of
 * computers it paired with (pinned by SHA-256 of the public key). Then both sides sign each other's
 * random nonce ([authenticate]); over Bluetooth that signature check is the only proof, on top of the
 * Bluetooth link's own encryption.
 */
object Secure {
    private val random = SecureRandom()

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    fun b64url(b: ByteArray): String = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    fun unb64url(s: String): ByteArray = Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    fun nonce(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    /** Accepts the server only if its key's SHA-256 is one [accept] knows. */
    private class PinnedTrustManager(private val accept: (ByteArray) -> Boolean) : X509TrustManager {
        var serverId: ByteArray? = null

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
            val id = sha256(chain.first().publicKey.encoded)
            if (!accept(id)) throw CertificateException("Not a paired AirPointer computer")
            serverId = id
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) =
            throw CertificateException("Not used")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    class TlsConnection(val socket: SSLSocket, val host: String, val serverId: ByteArray)

    /** Blocking: TCP + TLS to one address, trusting only computers [accept] approves. */
    fun connectTls(host: String, port: Int, timeoutMs: Int, accept: (ByteArray) -> Boolean): TlsConnection {
        val plain = Socket()
        try {
            plain.tcpNoDelay = true
            plain.keepAlive = true
            plain.connect(InetSocketAddress(host, port), timeoutMs)
            plain.soTimeout = 8000
            val trust = PinnedTrustManager(accept)
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            val tls = ctx.socketFactory.createSocket(plain, host, port, true) as SSLSocket
            tls.startHandshake()
            return TlsConnection(tls, host, trust.serverId!!)
        } catch (e: Exception) {
            runCatching { plain.close() }
            throw e
        }
    }

    /**
     * Tries all [hosts] at once (a computer can have several addresses: Wi-Fi, Ethernet, virtual adapters)
     * and returns the first that answers as a trusted computer.
     */
    fun connectFirst(hosts: List<String>, port: Int, accept: (ByteArray) -> Boolean, timeoutMs: Int = 3000): TlsConnection {
        if (hosts.isEmpty()) throw java.io.IOException("No address")
        val results = LinkedBlockingQueue<Result<TlsConnection>>()
        for (h in hosts.distinct()) thread(name = "airpointer-connect") { results.put(runCatching { connectTls(h, port, timeoutMs, accept) }) }
        var winner: TlsConnection? = null
        var error: Throwable? = null
        repeat(hosts.distinct().size) {
            val r = results.poll(timeoutMs + 6000L, TimeUnit.MILLISECONDS) ?: return@repeat
            r.onSuccess { c -> if (winner == null) winner = c else runCatching { c.socket.close() } }
            r.onFailure { e -> if (error == null || e is CertificateException || e.cause is CertificateException) error = e }
        }
        return winner ?: throw (error ?: java.io.IOException("Not reachable"))
    }

    class Authenticated(val pc: PairedPc, val serverName: String)

    /**
     * Sends [hello] (HELLO or MEDIA_HELLO built with [nonce]) and runs the challenge/response.
     * [tlsServerId]: the key TLS already verified, which must be the one that signs. Throws
     * [NetSink.HandshakeException] when the computer refuses or can't prove who it is.
     */
    fun authenticate(
        context: Context,
        input: InputStream,
        output: OutputStream,
        hello: ByteArray,
        nonce: ByteArray,
        tlsServerId: ByteArray?,
    ): Authenticated {
        output.write(hello)
        output.flush()
        var f = Protocol.readFrame(input)
        if (f.type == Protocol.WELCOME) throw refusal(f.payload)
        if (f.type != Protocol.AUTH_CHALLENGE) throw NetSink.HandshakeException("Unexpected reply from computer")
        val r = Protocol.Reader(f.payload)
        val pcId = r.bytes(32)
        val serverNonce = r.bytes(32)
        val pcSignature = r.bytes8()
        val pc = PcStore.find(context, pcId)
            ?: throw NetSink.HandshakeException("Not paired yet. Scan its code first.", fatal = true)
        if (tlsServerId != null && !tlsServerId.contentEquals(pcId)) throw NetSink.HandshakeException("Computer identity mismatch", fatal = true)
        if (!verify(pc.publicKey, transcript(true, nonce, serverNonce, DeviceKey.id), pcSignature)) {
            throw NetSink.HandshakeException("${pc.name} couldn't prove its identity", fatal = true)
        }
        val mine = DeviceKey.sign(transcript(false, nonce, serverNonce, pcId))
        output.write(Protocol.frame(Protocol.AUTH_RESPONSE, Protocol.bytes8(mine)))
        output.flush()
        f = Protocol.readFrame(input)
        if (f.type != Protocol.WELCOME || f.payload.isEmpty()) throw NetSink.HandshakeException("Unexpected reply from computer")
        if (f.payload[0].toInt() != Protocol.STATUS_OK) throw refusal(f.payload)
        val w = Protocol.Reader(f.payload, 1)
        val name = w.str8()
        w.str8() // os
        // The computer's internet id (absent while it has internet access off): remember it for later.
        val internetId = if (w.remaining > 0) w.bytes8().takeIf { it.size == 32 } else null
        var known = pc
        if (!internetId.contentEquals(pc.internetId)) {
            known = pc.withInternetId(internetId)
            PcStore.save(context, known)
        }
        return Authenticated(known, name)
    }

    /** Same layout as Auth.Transcript on the computer. */
    private fun transcript(server: Boolean, nonceC: ByteArray, nonceS: ByteArray, peerId: ByteArray): ByteArray =
        (if (server) "AirPointer-v5-server" else "AirPointer-v5-client").toByteArray(Charsets.US_ASCII) + nonceC + nonceS + peerId

    private fun verify(spki: ByteArray, data: ByteArray, signature: ByteArray): Boolean = runCatching {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(data)
            verify(signature)
        }
    }.getOrDefault(false)

    /** A Welcome with a non-OK status → the matching error for the user. */
    fun refusal(welcome: ByteArray): NetSink.HandshakeException {
        val status = welcome.firstOrNull()?.toInt() ?: -1
        return when (status) {
            Protocol.STATUS_BAD_VERSION -> NetSink.HandshakeException("App versions don't match - update both apps", fatal = true)
            Protocol.STATUS_NOT_PAIRED -> NetSink.HandshakeException(
                "Not paired with this computer. Pair again.",
                fatal = true,
            )
            Protocol.STATUS_AUTH_FAILED -> NetSink.HandshakeException("Not accepted by the computer. Pair again.", fatal = true)
            Protocol.STATUS_PAIRING_CLOSED -> NetSink.HandshakeException("Code expired. Show a new one on the computer.", fatal = true)
            Protocol.STATUS_UNAVAILABLE -> NetSink.HandshakeException(
                runCatching { Protocol.Reader(welcome, 1).run { str8(); str8(); str8() } }.getOrNull() ?: "Not available on this computer",
                fatal = true,
            )
            else -> NetSink.HandshakeException("Connection refused")
        }
    }
}

/** The pairing QR code: airpointer://pair?v=5&n=NAME&h=IP,IP&p=PORT&k=PC-KEY-HASH&t=ONE-TIME-TOKEN */
class PairingLink(val name: String, val hosts: List<String>, val port: Int, val pcId: ByteArray, val token: ByteArray) {
    companion object {
        fun parse(text: String): PairingLink? = runCatching {
            val u = Uri.parse(text.trim())
            if (u.scheme != "airpointer" || u.host != "pair") return null
            PairingLink(
                name = u.getQueryParameter("n") ?: "Computer",
                hosts = u.getQueryParameter("h")!!.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                port = u.getQueryParameter("p")?.toIntOrNull() ?: Protocol.DEFAULT_PORT,
                pcId = Secure.unb64url(u.getQueryParameter("k")!!),
                token = Secure.unb64url(u.getQueryParameter("t")!!),
            ).takeIf { it.pcId.size == 32 && it.hosts.isNotEmpty() }
        }.getOrNull()
    }

    /** Blocking: connects (TLS, trusting only the key in the QR code), presents the token, saves the computer. */
    fun pair(context: Context): PairedPc {
        val c = Secure.connectFirst(hosts, port, { it.contentEquals(pcId) })
        c.socket.use { s ->
            val out = s.outputStream
            out.write(Protocol.pairRequest(token, Link.deviceName(), DeviceKey.publicKey))
            out.flush()
            val f = Protocol.readFrame(s.inputStream)
            if (f.type != Protocol.PAIR_RESULT || f.payload.isEmpty()) throw NetSink.HandshakeException("Unexpected reply from computer")
            if (f.payload[0].toInt() != Protocol.STATUS_OK) throw Secure.refusal(f.payload)
            val r = Protocol.Reader(f.payload, 1)
            val pcName = r.str8()
            val os = r.str8()
            val key = r.bytes16()
            if (!Secure.sha256(key).contentEquals(pcId)) throw NetSink.HandshakeException("Computer identity mismatch", fatal = true)
            val pc = PairedPc(pcId, pcName, os, key, listOf(c.host) + hosts.filter { it != c.host }, port)
            PcStore.save(context, pc)
            return pc
        }
    }
}
