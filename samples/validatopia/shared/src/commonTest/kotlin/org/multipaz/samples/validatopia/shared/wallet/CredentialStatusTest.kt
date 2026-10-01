package org.multipaz.samples.validatopia.shared.wallet

import org.multipaz.revocation.RevocationCheckState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class CredentialStatusTest {
    private val from = Instant.parse("2026-01-01T00:00:00Z")
    private val until = Instant.parse("2027-01-01T00:00:00Z")
    private val during = from + 100.days
    private val after = until + 1.days
    private val before = from - 1.days

    private fun status(revocation: RevocationCheckState?, at: Instant) =
        CredentialStatus.of(validFrom = from, validUntil = until, revocation = revocation, at = at)

    @Test
    fun validWithinItsDatesWhenNotRevoked() {
        assertEquals(CredentialStatus.VALID, status(RevocationCheckState.VALID, during))
    }

    @Test
    fun uncheckedOrUnknownRevocationFallsBackToTheDates() {
        for (revocation in listOf(null, RevocationCheckState.UNKNOWN)) {
            assertEquals(CredentialStatus.VALID, status(revocation, during))
            assertEquals(CredentialStatus.EXPIRED, status(revocation, after))
            assertEquals(CredentialStatus.NOT_YET_VALID, status(revocation, before))
        }
    }

    @Test
    fun revokedOutranksEverything() {
        for (at in listOf(before, during, after)) {
            assertEquals(CredentialStatus.REVOKED, status(RevocationCheckState.INVALID, at))
        }
    }

    @Test
    fun suspendedOutranksTheDates() {
        for (at in listOf(before, during, after)) {
            assertEquals(CredentialStatus.SUSPENDED, status(RevocationCheckState.SUSPENDED, at))
        }
    }
}
