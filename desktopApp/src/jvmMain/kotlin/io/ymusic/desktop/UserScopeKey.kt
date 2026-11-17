package io.ymusic.desktop

import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import kmpcommon.log.Logger
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

private const val KEY_BYTES = 32
private const val KEY_NAME = "cookie-key"
private const val APP_ID = "io.ymusic.desktop"

/** [AppStore] secrets, named as the files they were before (the legacy import keeps the names). */
internal const val DPAPI_KEY_SECRET = "$KEY_NAME.dpapi"
internal const val FALLBACK_KEY_SECRET = "$KEY_NAME.bin"

/**
 * The key the cookie jar is sealed under by default: one only this OS user can recover, so a copied
 * file or disk is unreadable — though any app running as that user can still ask for it. A dev
 * slot's run keeps it in the slot's own [AppStore] (where tools/dev-slot.sh seeds it, the Rust debug
 * build's place too), off the release app's OS key item.
 * Blocking (keychain / DPAPI / D-Bus): call it off the main thread.
 */
internal fun userScopeCookieKey(store: AppStore, log: Logger?): SecretKey {
    val bytes = when {
        DevInstance.slot != null -> store.secretOrPut(FALLBACK_KEY_SECRET, randomSecret(KEY_BYTES))
        else -> osUserKey(store, log)
    }
    return SecretKeySpec(bytes, "AES")
}

private fun osUserKey(store: AppStore, log: Logger?): ByteArray =
    when (desktopOsKey()) {
        "macos" -> MacKeychain.getOrCreateSecret(APP_ID, KEY_NAME, KEY_BYTES)
        "windows" -> WindowsDpapi.getOrCreateSecret(store, KEY_BYTES)
        else -> linuxSecret(store, log)
    }

private fun randomSecret(size: Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }

/**
 * The desktop keyring (Secret Service: GNOME Keyring, KWallet) when one answers; otherwise a plain
 * key in the owner-only [AppStore], which only keeps out other OS users. A fallback key, once made,
 * stays the source — a keyring that turns up later must not orphan the jar sealed under it.
 */
private fun linuxSecret(store: AppStore, log: Logger?): ByteArray {
    store.secret(FALLBACK_KEY_SECRET)?.let { return it }
    try {
        return LinuxSecretService.getOrCreateSecret(APP_ID, KEY_NAME, KEY_BYTES)
    } catch (error: Throwable) {
        log?.log(Logger.Level.WARN, "no Secret Service, cookie key falls back to the app store", error)
    }
    return store.secretOrPut(FALLBACK_KEY_SECRET, randomSecret(KEY_BYTES))
}

/** DPAPI (`CryptProtectData`, user scope): the stored blob opens only for this Windows user. */
private object WindowsDpapi {
    private const val CRYPTPROTECT_UI_FORBIDDEN = 0x1
    private val crypt32 by lazy { NativeLibrary.getInstance("Crypt32") }
    private val kernel32 by lazy { NativeLibrary.getInstance("Kernel32") }
    private val entropy = "$APP_ID/$KEY_NAME".toByteArray()

    fun getOrCreateSecret(store: AppStore, size: Int): ByteArray {
        store.secret(DPAPI_KEY_SECRET)?.let { return transform("CryptUnprotectData", it) }
        val secret = randomSecret(size)
        val blob = transform("CryptProtectData", secret)
        val kept = store.secretOrPut(DPAPI_KEY_SECRET, blob)
        return if (kept.contentEquals(blob)) secret else transform("CryptUnprotectData", kept)
    }

    private fun transform(function: String, input: ByteArray): ByteArray {
        val inBlob = DataBlob.of(input)
        val entropyBlob = DataBlob.of(entropy)
        val outBlob = DataBlob()
        // CryptProtectData takes a description string where CryptUnprotectData takes an out-pointer
        // to one; null is valid for both.
        val ok = crypt32.getFunction(function).invokeInt(
            arrayOf(inBlob, null, entropyBlob, null, null, CRYPTPROTECT_UI_FORBIDDEN, outBlob),
        )
        if (ok == 0) {
            val error = kernel32.getFunction("GetLastError").invokeInt(arrayOf())
            throw IOException("$function failed: Win32 error $error")
        }
        outBlob.read()
        val data = requireNotNull(outBlob.pbData) { "$function returned no data" }
        try {
            return data.getByteArray(0, outBlob.cbData)
        } finally {
            kernel32.getFunction("LocalFree").invokePointer(arrayOf(data))
        }
    }

    /** Field order by override, not `@FieldOrder`: the release ProGuard pass strips annotations. */
    class DataBlob : Structure() {
        @JvmField var cbData: Int = 0

        @JvmField var pbData: Pointer? = null

        override fun getFieldOrder(): List<String> = listOf("cbData", "pbData")

        companion object {
            fun of(bytes: ByteArray) = DataBlob().apply {
                cbData = bytes.size
                pbData = Memory(bytes.size.toLong().coerceAtLeast(1)).apply { write(0, bytes, 0, bytes.size) }
                write()
            }
        }
    }
}

/**
 * libsecret over JNA, through the `v` (GHashTable) entry points so no C varargs cross JNA. The
 * secret is stored base64: libsecret's password API is string-typed.
 */
private object LinuxSecretService {
    private val secret by lazy { NativeLibrary.getInstance("secret-1") }
    private val glib by lazy { NativeLibrary.getInstance("glib-2.0") }

    // SecretSchema on LP64: name(8) flags(4+4) attributes[32]{name(8) type(4+4)} reserved(4+4) reserved1..7(8×7).
    private const val SCHEMA_BYTES = 16 + 32 * 16 + 8 + 7 * 8

    fun getOrCreateSecret(service: String, account: String, size: Int): ByteArray {
        lookup(service, account)?.let { return Base64.getDecoder().decode(it) }
        val fresh = randomSecret(size)
        store(service, account, Base64.getEncoder().encodeToString(fresh))
        return fresh
    }

    private fun lookup(service: String, account: String): String? = withSchema(service, account) { schema, attributes ->
        val error = PointerByReference()
        val found = secret.getFunction("secret_password_lookupv_sync").invokePointer(arrayOf(schema, attributes, null, error))
        throwIfError(error, "secret_password_lookupv_sync")
        found?.let {
            try {
                it.getString(0)
            } finally {
                secret.getFunction("secret_password_free").invokeVoid(arrayOf(it))
            }
        }
    }

    private fun store(service: String, account: String, value: String) = withSchema(service, account) { schema, attributes ->
        val error = PointerByReference()
        val ok = secret.getFunction("secret_password_storev_sync").invokeInt(
            arrayOf(schema, attributes, null, "YMusic sign-in key", value, null, error),
        )
        throwIfError(error, "secret_password_storev_sync")
        if (ok == 0) throw IOException("secret_password_storev_sync refused")
    }

    private inline fun <T> withSchema(service: String, account: String, block: (Pointer, Pointer) -> T): T {
        val schemaName = cString(APP_ID)
        val serviceKey = cString("service")
        val accountKey = cString("account")
        val serviceValue = cString(service)
        val accountValue = cString(account)
        val schema = Memory(SCHEMA_BYTES.toLong()).apply {
            clear()
            setPointer(0, schemaName)
            setPointer(16, serviceKey)
            setPointer(32, accountKey)
        }
        val table = glib.getFunction("g_hash_table_new").invokePointer(
            arrayOf(glib.getFunction("g_str_hash"), glib.getFunction("g_str_equal")),
        )
        try {
            glib.getFunction("g_hash_table_insert").invokeInt(arrayOf(table, serviceKey, serviceValue))
            glib.getFunction("g_hash_table_insert").invokeInt(arrayOf(table, accountKey, accountValue))
            return block(schema, table)
        } finally {
            glib.getFunction("g_hash_table_unref").invokeVoid(arrayOf(table))
        }
    }

    private fun throwIfError(error: PointerByReference, function: String) {
        val gError = error.value ?: return
        val message = gError.getPointer(8)?.getString(0)
        glib.getFunction("g_error_free").invokeVoid(arrayOf(gError))
        throw IOException("$function: $message")
    }

    private fun cString(value: String): Memory {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return Memory(bytes.size + 1L).apply {
            write(0, bytes, 0, bytes.size)
            setByte(bytes.size.toLong(), 0)
        }
    }
}
