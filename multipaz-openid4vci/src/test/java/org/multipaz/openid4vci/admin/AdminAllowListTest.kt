package org.multipaz.openid4vci.admin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminAllowListTest {
    @Test
    fun nullConfigurationMeansUnrestricted() {
        assertTrue(AdminAllowList.isAllowed(null, "203.0.113.7"))
    }

    @Test
    fun matchesAnAddressInsideACidrBlock() {
        val entries = AdminAllowList.parse("10.0.0.0/8")
        assertTrue(AdminAllowList.isAllowed(entries, "10.1.2.3"))
        assertFalse(AdminAllowList.isAllowed(entries, "11.1.2.3"))
    }

    @Test
    fun matchesABareAddressExactly() {
        val entries = AdminAllowList.parse("192.168.1.5")
        assertTrue(AdminAllowList.isAllowed(entries, "192.168.1.5"))
        assertFalse(AdminAllowList.isAllowed(entries, "192.168.1.6"))
    }

    @Test
    fun supportsMultipleCommaSeparatedEntries() {
        val entries = AdminAllowList.parse("127.0.0.1, 10.0.0.0/8")
        assertTrue(AdminAllowList.isAllowed(entries, "127.0.0.1"))
        assertTrue(AdminAllowList.isAllowed(entries, "10.9.9.9"))
        assertFalse(AdminAllowList.isAllowed(entries, "8.8.8.8"))
    }

    @Test
    fun ipv4AndIpv6NeverCrossMatch() {
        val entries = AdminAllowList.parse("10.0.0.0/8")
        assertFalse(AdminAllowList.isAllowed(entries, "::1"))
    }

    @Test
    fun matchesAnIpv6CidrBlock() {
        val entries = AdminAllowList.parse("2001:db8::/32")
        assertTrue(AdminAllowList.isAllowed(entries, "2001:db8::1"))
        assertFalse(AdminAllowList.isAllowed(entries, "2001:db9::1"))
    }
}
