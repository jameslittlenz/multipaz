package org.multipaz.openid4vci.admin

import java.net.InetAddress

/**
 * Optional IP allow-list for admin endpoints (`ADMIN_ALLOW_CIDR`, `docs/validatopia/PLAN.md`'s
 * Component E). Supports IPv4/IPv6 CIDR blocks (e.g. `10.0.0.0/8`) and bare-address entries
 * (treated as a /32 or /128); entries are comma-separated.
 *
 * The client address is read from `X-Forwarded-For`, trusting nginx (the only reverse proxy in
 * front of this server in the container profile) to have set it; see `AdminAuth.clientIp`.
 */
object AdminAllowList {
    class Entry internal constructor(internal val address: ByteArray, internal val prefixBits: Int)

    /** Parses a comma-separated CIDR list, or returns `null` if [cidrList] is blank (no restriction). */
    fun parse(cidrList: String?): List<Entry>? {
        if (cidrList.isNullOrBlank()) {
            return null
        }
        return cidrList.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map(::parseEntry)
    }

    /** Whether [clientIp] is allowed. `entries == null` means no allow-list is configured. */
    fun isAllowed(entries: List<Entry>?, clientIp: String): Boolean {
        if (entries == null) {
            return true
        }
        val addr = try {
            InetAddress.getByName(clientIp).address
        } catch (_: java.net.UnknownHostException) {
            return false
        }
        return entries.any { matches(it, addr) }
    }

    private fun parseEntry(spec: String): Entry {
        val slash = spec.indexOf('/')
        val hostPart = if (slash >= 0) spec.substring(0, slash) else spec
        val addr = InetAddress.getByName(hostPart).address
        val prefixBits = if (slash >= 0) spec.substring(slash + 1).toInt() else addr.size * 8
        require(prefixBits in 0..(addr.size * 8)) { "Invalid CIDR prefix in '$spec'" }
        return Entry(addr, prefixBits)
    }

    private fun matches(entry: Entry, addr: ByteArray): Boolean {
        if (entry.address.size != addr.size) {
            return false // different address family (IPv4 vs IPv6): never matches
        }
        var bitsLeft = entry.prefixBits
        for (i in addr.indices) {
            if (bitsLeft <= 0) break
            val bits = minOf(8, bitsLeft)
            val mask = (0xff shl (8 - bits)) and 0xff
            if ((entry.address[i].toInt() and mask) != (addr[i].toInt() and mask)) {
                return false
            }
            bitsLeft -= bits
        }
        return true
    }
}
