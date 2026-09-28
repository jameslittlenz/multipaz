package org.multipaz.openid4vci.admin

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.multipaz.crypto.Crypto
import kotlin.random.Random

/**
 * Argon2id password hashing for admin accounts (`docs/validatopia/PLAN.md`'s Component E: "Argon2id
 * or PBKDF2 password hashes"). Parameters follow the OWASP Password Storage Cheat Sheet's Argon2id
 * recommendation (19 MiB memory, 2 iterations, 1 degree of parallelism) so a single hash is cheap
 * enough for interactive login but expensive enough to resist offline cracking of a leaked table.
 */
object PasswordHashing {
    private const val MEMORY_KIB = 19 * 1024
    private const val ITERATIONS = 2
    private const val PARALLELISM = 1
    private const val SALT_SIZE = 16
    private const val HASH_SIZE = 32

    /** The persisted form of a hashed password; parameters are stored alongside so they can be tuned later. */
    data class Hash(
        val salt: ByteArray,
        val hash: ByteArray,
        val memoryKib: Int,
        val iterations: Int,
        val parallelism: Int,
    )

    /** Hashes [password] with a freshly generated salt. The caller owns [password] and should zero it after use. */
    fun hash(password: CharArray, random: Random = Crypto.secureRandom): Hash {
        val salt = random.nextBytes(SALT_SIZE)
        val digest = computeHash(password, salt, MEMORY_KIB, ITERATIONS, PARALLELISM)
        return Hash(salt, digest, MEMORY_KIB, ITERATIONS, PARALLELISM)
    }

    /** Constant-time verification of [password] against a previously computed [stored] hash. */
    fun verify(password: CharArray, stored: Hash): Boolean {
        val computed = computeHash(password, stored.salt, stored.memoryKib, stored.iterations, stored.parallelism)
        return constantTimeEquals(computed, stored.hash)
    }

    private fun computeHash(
        password: CharArray,
        salt: ByteArray,
        memoryKib: Int,
        iterations: Int,
        parallelism: Int
    ): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memoryKib)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .withSalt(salt)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(params)
        val out = ByteArray(HASH_SIZE)
        generator.generateBytes(password, out)
        return out
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) {
            return false
        }
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }
}
