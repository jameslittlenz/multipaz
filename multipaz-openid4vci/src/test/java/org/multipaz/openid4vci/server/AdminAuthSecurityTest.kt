package org.multipaz.openid4vci.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.multipaz.openid4vci.admin.ADMIN_CSRF_HEADER
import org.multipaz.openid4vci.admin.ADMIN_SESSION_COOKIE
import org.multipaz.openid4vci.admin.AdminAuth
import org.multipaz.openid4vci.admin.Base32
import org.multipaz.server.common.ServerConfiguration
import org.multipaz.server.common.ServerEnvironment
import org.multipaz.server.common.installServerEnvironment
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Security tests for the admin login/session mechanism (`docs/validatopia/PLAN.md`'s Component E):
 * TOTP enrollment, lockout, CSRF enforcement, cookie flags, and the IP allow-list. Mirrors
 * `ProvisioningClientTest`'s in-process server harness.
 */
class AdminAuthSecurityTest {
    private fun buildEnvironment(vararg extraArgs: String): kotlinx.coroutines.Deferred<ServerEnvironment> {
        val configuration = ServerConfiguration(
            arrayOf("-param", "base_url=http://localhost", "-param", "database_engine=ephemeral") + extraArgs
        )
        return ServerEnvironment.create(configuration) {}
    }

    @Test
    fun loginWithUnknownUsernameIsRejected() = testApplication {
        val serverEnvironment = buildEnvironment()
        application {
            installServerEnvironment(serverEnvironment)
            configureRouting(serverEnvironment)
        }
        val client = createClient {}
        val response = client.post("http://localhost/admin_login") {
            header("Content-Type", "application/json")
            setBody("""{"username":"nobody","password":"whatever"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("auth_failed", bodyJson(response)["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun fullLoginFlowGrantsASessionWithHardenedCookieFlags() = testApplication {
        val serverEnvironment = buildEnvironment()
        application {
            installServerEnvironment(serverEnvironment)
            configureRouting(serverEnvironment)
        }
        withContext(serverEnvironment.await()) {
            AdminAuth.createAccount("admin", "correct horse battery staple".toCharArray(), actor = null)
        }
        val client = createClient {}

        val step1 = client.post("http://localhost/admin_login") {
            header("Content-Type", "application/json")
            setBody("""{"username":"admin","password":"correct horse battery staple"}""")
        }
        val step1Body = bodyJson(step1)
        assertEquals("totp_enrollment_required", step1Body["status"]?.jsonPrimitive?.content)
        val secret = step1Body["secret"]!!.jsonPrimitive.content

        val step2 = client.post("http://localhost/admin_login_totp") {
            header("Content-Type", "application/json")
            setBody("""{"username":"admin","code":"${totpCodeNow(secret)}"}""")
        }
        val step2Body = bodyJson(step2)
        assertEquals("ok", step2Body["status"]?.jsonPrimitive?.content)
        assertTrue(step2Body["csrf_token"]!!.jsonPrimitive.content.isNotEmpty())

        val setCookie = step2.headers["Set-Cookie"]
        assertNotNull(setCookie)
        assertTrue(setCookie!!.contains("$ADMIN_SESSION_COOKIE="))
        assertTrue("cookie should be HttpOnly", setCookie.contains("HttpOnly", ignoreCase = true))
        assertTrue("cookie should be SameSite=Strict", setCookie.contains("SameSite=Strict", ignoreCase = true))
        // base_url is http://localhost in this test, so the Secure flag must NOT be set (or the
        // browser would refuse to send the cookie back at all over plain HTTP).
        assertFalse("cookie should not be Secure over http base_url", setCookie.contains("Secure", ignoreCase = true))

        val cookieHeader = setCookie.substringBefore(';')
        val csrfToken = step2Body["csrf_token"]!!.jsonPrimitive.content

        // A GET (read-only) admin endpoint works with just the session cookie.
        val listSessions = client.get("http://localhost/admin_list_sessions") {
            header("Cookie", cookieHeader)
        }
        assertEquals(HttpStatusCode.OK, listSessions.status)

        // The same mutating endpoint without a CSRF header is rejected...
        val withoutCsrf = client.post("http://localhost/admin_set_credential_status") {
            header("Cookie", cookieHeader)
            header("Content-Type", "application/json")
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, withoutCsrf.status)
        assertEquals("csrf_invalid", bodyJson(withoutCsrf)["error"]?.jsonPrimitive?.content)

        // ...but proceeds (past the CSRF check, failing later on the missing 'bucket' parameter
        // instead — proof the CSRF gate itself passed) once the header is present.
        val withCsrf = client.post("http://localhost/admin_set_credential_status") {
            header("Cookie", cookieHeader)
            header(ADMIN_CSRF_HEADER, csrfToken)
            header("Content-Type", "application/json")
            setBody("{}")
        }
        assertEquals(HttpStatusCode.BadRequest, withCsrf.status)
        assertEquals("invalid_request", bodyJson(withCsrf)["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun protectedEndpointRejectsAMissingOrUnknownSession() = testApplication {
        val serverEnvironment = buildEnvironment()
        application {
            installServerEnvironment(serverEnvironment)
            configureRouting(serverEnvironment)
        }
        val client = createClient {}

        val noCookie = client.get("http://localhost/admin_list_sessions")
        assertEquals(HttpStatusCode.Unauthorized, noCookie.status)

        val unknownCookie = client.get("http://localhost/admin_list_sessions") {
            header("Cookie", "$ADMIN_SESSION_COOKIE=not-a-real-session")
        }
        assertEquals(HttpStatusCode.Unauthorized, unknownCookie.status)
    }

    @Test
    fun repeatedFailedLoginsLockTheAccountOutWithIncreasingDelay() = testApplication {
        val serverEnvironment = buildEnvironment()
        application {
            installServerEnvironment(serverEnvironment)
            configureRouting(serverEnvironment)
        }
        withContext(serverEnvironment.await()) {
            AdminAuth.createAccount("admin", "correct horse battery staple".toCharArray(), actor = null)
        }
        val client = createClient {}

        suspend fun attempt(password: String): JsonObject {
            val response = client.post("http://localhost/admin_login") {
                header("Content-Type", "application/json")
                setBody("""{"username":"admin","password":"$password"}""")
            }
            return bodyJson(response)
        }

        // The first two failures are just rejected (below the lockout threshold).
        assertEquals("auth_failed", attempt("wrong password")["error"]?.jsonPrimitive?.content)
        assertEquals("auth_failed", attempt("wrong password")["error"]?.jsonPrimitive?.content)
        // The third crosses the threshold and locks the account with a positive delay.
        val locked = attempt("wrong password")
        assertEquals("locked", locked["status"]?.jsonPrimitive?.content)
        assertTrue((locked["retry_after_seconds"]?.jsonPrimitive?.long ?: 0) > 0)

        // The correct password is rejected too while locked out, not just wrong ones.
        val whileLocked = attempt("correct horse battery staple")
        assertEquals("locked", whileLocked["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun adminAllowCidrDeniesRequestsFromOutsideTheConfiguredRange() = testApplication {
        // Deliberately a range the test client's own address can never be in.
        val serverEnvironment = buildEnvironment("-param", "admin_allow_cidr=203.0.113.0/24")
        application {
            installServerEnvironment(serverEnvironment)
            configureRouting(serverEnvironment)
        }
        val client = createClient {}
        val response = client.get("http://localhost/admin_list_sessions")
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("access_denied", bodyJson(response)["error"]?.jsonPrimitive?.content)
    }

    private suspend fun bodyJson(response: io.ktor.client.statement.HttpResponse): JsonObject =
        Json.parseToJsonElement(response.readRawBytes().decodeToString()) as JsonObject

    /** Computes the current TOTP code for a base32 secret, matching `Totp`'s algorithm. */
    private fun totpCodeNow(secretBase32: String): String {
        val secret = Base32.decode(secretBase32)
        val counter = System.currentTimeMillis() / 1000 / 30
        val data = ByteArray(8)
        var value = counter
        for (i in 7 downTo 0) {
            data[i] = (value and 0xff).toByte()
            value = value shr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val hash = mac.doFinal(data)
        val offset = hash[hash.size - 1].toInt() and 0xf
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }
}
