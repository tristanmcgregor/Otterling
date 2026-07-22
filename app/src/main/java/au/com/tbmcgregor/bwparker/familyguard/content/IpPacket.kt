package au.com.tbmcgregor.bwparker.familyguard.content

/**
 * Minimal IPv4/UDP packet parsing and reply-building for [VpnFilterService]. Deliberately limited
 * to exactly what DNS filtering needs -- IPv6 and non-UDP protocols are recognized but not fully
 * parsed, since this service never routes general traffic through its tun interface.
 */
class IpPacket private constructor(
    val protocol: Int,
    val sourceAddress: String,
    val destinationAddress: String,
    val sourcePort: Int,
    val destinationPort: Int,
    val udpPayload: ByteArray,
) {
    /**
     * Builds a reply packet with source/destination swapped and [payload] as the UDP body.
     * The UDP checksum is left as 0 ("not computed"), which is valid for IPv4 per RFC 768; the
     * IPv4 header checksum is mandatory and is computed properly.
     */
    fun buildUdpReply(payload: ByteArray): ByteArray {
        val totalSize = IP_HEADER_SIZE + UDP_HEADER_SIZE + payload.size
        val out = ByteArray(totalSize)

        out[0] = 0x45 // version 4, IHL 5 (20 bytes, no options)
        out[1] = 0
        writeUInt16(out, 2, totalSize)
        writeUInt16(out, 4, 0) // identification
        writeUInt16(out, 6, 0) // flags/fragment offset
        out[8] = 64 // TTL
        out[9] = PROTOCOL_UDP.toByte()
        writeUInt16(out, 10, 0) // checksum placeholder, filled in below
        writeAddress(out, 12, destinationAddress) // we're replying FROM the original destination
        writeAddress(out, 16, sourceAddress) // ...TO the original source
        writeUInt16(out, 10, ipChecksum(out, IP_HEADER_SIZE))

        writeUInt16(out, IP_HEADER_SIZE, destinationPort) // swapped
        writeUInt16(out, IP_HEADER_SIZE + 2, sourcePort)
        writeUInt16(out, IP_HEADER_SIZE + 4, UDP_HEADER_SIZE + payload.size)
        writeUInt16(out, IP_HEADER_SIZE + 6, 0) // UDP checksum: 0 = not computed
        System.arraycopy(payload, 0, out, IP_HEADER_SIZE + UDP_HEADER_SIZE, payload.size)
        return out
    }

    companion object {
        const val PROTOCOL_UDP = 17
        private const val IP_HEADER_SIZE = 20
        private const val UDP_HEADER_SIZE = 8

        fun parse(buffer: ByteArray, length: Int): IpPacket? {
            if (length < IP_HEADER_SIZE) return null
            val version = (buffer[0].toInt() shr 4) and 0xF
            if (version != 4) return null // IPv6 not handled by this minimal implementation

            val ihl = (buffer[0].toInt() and 0xF) * 4
            if (ihl < IP_HEADER_SIZE || length < ihl) return null
            val protocol = buffer[9].toInt() and 0xFF
            val sourceAddress = formatAddress(buffer, 12)
            val destinationAddress = formatAddress(buffer, 16)

            if (protocol != PROTOCOL_UDP || length < ihl + UDP_HEADER_SIZE) {
                return IpPacket(protocol, sourceAddress, destinationAddress, 0, 0, ByteArray(0))
            }

            val sourcePort = readUInt16(buffer, ihl)
            val destinationPort = readUInt16(buffer, ihl + 2)
            val udpLength = readUInt16(buffer, ihl + 4)
            val payloadStart = ihl + UDP_HEADER_SIZE
            val payloadEnd = (ihl + udpLength).coerceIn(payloadStart, length)
            val payload = buffer.copyOfRange(payloadStart, payloadEnd)

            return IpPacket(protocol, sourceAddress, destinationAddress, sourcePort, destinationPort, payload)
        }

        private fun formatAddress(buffer: ByteArray, offset: Int): String =
            "${buffer[offset].toInt() and 0xFF}.${buffer[offset + 1].toInt() and 0xFF}." +
                "${buffer[offset + 2].toInt() and 0xFF}.${buffer[offset + 3].toInt() and 0xFF}"

        private fun writeAddress(buffer: ByteArray, offset: Int, address: String) {
            val parts = address.split('.')
            for (i in 0 until 4) {
                buffer[offset + i] = parts[i].toInt().toByte()
            }
        }

        private fun readUInt16(buffer: ByteArray, offset: Int): Int =
            ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)

        private fun writeUInt16(buffer: ByteArray, offset: Int, value: Int) {
            buffer[offset] = ((value shr 8) and 0xFF).toByte()
            buffer[offset + 1] = (value and 0xFF).toByte()
        }

        private fun ipChecksum(buffer: ByteArray, headerLength: Int): Int {
            var sum = 0
            var i = 0
            while (i < headerLength) {
                sum += readUInt16(buffer, i)
                i += 2
            }
            while (sum shr 16 != 0) {
                sum = (sum and 0xFFFF) + (sum shr 16)
            }
            return sum.inv() and 0xFFFF
        }
    }
}
