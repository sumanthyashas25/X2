package com.example.chat

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.model.ChatMessage
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedMessagePayload(
    val ciphertextBase64: String,
    val ivBase64: String,
    val senderPublicKeyBase64: String,
    val envelopes: Map<String, String>,
    val algo: String = ChatE2eeCrypto.ALGO_ID
)

/**
 * End-to-End Encryption engine using per-user ECDH (secp256r1) + HKDF-SHA256 + AES-256-GCM
 * envelope encryption. Each message gets a fresh 256-bit AES content key (DEK), which is
 * individually wrapped using ECDH shared secret with every participant's public key.
 * Private keys are stored hardware-backed/encrypted via AndroidKeyStore when available.
 */
object ChatE2eeCrypto {
    const val ALGO_ID = "ECDH-P256-AES256-GCM"
    private const val PREF_FILE = "xmax_e2ee_keystore_v1"
    private const val KEYSTORE_WRAP_ALIAS_PREFIX = "xmax_e2ee_wrap_"
    private const val GCM_TAG_BITS = 128

    private val random = SecureRandom()

    fun getOrCreatePublicKeyBase64(context: Context, uid: String): String {
        val kp = getOrCreateKeyPair(context, uid) ?: return ""
        return Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
    }

    fun fingerprintForPublicKey(publicKeyBase64: String): String {
        val clean = publicKeyBase64.trim()
        if (clean.isBlank()) return "Not verified"
        val raw = runCatching { Base64.decode(clean, Base64.NO_WRAP) }.getOrNull() ?: return "Invalid key"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw)
        val digits = buildString {
            for (i in 0 until 15 step 3) {
                val v = ((digest[i].toInt() and 0xFF) shl 16) or
                    ((digest[i + 1].toInt() and 0xFF) shl 8) or
                    (digest[i + 2].toInt() and 0xFF)
                append((v % 100000).toString().padStart(5, '0'))
                if (i + 3 < 15) append(" ")
            }
        }
        return digits
    }

    fun pairSafetyNumber(myPublicKeyB64: String, peerPublicKeyB64: String): String {
        if (myPublicKeyB64.isBlank() || peerPublicKeyB64.isBlank()) return "Pending key exchange"
        val sorted = listOf(myPublicKeyB64.trim(), peerPublicKeyB64.trim()).sorted()
        val combined = (sorted[0] + "|" + sorted[1]).toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(combined)
        return (0 until 18 step 3).joinToString(" ") { i ->
            val v = ((digest[i].toInt() and 0xFF) shl 16) or
                ((digest[i + 1].toInt() and 0xFF) shl 8) or
                (digest[i + 2].toInt() and 0xFF)
            (v % 100000).toString().padStart(5, '0')
        }
    }

    fun encryptForRecipients(
        context: Context,
        senderUid: String,
        plaintext: String,
        recipientPublicKeysByUid: Map<String, String>
    ): EncryptedMessagePayload? {
        if (senderUid.isBlank() || plaintext.isEmpty()) return null
        val myKp = getOrCreateKeyPair(context, senderUid) ?: return null
        val senderPubB64 = Base64.encodeToString(myKp.public.encoded, Base64.NO_WRAP)

        val targets = recipientPublicKeysByUid.toMutableMap()
        if (!targets.containsKey(senderUid)) {
            targets[senderUid] = senderPubB64
        }
        val validTargets = targets.filterValues { it.isNotBlank() }
        if (validTargets.isEmpty()) return null

        val dek = ByteArray(32).also { random.nextBytes(it) }
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val contentCipher = Cipher.getInstance("AES/GCM/NoPadding")
        contentCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = contentCipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val envelopes = mutableMapOf<String, String>()
        validTargets.forEach { (uid, pubB64) ->
            val peerPub = decodePublicKey(pubB64) ?: return@forEach
            val wrapKey = deriveSharedAesKey(myKp.private, peerPub) ?: return@forEach
            val wrapIv = ByteArray(12).also { random.nextBytes(it) }
            val wrapCipher = Cipher.getInstance("AES/GCM/NoPadding")
            wrapCipher.init(Cipher.ENCRYPT_MODE, wrapKey, GCMParameterSpec(GCM_TAG_BITS, wrapIv))
            val wrapped = wrapCipher.doFinal(dek)
            val packed = ByteArray(12 + wrapped.size)
            System.arraycopy(wrapIv, 0, packed, 0, 12)
            System.arraycopy(wrapped, 0, packed, 12, wrapped.size)
            envelopes[uid] = Base64.encodeToString(packed, Base64.NO_WRAP)
        }
        if (envelopes.isEmpty()) return null

        return EncryptedMessagePayload(
            ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP),
            senderPublicKeyBase64 = senderPubB64,
            envelopes = envelopes,
            algo = ALGO_ID
        )
    }

    fun decryptMessageIfNeeded(
        context: Context,
        myUid: String,
        message: ChatMessage
    ): ChatMessage {
        if (!message.isEncrypted || message.e2eeCiphertext.isBlank() || message.isDeletedForEveryone) {
            return message
        }
        val plain = decryptPayload(
            context = context,
            myUid = myUid,
            ciphertextB64 = message.e2eeCiphertext,
            ivB64 = message.e2eeIv,
            senderPublicKeyB64 = message.e2eeSenderPublicKey,
            envelopes = message.e2eeKeyEnvelopes
        ) ?: return message
        return message.copy(body = plain)
    }

    fun decryptPayload(
        context: Context,
        myUid: String,
        ciphertextB64: String,
        ivB64: String,
        senderPublicKeyB64: String,
        envelopes: Map<String, String>
    ): String? = runCatching {
        if (myUid.isBlank()) return null
        val packedEnvelopeB64 = envelopes[myUid]?.takeIf { it.isNotBlank() } ?: return null
        val myKp = getOrCreateKeyPair(context, myUid) ?: return null
        val senderPub = decodePublicKey(senderPublicKeyB64) ?: return null
        val unwrapKey = deriveSharedAesKey(myKp.private, senderPub) ?: return null

        val packed = Base64.decode(packedEnvelopeB64, Base64.NO_WRAP)
        if (packed.size <= 12) return null
        val wrapIv = packed.copyOfRange(0, 12)
        val wrappedDek = packed.copyOfRange(12, packed.size)

        val unwrapCipher = Cipher.getInstance("AES/GCM/NoPadding")
        unwrapCipher.init(Cipher.DECRYPT_MODE, unwrapKey, GCMParameterSpec(GCM_TAG_BITS, wrapIv))
        val dek = unwrapCipher.doFinal(wrappedDek)

        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val ciphertext = Base64.decode(ciphertextB64, Base64.NO_WRAP)
        val contentCipher = Cipher.getInstance("AES/GCM/NoPadding")
        contentCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(contentCipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()

    private fun deriveSharedAesKey(myPrivate: PrivateKey, peerPublic: PublicKey): SecretKey? = runCatching {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(myPrivate)
        ka.doPhase(peerPublic, true)
        val shared = ka.generateSecret()
        // HKDF-like expand with HMAC-SHA256
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("XMAX-E2EE-HKDF-SALT-v1".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val prk = mac.doFinal(shared)
        val expandMac = Mac.getInstance("HmacSHA256")
        expandMac.init(SecretKeySpec(prk, "HmacSHA256"))
        val okm = expandMac.doFinal("XMAX-E2EE-KEY-WRAP-v1\u0001".toByteArray(Charsets.UTF_8))
        SecretKeySpec(okm.copyOfRange(0, 32), "AES")
    }.getOrNull()

    private fun decodePublicKey(publicKeyB64: String): PublicKey? = runCatching {
        val bytes = Base64.decode(publicKeyB64.trim(), Base64.NO_WRAP)
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }.getOrNull()

    @Synchronized
    private fun getOrCreateKeyPair(context: Context, uid: String): KeyPair? {
        val safeUid = uid.trim().replace(Regex("[^A-Za-z0-9_-]"), "_")
        if (safeUid.isBlank()) return null
        val sp = context.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val pubKeyName = "pub_$safeUid"
        val privKeyName = "priv_$safeUid"
        val privIvName = "priv_iv_$safeUid"

        val storedPubB64 = sp.getString(pubKeyName, null)
        val storedPrivB64 = sp.getString(privKeyName, null)
        if (!storedPubB64.isNullOrBlank() && !storedPrivB64.isNullOrBlank()) {
            val loaded = runCatching {
                val pub = decodePublicKey(storedPubB64) ?: error("Bad pub")
                val privIvB64 = sp.getString(privIvName, null)
                val privRaw = if (!privIvB64.isNullOrBlank()) {
                    unwrapWithAndroidKeyStore(safeUid, Base64.decode(storedPrivB64, Base64.NO_WRAP), Base64.decode(privIvB64, Base64.NO_WRAP))
                } else {
                    Base64.decode(storedPrivB64, Base64.NO_WRAP)
                }
                val priv = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privRaw))
                KeyPair(pub, priv)
            }.getOrNull()
            if (loaded != null) return loaded
        }

        // Generate fresh P-256 ECDH keypair
        return runCatching {
            val gen = KeyPairGenerator.getInstance("EC")
            gen.initialize(ECGenParameterSpec("secp256r1"), random)
            val kp = gen.generateKeyPair()
            val pubB64 = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
            val privBytes = kp.private.encoded
            val wrapped = wrapWithAndroidKeyStore(safeUid, privBytes)
            val edit = sp.edit().putString(pubKeyName, pubB64)
            if (wrapped != null) {
                edit.putString(privKeyName, Base64.encodeToString(wrapped.first, Base64.NO_WRAP))
                edit.putString(privIvName, Base64.encodeToString(wrapped.second, Base64.NO_WRAP))
            } else {
                // Fallback on headless JVM/test where AndroidKeyStore is absent
                edit.putString(privKeyName, Base64.encodeToString(privBytes, Base64.NO_WRAP))
                edit.remove(privIvName)
            }
            edit.apply()
            kp
        }.getOrNull()
    }

    private fun getOrCreateKeystoreWrapKey(safeUid: String): SecretKey? = runCatching {
        val alias = "$KEYSTORE_WRAP_ALIAS_PREFIX$safeUid"
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return@runCatching it }
        val keyGen = javax.crypto.KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        keyGen.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        keyGen.generateKey()
    }.getOrNull()

    private fun wrapWithAndroidKeyStore(safeUid: String, raw: ByteArray): Pair<ByteArray, ByteArray>? = runCatching {
        val key = getOrCreateKeystoreWrapKey(safeUid) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.doFinal(raw) to cipher.iv
    }.getOrNull()

    private fun unwrapWithAndroidKeyStore(safeUid: String, enc: ByteArray, iv: ByteArray): ByteArray {
        val key = getOrCreateKeystoreWrapKey(safeUid) ?: error("Missing keystore key")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(enc)
    }
}
