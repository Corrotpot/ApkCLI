package dev.apkcli.core.util

/**
 * Is a host on this device or on a private network?
 *
 * Judged from the literal host string only. Resolving the name here would
 * block the caller, and the answer could change between this check and the
 * connection anyway, so a name is classified by its shape: an IP literal in a
 * private range, a single-label LAN name, or a suffix reserved for private use.
 *
 * Used to decide whether plain HTTP is acceptable. On a LAN it is the normal
 * case — LM Studio, a llama.cpp server, vLLM on a desktop — and refusing it
 * would make local models unusable. Across the internet it means the API key
 * travels in clear text, which is what the callers refuse.
 */
object NetworkHosts {

    fun isPrivateOrLocal(host: String): Boolean {
        val h = host.trim().trimEnd('.').lowercase().removePrefix("[").removeSuffix("]")
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".localhost")) return true

        ipv4(h)?.let { return isPrivateV4(it) }
        if (':' in h) return isPrivateV6(h)

        // "nas", "desktop", "gpu-box": only resolvable through LAN DNS or mDNS.
        if ('.' !in h) return true
        return PRIVATE_SUFFIXES.any { h.endsWith(it) }
    }

    /**
     * Suffixes that never reach the public DNS, plus Tailscale's MagicDNS
     * domain, whose traffic travels inside WireGuard even when the URL says
     * http. `.internal` was reserved for private use by ICANN in 2024.
     */
    private val PRIVATE_SUFFIXES = listOf(
        ".local", ".lan", ".home.arpa", ".internal", ".intranet", ".ts.net",
    )

    private fun ipv4(h: String): IntArray? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return null
            val value = part.toInt()
            if (value > 255) return null
            octets[i] = value
        }
        return octets
    }

    private fun isPrivateV4(o: IntArray): Boolean = when {
        o[0] == 10 -> true                              // 10.0.0.0/8
        o[0] == 127 -> true                             // loopback
        o[0] == 172 && o[1] in 16..31 -> true           // 172.16.0.0/12
        o[0] == 192 && o[1] == 168 -> true              // 192.168.0.0/16
        o[0] == 169 && o[1] == 254 -> true              // link-local
        o[0] == 100 && o[1] in 64..127 -> true          // CGNAT; Tailscale lives here
        else -> false
    }

    private fun isPrivateV6(h: String): Boolean {
        if (h == "::1") return true
        // IPv4-mapped (::ffff:192.168.1.5) defers to the IPv4 rules.
        h.substringAfterLast(':').let { tail ->
            if ('.' in tail) ipv4(tail)?.let { return isPrivateV4(it) }
        }
        val first = h.substringBefore(':')
        if (first.length < 2) return false
        val prefix = first.padStart(4, '0').take(2)
        return prefix == "fc" || prefix == "fd" ||                 // fc00::/7, unique local
            first.padStart(4, '0').take(3) in setOf("fe8", "fe9", "fea", "feb") // fe80::/10
    }
}
