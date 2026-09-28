package org.multipaz.openid4vci.admin

import io.ktor.http.Cookie
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.crypto.Crypto
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.rpc.handler.SimpleCipher
import org.multipaz.server.common.baseUrl
import org.multipaz.util.Logger
import org.multipaz.util.toBase64Url
import java.net.URI
import java.net.URISyntaxException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Cookie carrying the opaque, revocable admin session id. See [AdminSessionRecord]. */
const val ADMIN_SESSION_COOKIE = "validatopia_admin_session"

/** Header a JS client must echo the session's CSRF token in for state-changing admin requests. */
const val ADMIN_CSRF_HEADER = "X-Admin-Csrf"

private const val TAG = "AdminAuth"
private const val SESSION_TTL_SECONDS = 12L * 60 * 60
private const val LOCKOUT_THRESHOLD = 3
private const val LOCKOUT_BASE_SECONDS = 30L
private const val LOCKOUT_MAX_SECONDS = 15L * 60
private const val TOTP_ISSUER = "Validatopia"
private const val DEFAULT_BOOTSTRAP_USERNAME = "admin"

/** Outcome of [AdminAuth.login]/[AdminAuth.completeTotp]. */
sealed class LoginResult {
    /** Password (and, if applicable, TOTP code) accepted; a session was created. */
    data class Success(val sessionId: String, val csrfToken: String, val expiresInSeconds: Long) : LoginResult()

    /** Password accepted; the account has no confirmed TOTP secret yet and must enroll one. */
    data class NeedsTotpEnrollment(
        val username: String,
        val secretBase32: String,
        val provisioningUri: String
    ) : LoginResult()

    /** Password accepted; a TOTP code from the account's already-enrolled authenticator is needed. */
    data class NeedsTotpCode(val username: String) : LoginResult()

    /** Too many recent failures; retry after this many seconds. */
    data class LockedOut(val retryAfterSeconds: Long) : LoginResult()

    /** Wrong username, password or TOTP code (deliberately not distinguished, to avoid enumeration). */
    object InvalidCredentials : LoginResult()
}

/** A validated admin session, as returned by [AdminAuth.requireSession]. */
data class AdminSessionContext(val sessionId: String, val username: String, val csrfToken: String)

/**
 * Orchestrates admin authentication (`docs/validatopia/PLAN.md`'s Component E): Argon2id password
 * verification, mandatory TOTP, server-side revocable sessions, CSRF, failed-login lockout with
 * increasing delay, and an optional IP allow-list.
 */
object AdminAuth {
    /**
     * Ensures at least one admin account exists, creating one from `admin_bootstrap_user`/
     * `admin_bootstrap_pass` configuration (the `ADMIN_BOOTSTRAP_USER`/`ADMIN_BOOTSTRAP_PASS`
     * environment variables, per `start-servers.sh`) if the account table is empty.
     *
     * Refuses to bootstrap with an empty password unless `base_url` is a loopback address: a
     * second, JVM-level check alongside `start-servers.sh`'s shell-level one, so a container
     * started some other way still fails closed rather than serving a guessable admin account
     * publicly.
     *
     * @throws IllegalStateException if bootstrapping is required but unsafe (see above).
     */
    suspend fun ensureBootstrapped(configuration: Configuration) {
        if (AdminAccountRecord.count() > 0) {
            return
        }
        val username = configuration.getValue("admin_bootstrap_user")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_BOOTSTRAP_USERNAME
        var password = configuration.getValue("admin_bootstrap_pass")?.takeIf { it.isNotBlank() }
        if (password == null) {
            val host = hostOf(configuration.baseUrl)
            if (!isLoopbackHost(host)) {
                throw IllegalStateException(
                    "Refusing to start: no admin account exists and 'admin_bootstrap_pass' " +
                        "(ADMIN_BOOTSTRAP_PASS) is not set, but base_url ('${configuration.baseUrl}') " +
                        "is not a loopback address. Public deployments must set an explicit bootstrap " +
                        "password."
                )
            }
            password = Crypto.secureRandom.nextBytes(15).toBase64Url()
            Logger.e(TAG, "No 'admin_bootstrap_pass' set, generated one-time password for " +
                "'$username': '$password'")
        }
        createAccountInternal(username, password.toCharArray())
        Logger.i(TAG, "Bootstrapped admin account '$username'; TOTP enrollment required on first login")
        AdminActionLogRecord.record(username = null, action = "account_bootstrapped", detail = username)
    }

    /** Step 1 of login: verifies the password and reports what (if anything) is needed next. */
    suspend fun login(username: String, password: CharArray, clientIp: String): LoginResult {
        val account = AdminAccountRecord.get(username)
        if (account == null) {
            // Burn roughly the same time as a real verification, so account existence can't be
            // inferred from response latency.
            PasswordHashing.verify(password, DUMMY_HASH)
            AdminActionLogRecord.record(null, "login_failed", "unknown username, ip=$clientIp")
            return LoginResult.InvalidCredentials
        }
        val now = Clock.System.now()
        account.lockedUntil?.let { lockedUntil ->
            if (now < lockedUntil) {
                return LoginResult.LockedOut((lockedUntil - now).inWholeSeconds.coerceAtLeast(1))
            }
        }
        val stored = PasswordHashing.Hash(
            salt = account.passwordSalt.toByteArray(),
            hash = account.passwordHash.toByteArray(),
            memoryKib = account.passwordMemoryKib,
            iterations = account.passwordIterations,
            parallelism = account.passwordParallelism,
        )
        if (!PasswordHashing.verify(password, stored)) {
            return recordFailureAndMaybeLock(account, clientIp)
        }
        // Password correct: don't reset the failure counter yet, TOTP still has to check out
        // (except for first-time enrollment, which has no code to check yet).
        if (!account.totpConfirmed) {
            val secret = decryptTotpSecret(account.totpSecretEncrypted)
            return LoginResult.NeedsTotpEnrollment(
                username = account.username,
                secretBase32 = Base32.encode(secret),
                provisioningUri = Totp.provisioningUri(secret, account.username, TOTP_ISSUER),
            )
        }
        return LoginResult.NeedsTotpCode(account.username)
    }

    /** Step 2 of login: verifies the TOTP code (confirming enrollment if this is the first one). */
    suspend fun completeTotp(username: String, code: String, clientIp: String): LoginResult {
        val account = AdminAccountRecord.get(username) ?: return LoginResult.InvalidCredentials
        val now = Clock.System.now()
        account.lockedUntil?.let { lockedUntil ->
            if (now < lockedUntil) {
                return LoginResult.LockedOut((lockedUntil - now).inWholeSeconds.coerceAtLeast(1))
            }
        }
        val secret = decryptTotpSecret(account.totpSecretEncrypted)
        if (!Totp.verifyCode(secret, code, now.epochSeconds)) {
            return recordFailureAndMaybeLock(account, clientIp)
        }
        if (!account.totpConfirmed) {
            AdminAccountRecord.put(account.copy(totpConfirmed = true, failedAttempts = 0, lockedUntil = null))
            AdminActionLogRecord.record(account.username, "totp_enrolled", "ip=$clientIp")
        } else if (account.failedAttempts != 0 || account.lockedUntil != null) {
            AdminAccountRecord.put(account.copy(failedAttempts = 0, lockedUntil = null))
        }
        val (sessionId, session) = AdminSessionRecord.create(account.username, SESSION_TTL_SECONDS)
        AdminActionLogRecord.record(account.username, "login_success", "ip=$clientIp")
        return LoginResult.Success(sessionId, session.csrfToken, SESSION_TTL_SECONDS)
    }

    /** Issues the session cookie for a successful [LoginResult.Success]. */
    suspend fun setSessionCookie(call: ApplicationCall, result: LoginResult.Success) {
        call.response.cookies.append(sessionCookie(result.sessionId, result.expiresInSeconds))
    }

    /** Clears the session cookie (used on logout, independent of whether the session existed). */
    suspend fun clearSessionCookie(call: ApplicationCall) {
        call.response.cookies.append(sessionCookie("", 0))
    }

    suspend fun logout(call: ApplicationCall) {
        val sessionId = call.request.cookies[ADMIN_SESSION_COOKIE]
        if (sessionId != null) {
            AdminSessionRecord.get(sessionId)?.let { session ->
                AdminActionLogRecord.record(session.username, "logout", "")
            }
            AdminSessionRecord.revoke(sessionId)
        }
        clearSessionCookie(call)
    }

    /**
     * Validates the request's session cookie (and, if [requireCsrf], the [ADMIN_CSRF_HEADER])
     * and the IP allow-list. On failure, writes the appropriate error response and returns `null`
     * — callers should `return` immediately when that happens, mirroring
     * `request/idv.kt`'s `identityProofingOrNotFound`.
     */
    suspend fun requireSession(call: ApplicationCall, requireCsrf: Boolean): AdminSessionContext? {
        val configuration = BackendEnvironment.getInterface(Configuration::class)!!
        val allowList = AdminAllowList.parse(configuration.getValue("admin_allow_cidr"))
        val ip = clientIp(call)
        if (!AdminAllowList.isAllowed(allowList, ip)) {
            call.respondText(
                status = HttpStatusCode.Forbidden,
                text = errorJson("access_denied", "IP not allowed"),
                contentType = ContentType.Application.Json
            )
            return null
        }
        val sessionId = call.request.cookies[ADMIN_SESSION_COOKIE]
        if (sessionId == null) {
            call.respondText(
                status = HttpStatusCode.Unauthorized,
                text = errorJson("login_required", ""),
                contentType = ContentType.Application.Json
            )
            return null
        }
        val session = AdminSessionRecord.get(sessionId)
        if (session == null) {
            clearSessionCookie(call)
            call.respondText(
                status = HttpStatusCode.Unauthorized,
                text = errorJson("login_required", ""),
                contentType = ContentType.Application.Json
            )
            return null
        }
        if (requireCsrf) {
            val header = call.request.headers[ADMIN_CSRF_HEADER]
            if (header == null || !constantTimeEquals(header, session.csrfToken)) {
                call.respondText(
                    status = HttpStatusCode.Forbidden,
                    text = errorJson("csrf_invalid", ""),
                    contentType = ContentType.Application.Json
                )
                return null
            }
        }
        return AdminSessionContext(sessionId, session.username, session.csrfToken)
    }

    /**
     * Reads the caller's address from `X-Forwarded-For`.
     *
     * Takes the *last* entry, not the first: the bundled nginx (see
     * `nginx-validatopia-locations.conf`) only trusts `X-Forwarded-For` from private-network
     * peers (its own fronting reverse proxy, e.g. Caddy) and always appends its own
     * realip-resolved view of the client address as the final hop. Everything before that is
     * whatever the client itself claimed and cannot be trusted — a direct, unproxied caller can
     * put anything at all in this header, including "127.0.0.1". Taking the last entry means that
     * claim is ignored in favor of the address a trusted proxy actually observed.
     */
    fun clientIp(call: ApplicationCall): String {
        val forwarded = call.request.headers["X-Forwarded-For"]
        if (!forwarded.isNullOrBlank()) {
            return forwarded.split(",").last().trim()
        }
        return call.request.local.remoteHost
    }

    /** Whether [call] originates from the local machine (used to gate `/preauthorized_offer`). */
    fun isLoopbackCaller(call: ApplicationCall): Boolean = isLoopbackHost(clientIp(call))

    // --- Admin-account management, used by the "admin accounts" website page ---

    data class AccountSummary(
        val username: String,
        val createdAt: Instant,
        val totpConfirmed: Boolean,
        val locked: Boolean,
    )

    data class Enrollment(val username: String, val secretBase32: String, val provisioningUri: String)

    suspend fun listAccounts(): List<AccountSummary> {
        val now = Clock.System.now()
        return AdminAccountRecord.list().map {
            AccountSummary(
                username = it.username,
                createdAt = it.createdAt,
                totpConfirmed = it.totpConfirmed,
                locked = it.lockedUntil?.let { until -> now < until } ?: false,
            )
        }
    }

    /** Creates a new admin account with TOTP not yet confirmed; the new admin enrolls on first login. */
    suspend fun createAccount(username: String, initialPassword: CharArray, actor: String?): Enrollment {
        require(username.isNotBlank()) { "username must not be blank" }
        require(initialPassword.size >= MIN_PASSWORD_LENGTH) {
            "password must be at least $MIN_PASSWORD_LENGTH characters"
        }
        require(AdminAccountRecord.get(username) == null) { "account '$username' already exists" }
        val enrollment = createAccountInternal(username, initialPassword)
        AdminActionLogRecord.record(actor, "account_created", username)
        return enrollment
    }

    suspend fun deleteAccount(username: String, actor: String?) {
        require(AdminAccountRecord.count() > 1) { "cannot remove the last admin account" }
        if (AdminAccountRecord.delete(username)) {
            AdminSessionRecord.revokeAllForUser(username)
            AdminActionLogRecord.record(actor, "account_deleted", username)
        }
    }

    /** Regenerates the account's TOTP secret and revokes its sessions; re-enrollment is required next login. */
    suspend fun resetTotp(username: String, actor: String?): Enrollment {
        val account = AdminAccountRecord.get(username)
            ?: throw IllegalArgumentException("no such account '$username'")
        val secret = Totp.generateSecret()
        val cipher = BackendEnvironment.getInterface(SimpleCipher::class)!!
        AdminAccountRecord.put(
            account.copy(
                totpSecretEncrypted = ByteString(cipher.encrypt(secret)),
                totpConfirmed = false,
            )
        )
        AdminSessionRecord.revokeAllForUser(username)
        AdminActionLogRecord.record(actor, "totp_reset", username)
        return Enrollment(
            username = account.username,
            secretBase32 = Base32.encode(secret),
            provisioningUri = Totp.provisioningUri(secret, account.username, TOTP_ISSUER),
        )
    }

    private suspend fun createAccountInternal(username: String, password: CharArray): Enrollment {
        val normalized = username.trim()
        val hash = PasswordHashing.hash(password)
        password.fill('\u0000')
        val secret = Totp.generateSecret()
        val cipher = BackendEnvironment.getInterface(SimpleCipher::class)!!
        AdminAccountRecord.put(
            AdminAccountRecord(
                username = normalized,
                passwordSalt = ByteString(hash.salt),
                passwordHash = ByteString(hash.hash),
                passwordMemoryKib = hash.memoryKib,
                passwordIterations = hash.iterations,
                passwordParallelism = hash.parallelism,
                totpSecretEncrypted = ByteString(cipher.encrypt(secret)),
                totpConfirmed = false,
                createdAt = Clock.System.now(),
            )
        )
        return Enrollment(
            username = normalized,
            secretBase32 = Base32.encode(secret),
            provisioningUri = Totp.provisioningUri(secret, normalized, TOTP_ISSUER),
        )
    }

    private suspend fun recordFailureAndMaybeLock(account: AdminAccountRecord, clientIp: String): LoginResult {
        val attempts = account.failedAttempts + 1
        val lockedUntil = if (attempts >= LOCKOUT_THRESHOLD) {
            val delaySeconds = (LOCKOUT_BASE_SECONDS shl (attempts - LOCKOUT_THRESHOLD))
                .coerceAtMost(LOCKOUT_MAX_SECONDS)
            Clock.System.now() + delaySeconds.seconds
        } else {
            null
        }
        AdminAccountRecord.put(account.copy(failedAttempts = attempts, lockedUntil = lockedUntil))
        AdminActionLogRecord.record(account.username, "login_failed", "ip=$clientIp, attempt=$attempts")
        return if (lockedUntil != null) {
            LoginResult.LockedOut((lockedUntil - Clock.System.now()).inWholeSeconds.coerceAtLeast(1))
        } else {
            LoginResult.InvalidCredentials
        }
    }

    private suspend fun decryptTotpSecret(encrypted: ByteString): ByteArray {
        val cipher = BackendEnvironment.getInterface(SimpleCipher::class)!!
        return cipher.decrypt(encrypted.toByteArray())
    }

    private suspend fun sessionCookie(value: String, maxAgeSeconds: Long): Cookie {
        val configuration = BackendEnvironment.getInterface(Configuration::class)
        val secure = configuration?.baseUrl?.startsWith("https://") ?: true
        return Cookie(
            name = ADMIN_SESSION_COOKIE,
            value = value,
            path = "/",
            maxAge = maxAgeSeconds.toInt(),
            httpOnly = true,
            secure = secure,
            extensions = mapOf("SameSite" to "Strict"),
        )
    }

    private fun errorJson(error: String, description: String): String = buildJsonObject {
        put("error", error)
        put("error_description", description)
    }.toString()

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }

    private fun hostOf(url: String): String = try {
        URI(url).host ?: ""
    } catch (_: URISyntaxException) {
        ""
    }

    private fun isLoopbackHost(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "::1" || host.isEmpty()

    private const val MIN_PASSWORD_LENGTH = 12

    // A fixed, never-matching Argon2id hash, verified against on an unknown username so failed
    // lookups take about as long as a real verification (mitigates username enumeration by timing).
    private val DUMMY_HASH = PasswordHashing.Hash(
        salt = ByteArray(16),
        hash = ByteArray(32),
        memoryKib = 19 * 1024,
        iterations = 2,
        parallelism = 1,
    )
}
