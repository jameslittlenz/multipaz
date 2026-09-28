package org.multipaz.openid4vci.admin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PasswordHashingTest {
    @Test
    fun verifyAcceptsTheCorrectPassword() {
        val hash = PasswordHashing.hash("correct horse battery staple".toCharArray(), Random(1))
        assertTrue(PasswordHashing.verify("correct horse battery staple".toCharArray(), hash))
    }

    @Test
    fun verifyRejectsTheWrongPassword() {
        val hash = PasswordHashing.hash("correct horse battery staple".toCharArray(), Random(1))
        assertFalse(PasswordHashing.verify("wrong password".toCharArray(), hash))
    }

    @Test
    fun verifyRejectsAnEmptyPasswordAgainstANonEmptyHash() {
        val hash = PasswordHashing.hash("correct horse battery staple".toCharArray(), Random(1))
        assertFalse(PasswordHashing.verify("".toCharArray(), hash))
    }

    @Test
    fun distinctHashesUseDistinctSalts() {
        val a = PasswordHashing.hash("same password".toCharArray(), Random(1))
        val b = PasswordHashing.hash("same password".toCharArray(), Random(2))
        assertNotEquals(a.salt.toList(), b.salt.toList())
        assertNotEquals(a.hash.toList(), b.hash.toList())
    }
}
