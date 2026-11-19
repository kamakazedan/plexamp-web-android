package io.github.kamakazedan.plexampweb.downloads

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.kamakazedan.plexampweb.gui.GuiStore
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the downloads' account (the player's sign-in to your server: see DownloadAccount)
 * is kept, while something's downloaded: encrypted (AES-GCM), with a key the Android
 * Keystore holds and never lets out of it, in the app's own storage, which isn't backed
 * up. Damaged, or its key gone (a restored phone, say): forgotten, and the player gives
 * it again when it's next opened.
 */
class TokenVault(context: Context) {
    private val file = File(context.noBackupFilesDir, "downloads-account")
    private val lock = Any()

    fun read(): DownloadAccount? = synchronized(lock) {
        if (!file.isFile) return@synchronized null
        try {
            val bytes = file.readBytes()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(create = false) ?: return@synchronized null, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            DownloadAccount.fromJson(String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    fun write(account: DownloadAccount) = synchronized(lock) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        val sealed = cipher.doFinal(account.toJson().toByteArray(Charsets.UTF_8))
        file.parentFile?.mkdirs()
        GuiStore.writeAtomically(file, cipher.iv + sealed)
    }

    /** Forgotten: the file, and its key. */
    fun clear() = synchronized(lock) {
        file.delete()
        try {
            keyStore().deleteEntry(ALIAS)
        } catch (e: Exception) {
            // (None there.)
        }
    }

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun key(create: Boolean): SecretKey? {
        (keyStore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "downloads-account"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
