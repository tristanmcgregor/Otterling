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
