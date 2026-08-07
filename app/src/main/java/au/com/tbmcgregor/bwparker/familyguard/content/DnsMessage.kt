package au.com.tbmcgregor.bwparker.familyguard.content

/** Minimal DNS wire-format parsing/building -- only what [VpnFilterService] needs. */
object DnsMessage {
    data class Query(val questionName: String)

    /**
     * Reads just the first question's name out of a query message. Doesn't handle name
     * compression (DNS pointer bytes) since a query's own question section is always the first
     * name in the message and has nothing earlier to point back to.
     */
    fun parseQuery(bytes: ByteArray): Query? {
        if (bytes.size < 12) return null
        val questionCount = readUInt16(bytes, 4)
        if (questionCount < 1) return null

        val name = StringBuilder()
        var offset = 12
        while (offset < bytes.size) {
            val labelLength = bytes[offset].toInt() and 0xFF
            offset += 1
            if (labelLength == 0) break
            if (offset + labelLength > bytes.size) return null
            if (name.isNotEmpty()) name.append('.')
            name.append(String(bytes, offset, labelLength, Charsets.US_ASCII))
            offset += labelLength
        }
        return name.toString().takeIf { it.isNotEmpty() }?.let { Query(it) }
    }

    /** Builds a minimal standalone "A" query for [name] -- used only by the cloud filter
     *  reachability probe (real client queries are relayed verbatim, never built here). */
    fun buildQuery(name: String): ByteArray {
        val header = byteArrayOf(
            0x12, 0x34, // arbitrary transaction ID
            0x01, 0x00, // flags: standard query, recursion desired
            0x00, 0x01, // QDCOUNT = 1
            0x00, 0x00, // ANCOUNT
            0x00, 0x00, // NSCOUNT
            0x00, 0x00, // ARCOUNT
        )
        val question = ArrayList<Byte>()
        name.split('.').forEach { label ->
            question.add(label.length.toByte())
            label.forEach { question.add(it.code.toByte()) }
        }
        question.add(0) // root label
        question.add(0x00); question.add(0x01) // QTYPE = A
        question.add(0x00); question.add(0x01) // QCLASS = IN
        return header + question.toByteArray()
    }

    /**
     * Pulls just the A-record (IPv4) answer addresses out of a DNS response -- used only to
     * populate [VpnFilterService]'s best-effort IP->hostname cache for CONNECT proxying (so the
     * proxy sees a real hostname instead of a bare IP when one's available); not
     * security-critical, since a parse failure here just means that cache misses, not that
     * filtering itself is skipped (the destination IP is still checked/relayed independently).
     */
    fun parseAnswerIPv4s(bytes: ByteArray): List<String> {
        if (bytes.size < 12) return emptyList()
        val questionCount = readUInt16(bytes, 4)
        val answerCount = readUInt16(bytes, 6)
        var offset = 12
        repeat(questionCount) {
            offset = skipName(bytes, offset) ?: return emptyList()
            offset += 4 // QTYPE + QCLASS
        }
        val addresses = mutableListOf<String>()
        repeat(answerCount) {
            offset = skipName(bytes, offset) ?: return addresses
            if (offset + 10 > bytes.size) return addresses
            val type = readUInt16(bytes, offset)
            val rdLength = readUInt16(bytes, offset + 8)
            offset += 10
            if (offset + rdLength > bytes.size) return addresses
            if (type == TYPE_A && rdLength == 4) {
                addresses.add(
                    "${bytes[offset].toInt() and 0xFF}.${bytes[offset + 1].toInt() and 0xFF}." +
                        "${bytes[offset + 2].toInt() and 0xFF}.${bytes[offset + 3].toInt() and 0xFF}",
                )
            }
            offset += rdLength
        }
        return addresses
    }

    /**
     * Advances past a (possibly compressed) DNS name starting at [offset], returning the offset of
     * the byte right after it, or null if malformed. Answer-section names are almost always a
     * 2-byte compression pointer back at the question -- handled here by treating the pointer
     * itself as the name's full on-wire length, since the caller only needs to skip past it, not
     * follow it to read the name it points to.
     */
    private fun skipName(bytes: ByteArray, start: Int): Int? {
        var offset = start
        while (offset < bytes.size) {
            val length = bytes[offset].toInt() and 0xFF
            if (length == 0) return offset + 1
            if (length and 0xC0 == 0xC0) return offset + 2
            offset += 1 + length
        }
        return null
    }

    private const val TYPE_A = 1

    /** Builds an NXDOMAIN response reusing the original query's header/question section. */
    fun buildBlockedResponse(queryBytes: ByteArray): ByteArray {
        if (queryBytes.size < 12) return queryBytes
        val response = queryBytes.copyOf()
        response[2] = (response[2].toInt() or 0x80).toByte() // set QR bit: this is a response
        response[3] = 0x83.toByte() // RA=1, RCODE=3 (NXDOMAIN)
        writeUInt16(response, 6, 0) // ANCOUNT
        writeUInt16(response, 8, 0) // NSCOUNT
        writeUInt16(response, 10, 0) // ARCOUNT
        return response
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun writeUInt16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 1] = (value and 0xFF).toByte()
    }
}
