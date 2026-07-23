package au.com.tbmcgregor.bwparker.familyguard.content

/**
 * Minimal IPv4/UDP/TCP packet parsing and reply-building for [VpnFilterService]'s NAT relay.
 * IPv6 is recognized but not parsed -- this VPN doesn't advertise an IPv6 address/route, so
 * Android already treats that address family as unreachable for captured apps and never delivers
 * it here (see [VpnFilterService] for why IPv4-only is an acceptable trade-off for now).
 */
class IpPacket private constructor(
    val protocol: Int,
    val sourceAddress: String,
    val destinationAddress: String,
    val sourcePort: Int,
    val destinationPort: Int,
    /** The layer-4 payload: UDP/TCP body bytes, empty for non-UDP/TCP protocols or malformed segments. */
    val payload: ByteArray,
    val tcpSeq: Long = 0,
    val tcpAck: Long = 0,
    val tcpFlags: Int = 0,
    val tcpWindow: Int = 0,
    /** The RFC 1323 Window Scale shift count this packet's sender offered, parsed from the TCP
     * options of a SYN segment (null if absent or not a SYN -- the option is only ever valid on
     * the initial SYN/SYN-ACK exchange). Needed to interpret [tcpWindow] as a real byte count
     * (real window = tcpWindow shl the *peer's* shift) instead of being capped at 65535, which
     * throttled every connection through this relay to well below real link speed. */
    val tcpWindowScale: Int? = null,
) {
    val isSyn: Boolean get() = tcpFlags and TCP_SYN != 0
    val isAck: Boolean get() = tcpFlags and TCP_ACK != 0
    val isFin: Boolean get() = tcpFlags and TCP_FIN != 0
    val isRst: Boolean get() = tcpFlags and TCP_RST != 0

    /**
     * Builds a UDP reply packet with source/destination swapped and [replyPayload] as the UDP
     * body. The UDP checksum is left as 0 ("not computed"), which is valid for IPv4 per RFC 768;
     * the IPv4 header checksum is mandatory and is computed properly.
     */
    fun buildUdpReply(replyPayload: ByteArray): ByteArray {
        val totalSize = IP_HEADER_SIZE + UDP_HEADER_SIZE + replyPayload.size
        val out = ByteArray(totalSize)

        writeIpHeader(out, totalSize, PROTOCOL_UDP, fromAddress = destinationAddress, toAddress = sourceAddress)

        writeUInt16(out, IP_HEADER_SIZE, destinationPort) // swapped
        writeUInt16(out, IP_HEADER_SIZE + 2, sourcePort)
        writeUInt16(out, IP_HEADER_SIZE + 4, UDP_HEADER_SIZE + replyPayload.size)
        writeUInt16(out, IP_HEADER_SIZE + 6, 0) // UDP checksum: 0 = not computed
        System.arraycopy(replyPayload, 0, out, IP_HEADER_SIZE + UDP_HEADER_SIZE, replyPayload.size)
        return out
    }

    /**
     * Builds a TCP segment from us (posing as [destinationAddress]:[destinationPort], i.e. the
     * relay's NAT'd side of the connection) back to the original client
     * ([sourceAddress]:[sourcePort]), with the given [seq]/[ack]/[flags]/[window] and
     * [segmentPayload]. Unlike UDP, the TCP checksum is mandatory (most stacks silently discard a
     * segment with an invalid one), so it's always computed over the pseudo-header + segment.
     *
     * [windowScaleToAdvertise], if non-null, appends an RFC 1323 Window Scale option (only
     * meaningful -- and only ever passed -- on the SYN-ACK): it tells the client "multiply the
     * window field of every future segment you get from me by 2^this" so we're not stuck
     * advertising a max-65535-byte receive window for the rest of the connection's life.
     */
    fun buildTcpSegment(
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int,
        segmentPayload: ByteArray = ByteArray(0),
        windowScaleToAdvertise: Int? = null,
    ): ByteArray {
        // Window Scale option is kind=3, len=3, 1 shift byte, padded with a 1-byte NOP to keep the
        // header a multiple of 4 bytes (options are otherwise absent from every segment this relay
        // ever sends, so this is the only case that needs to touch the header length at all).
        val optionsSize = if (windowScaleToAdvertise != null) 4 else 0
        val headerSize = TCP_HEADER_SIZE + optionsSize
        val totalSize = IP_HEADER_SIZE + headerSize + segmentPayload.size
        val out = ByteArray(totalSize)

        writeIpHeader(out, totalSize, PROTOCOL_TCP, fromAddress = destinationAddress, toAddress = sourceAddress)

        val tcpOffset = IP_HEADER_SIZE
        writeUInt16(out, tcpOffset, destinationPort) // swapped: we ARE destinationAddress:destinationPort
        writeUInt16(out, tcpOffset + 2, sourcePort)
        writeUInt32(out, tcpOffset + 4, seq)
        writeUInt32(out, tcpOffset + 8, ack)
        out[tcpOffset + 12] = ((headerSize / 4) shl 4).toByte() // data offset
        out[tcpOffset + 13] = flags.toByte()
        writeUInt16(out, tcpOffset + 14, window.coerceIn(0, 0xFFFF))
        writeUInt16(out, tcpOffset + 16, 0) // checksum placeholder
        writeUInt16(out, tcpOffset + 18, 0) // urgent pointer
        if (windowScaleToAdvertise != null) {
            out[tcpOffset + TCP_HEADER_SIZE] = 3 // kind: Window Scale
            out[tcpOffset + TCP_HEADER_SIZE + 1] = 3 // option length
            out[tcpOffset + TCP_HEADER_SIZE + 2] = windowScaleToAdvertise.coerceIn(0, 14).toByte()
            out[tcpOffset + TCP_HEADER_SIZE + 3] = 1 // NOP padding
        }
        System.arraycopy(segmentPayload, 0, out, tcpOffset + headerSize, segmentPayload.size)

        val tcpChecksum = transportChecksum(
            out,
            transportOffset = tcpOffset,
            transportLength = headerSize + segmentPayload.size,
            sourceAddress = destinationAddress,
            destAddress = sourceAddress,
            protocol = PROTOCOL_TCP,
        )
        writeUInt16(out, tcpOffset + 16, tcpChecksum)
        return out
    }

    private fun writeIpHeader(out: ByteArray, totalSize: Int, protocol: Int, fromAddress: String, toAddress: String) {
        out[0] = 0x45 // version 4, IHL 5 (20 bytes, no options)
        out[1] = 0
        writeUInt16(out, 2, totalSize)
        writeUInt16(out, 4, 0) // identification
        writeUInt16(out, 6, 0) // flags/fragment offset
        out[8] = 64 // TTL
        out[9] = protocol.toByte()
        writeUInt16(out, 10, 0) // checksum placeholder, filled in below
        writeAddress(out, 12, fromAddress)
        writeAddress(out, 16, toAddress)
        writeUInt16(out, 10, ipChecksum(out, IP_HEADER_SIZE))
    }

    companion object {
        const val PROTOCOL_TCP = 6
        const val PROTOCOL_UDP = 17

        const val TCP_FIN = 0x01
        const val TCP_SYN = 0x02
        const val TCP_RST = 0x04
        const val TCP_PSH = 0x08
        const val TCP_ACK = 0x10

        private const val IP_HEADER_SIZE = 20
        private const val UDP_HEADER_SIZE = 8
        private const val TCP_HEADER_SIZE = 20

        fun parse(buffer: ByteArray, length: Int): IpPacket? {
            if (length < IP_HEADER_SIZE) return null
            val version = (buffer[0].toInt() shr 4) and 0xF
            if (version != 4) return null // IPv6 not handled by this minimal implementation

            val ihl = (buffer[0].toInt() and 0xF) * 4
            if (ihl < IP_HEADER_SIZE || length < ihl) return null
            val protocol = buffer[9].toInt() and 0xFF
            val sourceAddress = formatAddress(buffer, 12)
            val destinationAddress = formatAddress(buffer, 16)

            return when (protocol) {
                PROTOCOL_UDP -> parseUdp(buffer, length, ihl, protocol, sourceAddress, destinationAddress)
                PROTOCOL_TCP -> parseTcp(buffer, length, ihl, protocol, sourceAddress, destinationAddress)
                else -> IpPacket(protocol, sourceAddress, destinationAddress, 0, 0, ByteArray(0))
            }
        }

        private fun parseUdp(
            buffer: ByteArray,
            length: Int,
            ihl: Int,
            protocol: Int,
            sourceAddress: String,
            destinationAddress: String,
        ): IpPacket? {
            if (length < ihl + UDP_HEADER_SIZE) return null
            val sourcePort = readUInt16(buffer, ihl)
            val destinationPort = readUInt16(buffer, ihl + 2)
            val udpLength = readUInt16(buffer, ihl + 4)
            val payloadStart = ihl + UDP_HEADER_SIZE
            val payloadEnd = (ihl + udpLength).coerceIn(payloadStart, length)
            val payload = buffer.copyOfRange(payloadStart, payloadEnd)
            return IpPacket(protocol, sourceAddress, destinationAddress, sourcePort, destinationPort, payload)
        }

        private fun parseTcp(
            buffer: ByteArray,
            length: Int,
            ihl: Int,
            protocol: Int,
            sourceAddress: String,
            destinationAddress: String,
        ): IpPacket? {
            if (length < ihl + TCP_HEADER_SIZE) return null
            val sourcePort = readUInt16(buffer, ihl)
            val destinationPort = readUInt16(buffer, ihl + 2)
            val seq = readUInt32(buffer, ihl + 4)
            val ack = readUInt32(buffer, ihl + 8)
            val dataOffset = ((buffer[ihl + 12].toInt() and 0xFF) ushr 4) * 4
            if (dataOffset < TCP_HEADER_SIZE) return null
            val flags = buffer[ihl + 13].toInt() and 0xFF
            val window = readUInt16(buffer, ihl + 14)
            // `dataOffset` is a claim made by the packet itself about how many header+options
            // bytes follow -- nothing stops a malformed/truncated segment from claiming more
            // option bytes than were actually captured in this read (`length`). `payload` below
            // already guards the same way; options parsing needs the identical guard, otherwise
            // it walks past the real segment into whatever this ByteArray happens to hold there.
            val optionsLength = dataOffset - TCP_HEADER_SIZE
            val windowScale = if (flags and TCP_SYN != 0 && ihl + dataOffset <= length) {
                parseWindowScaleOption(buffer, ihl + TCP_HEADER_SIZE, optionsLength)
            } else {
                null
            }
            val payloadStart = ihl + dataOffset
            val payload = if (payloadStart > length) ByteArray(0) else buffer.copyOfRange(payloadStart, length)
            return IpPacket(
                protocol,
                sourceAddress,
                destinationAddress,
                sourcePort,
                destinationPort,
                payload,
                tcpSeq = seq,
                tcpAck = ack,
                tcpFlags = flags,
                tcpWindow = window,
                tcpWindowScale = windowScale,
            )
        }

        /** Scans a SYN segment's TCP options for a Window Scale option (kind 3), per RFC 1323's
         * standard TLV option encoding (kind 0 = end-of-options, kind 1 = 1-byte NOP with no
         * length byte, everything else is kind+length+data). Returns the raw shift count, or null
         * if the client didn't offer one (in which case scaling must stay off for this connection
         * entirely -- RFC 1323 requires *both* sides to advertise it or neither may use it). */
        private fun parseWindowScaleOption(buffer: ByteArray, optionsStart: Int, optionsLength: Int): Int? {
            var i = optionsStart
            val end = optionsStart + optionsLength
            while (i < end) {
                val kind = buffer[i].toInt() and 0xFF
                when (kind) {
                    0 -> return null // end of options
                    1 -> i += 1 // NOP
                    else -> {
                        if (i + 1 >= end) return null
                        val optLen = buffer[i + 1].toInt() and 0xFF
                        if (optLen < 2 || i + optLen > end) return null
                        if (kind == 3 && optLen == 3) return buffer[i + 2].toInt() and 0xFF
                        i += optLen
                    }
                }
            }
            return null
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

        private fun readUInt32(buffer: ByteArray, offset: Int): Long =
            ((buffer[offset].toLong() and 0xFF) shl 24) or
                ((buffer[offset + 1].toLong() and 0xFF) shl 16) or
                ((buffer[offset + 2].toLong() and 0xFF) shl 8) or
                (buffer[offset + 3].toLong() and 0xFF)

        private fun writeUInt32(buffer: ByteArray, offset: Int, value: Long) {
            buffer[offset] = ((value shr 24) and 0xFF).toByte()
            buffer[offset + 1] = ((value shr 16) and 0xFF).toByte()
            buffer[offset + 2] = ((value shr 8) and 0xFF).toByte()
            buffer[offset + 3] = (value and 0xFF).toByte()
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

        /** RFC 793 TCP/UDP checksum: ones-complement sum over a 12-byte IPv4 pseudo-header
         * (source, dest, zero, protocol, transport length) followed by the transport segment
         * itself (with its own checksum field already zeroed by the caller). */
        private fun transportChecksum(
            buffer: ByteArray,
            transportOffset: Int,
            transportLength: Int,
            sourceAddress: String,
            destAddress: String,
            protocol: Int,
        ): Int {
            var sum = 0
            val addrBytes = ByteArray(4)
            fun sumAddress(address: String) {
                address.split('.').forEachIndexed { i, part -> addrBytes[i] = part.toInt().toByte() }
                sum += readUInt16(addrBytes, 0)
                sum += readUInt16(addrBytes, 2)
            }
            sumAddress(sourceAddress)
            sumAddress(destAddress)
            sum += protocol
            sum += transportLength

            var i = transportOffset
            val end = transportOffset + transportLength
            while (i + 1 < end) {
                sum += readUInt16(buffer, i)
                i += 2
            }
            if (i < end) {
                sum += (buffer[i].toInt() and 0xFF) shl 8 // odd trailing byte, padded with zero
            }
            while (sum shr 16 != 0) {
                sum = (sum and 0xFFFF) + (sum shr 16)
            }
            return sum.inv() and 0xFFFF
        }
    }
}
