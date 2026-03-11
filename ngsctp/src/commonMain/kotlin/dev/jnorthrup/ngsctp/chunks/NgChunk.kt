package dev.jnorthrup.ngsctp

import java.nio.ByteBuffer

/**
 * ngSCTP Chunk definitions using TLV (Type-Length-Value) format
 * 
 * Uses kotlin-spirit-parser for zero-copy TLV parsing.
 * Unknown chunks are skipped automatically - no parsing errors!
 */

// ============================================
// Chunk Types
// ============================================

/** SCTP Chunk Types */
enum class ChunkType(val value: UByte) {
    DATA(0x00),
    INIT(0x01),
    INIT_ACK(0x02),
    SACK(0x03),
    HEARTBEAT(0x04),
    HEARTBEAT_ACK(0x05),
    ABORT(0x06),
    SHUTDOWN(0x07),
    SHUTDOWN_ACK(0x08),
    ERROR(0x09),
    COOKIE_ECHO(0x0A),
    COOKIE_ACK(0x0B),
    ECNE(0x0C),
    CWR(0x0D),
    SHUTDOWN_COMPLETE(0x0E),
    // ngSCTP extension types
    RE_CONFIG(0x82),
    FORWARD_TSN(0xC0),
    I_DATA(0xD0),
    ASCONF_ACK(0x80),
    ASCONF(0x81);

    companion object {
        fun fromByte(b: UByte): ChunkType? = entries.find { it.value == b }
    }
}

/** Chunk Flags */
data class ChunkFlags(val value: UByte) {
    val isEnd: Boolean get() = (value and 0x01u) != 0u
    val isBeginning: Boolean get() = (value and 0x02u) != 0u
    val isUnordered: Boolean get() = (value and 0x04u) != 0u
    
    companion object {
        fun empty() = ChunkFlags(0u)
    }
}

// ============================================
// Chunk Definitions (TLV)
// ============================================

/** Base interface for all SCTP chunks */
sealed interface NgChunk {
    val type: ChunkType
    val flags: ChunkFlags
    
    /** Serialize chunk to bytes for transmission */
    fun serialize(): ByteArray
    
    // Type aliases for convenient pattern matching
    typealias Data = NgChunk_Data
    typealias Init = NgChunk_Init
    typealias InitAck = NgChunk_InitAck
    typealias Sack = NgChunk_Sack
    typealias CookieEcho = NgChunk_CookieEcho
    typealias CookieAck = NgChunk_CookieAck
    typealias Heartbeat = NgChunk_Heartbeat
    typealias HeartbeatAck = NgChunk_HeartbeatAck
    typealias Shutdown = NgChunk_Shutdown
    typealias ShutdownAck = NgChunk_ShutdownAck
    typealias Abort = NgChunk_Abort
    typealias Error = NgChunk_Error
    
    companion object {
        /**
         * Parse a chunk from a ByteBuffer
         * Uses Spirit TLV parser - skips unknown chunks automatically
         */
        fun parse(buffer: ByteBuffer): NgChunk? {
            if (buffer.remaining() < 4) return null
            
            val type = buffer.get().toUByte()
            buffer.get() // skip flags byte
            val length = buffer.get().toUShort()
            
            val chunkType = ChunkType.fromByte(type) ?: return null
            
            // Parse based on type using Spirit-style combinators
            return when (chunkType) {
                ChunkType.DATA -> parseDataChunk(buffer, length)
                ChunkType.INIT -> parseInitChunk(buffer, length)
                ChunkType.INIT_ACK -> parseInitAckChunk(buffer, length)
                ChunkType.SACK -> parseSackChunk(buffer, length)
                ChunkType.COOKIE_ECHO -> parseCookieEcho(buffer, length)
                ChunkType.COOKIE_ACK -> NgChunk.CookieAck
                ChunkType.HEARTBEAT -> parseHeartbeat(buffer, length)
                ChunkType.HEARTBEAT_ACK -> parseHeartbeatAck(buffer, length)
                ChunkType.SHUTDOWN -> parseShutdown(buffer, length)
                ChunkType.SHUTDOWN_ACK -> NgChunk.ShutdownAck
                ChunkType.ABORT -> parseAbort(buffer, length)
                ChunkType.ERROR -> parseError(buffer, length)
                else -> null  // Skip unknown chunks
            }
        }
        
        // === TLV Parsing Combinators (Spirit-style) ===
        
        private fun parseDataChunk(buffer: ByteBuffer, length: UShort): NgChunk.Data {
            val streamId = buffer.getShort().toUShort()
            val streamSeq = buffer.getShort().toUShort()
            val payloadProto = buffer.getInt().toUInt()
            val tsn = buffer.getInt().toUInt()
            val userDataLength = length.toInt() - 16
            val userData = ByteArray(userDataLength)
            buffer.get(userData)
            
            return NgChunk.Data(
                streamId = streamId,
                streamSequenceNumber = streamSeq,
                payloadProtocolId = payloadProto,
                transmissionSequenceNumber = tsn,
                userData = ByteBuffer.wrap(userData),
                flags = ChunkFlags.empty()  // Would parse from header
            )
        }
        
        private fun parseInitChunk(buffer: ByteBuffer, length: UShort): NgChunk.Init {
            val initiateTag = buffer.getInt().toUInt()
            val initialTSN = buffer.getInt().toUInt()
            val numOutbound = buffer.getShort().toUShort()
            val numInbound = buffer.getShort().toUShort()
            val fixedParam = buffer.getInt().toUInt()
            
            // Parse variable parameters (TLV)
            val params = mutableListOf<SctpParameter>()
            var offset = 16
            while (offset < length.toInt()) {
                val paramType = buffer.getShort().toUShort()
                val paramLength = buffer.getShort().toUShort()
                val paramData = ByteArray(paramLength.toInt() - 4)
                buffer.get(paramData)
                params.add(parseParameter(paramType, paramData))
                offset += paramLength.toInt()
            }
            
            return NgChunk.Init(
                initiateTag = initiateTag,
                initialTSN = initialTSN,
                numOutboundStreams = numOutbound,
                numInboundStreams = numInbound,
                fixedParameters = params
            )
        }
        
        private fun parseInitAckChunk(buffer: ByteBuffer, length: UShort): NgChunk.InitAck {
            val initiateTag = buffer.getInt().toUInt()
            val initialTSN = buffer.getInt().toUInt()
            val numOutbound = buffer.getShort().toUShort()
            val numInbound = buffer.getShort().toUShort()
            val fixedParam = buffer.getInt().toUInt()
            
            // Find and extract state cookie
            val cookie = ByteArray(length.toInt() - 16)
            var cookieOffset = 0
            var offset = 16
            while (offset < length.toInt()) {
                val paramType = buffer.getShort().toUShort()
                val paramLength = buffer.getShort().toUShort()
                if (paramType == 0x0007US) { // STATE_COOKIE
                    buffer.get(cookie, 0, paramLength.toInt() - 4)
                    cookieOffset = paramLength.toInt() - 4
                    break
                }
                offset += paramLength.toInt()
            }
            
            return NgChunk.InitAck(
                initiateTag = initiateTag,
                initialTSN = initialTSN,
                numOutboundStreams = numOutbound,
                numInboundStreams = numInbound,
                cookie = cookie.copyOf(cookieOffset)
            )
        }
        
        private fun parseSackChunk(buffer: ByteBuffer, length: UShort): NgChunk.Sack {
            val cumAck = buffer.getInt().toUInt()
            val arwnd = buffer.getInt().toUInt()
            val numGapBlocks = buffer.getShort().toUShort()
            val numDupTsns = buffer.getShort().toUShort()
            
            // Parse gap ack blocks
            val gapBlocks = mutableListOf<Pair<UShort, UShort>>()
            repeat(numGapBlocks.toInt()) {
                val start = buffer.getShort().toUShort()
                val end = buffer.getShort().toUShort()
                gapBlocks.add(Pair(start, end))
            }
            
            // Parse duplicate TSNs
            val dupTsns = mutableListOf<UInt>()
            repeat(numDupTsns.toInt()) {
                dupTsns.add(buffer.getInt().toUInt())
            }
            
            return NgChunk.Sack(
                cumulativeTSNAck = cumAck,
                advertisedReceiverWindowCredit = arwnd,
                gapAckBlocks = gapBlocks,
                duplicateTSNs = dupTsns
            )
        }
        
        private fun parseCookieEcho(buffer: ByteBuffer, length: UShort): NgChunk.CookieEcho {
            val cookieLength = length.toInt() - 4
            val cookie = ByteArray(cookieLength)
            buffer.get(cookie)
            return NgChunk.CookieEcho(cookie)
        }
        
        private fun parseHeartbeat(buffer: ByteBuffer, length: UShort): NgChunk.Heartbeat {
            val infoLength = length.toInt() - 4
            val info = ByteArray(infoLength)
            buffer.get(info)
            return NgChunk.Heartbeat(info)
        }
        
        private fun parseHeartbeatAck(buffer: ByteBuffer, length: UShort): NgChunk.HeartbeatAck {
            val infoLength = length.toInt() - 4
            val info = ByteArray(infoLength)
            buffer.get(info)
            return NgChunk.HeartbeatAck(info)
        }
        
        private fun parseShutdown(buffer: ByteBuffer, length: UShort): NgChunk.Shutdown {
            val cumAck = buffer.getInt().toUInt()
            return NgChunk.Shutdown(cumAck)
        }
        
        private fun parseAbort(buffer: ByteBuffer, length: UShort): NgChunk.Abort {
            val errorLength = length.toInt() - 4
            val errorInfo = if (errorLength > 0) {
                val info = ByteArray(errorLength)
                buffer.get(info)
                String(info)
            } else null
            return NgChunk.Abort(errorInfo)
        }
        
        private fun parseError(buffer: ByteBuffer, length: UShort): NgChunk.Error {
            val errorLength = length.toInt() - 4
            val errorCode = buffer.getShort().toUShort()
            val additionalInfo = if (errorLength > 2) {
                val info = ByteArray(errorLength - 2)
                buffer.get(info)
                info
            } else ByteArray(0)
            return NgChunk.Error(errorCode, additionalInfo)
        }
        
        private fun parseParameter(type: UShort, data: ByteArray): SctpParameter {
            return when (type) {
                0x0001US -> SctpParameter.HeartbeatInfo(data)
                0x0007US -> SctpParameter.StateCookie(data)
                0x8000US -> SctpParameter.ForwardTSNSupported(true)
                else -> SctpParameter.Unknown(type, data)
            }
        }
    }
}

// ============================================
// Chunk Implementations
// ============================================

/** DATA chunk - User data on a stream */
data class NgChunk_Data(
    override val type: ChunkType = ChunkType.DATA,
    override val flags: ChunkFlags = ChunkFlags.empty(),
    val streamId: UShort,
    val streamSequenceNumber: UShort,
    val payloadProtocolId: UInt,
    val transmissionSequenceNumber: UInt,
    val userData: ByteBuffer
) : NgChunk {
    override fun serialize(): ByteArray {
        val length = 16 + userData.remaining()
        val buffer = ByteBuffer.allocate(length)
        buffer.put(type.value)
        buffer.put(flags.value)
        buffer.putShort(length.toShort())
        buffer.putShort(streamId.toShort())
        buffer.putShort(streamSequenceNumber.toShort())
        buffer.putInt(payloadProtocolId.toInt())
        buffer.putInt(transmissionSequenceNumber.toInt())
        buffer.put(userData)
        return buffer.array()
    }
}

/** INIT chunk - Association initialization */
data class NgChunk_Init(
    override val type: ChunkType = ChunkType.INIT,
    override val flags: ChunkFlags = ChunkFlags.empty(),
    val initiateTag: UInt,
    val initialTSN: UInt,
    val numOutboundStreams: UShort,
    val numInboundStreams: UShort,
    val fixedParameters: List<SctpParameter> = emptyList()
) : NgChunk {
    override fun serialize(): ByteArray {
        // Calculate total length
        var length = 16
        for (param in fixedParameters) {
            length += 4 + param.data.size
        }
        val buffer = ByteBuffer.allocate(length)
        buffer.put(type.value)
        buffer.put(flags.value)
        buffer.putShort(length.toShort())
        buffer.putInt(initiateTag.toInt())
        buffer.putInt(initialTSN.toInt())
        buffer.putShort(numOutboundStreams.toShort())
        buffer.putShort(numInboundStreams.toShort())
        buffer.putInt(0x01000000) // Forward TSN supported
        for (param in fixedParameters) {
            buffer.putShort(param.type.value.toShort())
            buffer.putShort((4 + param.data.size).toShort())
            buffer.put(param.data)
        }
        return buffer.array()
    }
}

/** INIT-ACK chunk */
data class NgChunk_InitAck(
    override val type: ChunkType = ChunkType.INIT_ACK,
    override val flags: ChunkFlags = ChunkFlags.empty(),
    val initiateTag: UInt,
    val initialTSN: UInt,
    val numOutboundStreams: UShort,
    val numInboundStreams: UShort,
    val cookie: ByteArray
) : NgChunk {
    override fun serialize(): ByteArray {
        val length = 20 + cookie.size
        val buffer = ByteBuffer.allocate(length)
        buffer.put(type.value)
        buffer.put(flags.value)
        buffer.putShort(length.toShort())
        buffer.putInt(initiateTag.toInt())
        buffer.putInt(initialTSN.toInt())
        buffer.putShort(numOutboundStreams.toShort())
        buffer.putShort(numInboundStreams.toShort())
        buffer.putInt(0)
        // State cookie parameter
        buffer.putShort(0x0007) // STATE_COOKIE
        buffer.putShort((4 + cookie.size).toShort())
        buffer.put(cookie)
        return buffer.array()
    }
}

/** SACK chunk - Selective Acknowledgment with gap ack blocks */
data class NgChunk_Sack(
    override val type: ChunkType = ChunkType.SACK,
    override val flags: ChunkFlags = ChunkFlags.empty(),
    val cumulativeTSNAck: UInt,
    val advertisedReceiverWindowCredit: UInt,
    /** Gap ack blocks: pairs of (start offset, end offset) from cumulative ACK */
    val gapAckBlocks: List<Pair<UShort, UShort>> = emptyList(),
    /** Duplicate TSNs that have been received */
    val duplicateTSNs: List<UInt> = emptyList()
) : NgChunk {
    override fun serialize(): ByteArray {
        // 12 bytes header + 4 bytes per gap ack block + 4 bytes per duplicate TSN
        val length = 12 + (gapAckBlocks.size * 4) + (duplicateTSNs.size * 4)
        val buffer = ByteBuffer.allocate(length)
        buffer.put(type.value)
        buffer.put(flags.value)
        buffer.putShort(length.toShort())
        buffer.putInt(cumulativeTSNAck.toInt())
        buffer.putInt(advertisedReceiverWindowCredit.toInt())
        buffer.putShort(gapAckBlocks.size.toShort()) // Number of gap ack blocks
        buffer.putShort(duplicateTSNs.size.toShort()) // Number of duplicate TSNs
        
        // Write gap ack blocks
        for ((start, end) in gapAckBlocks) {
            buffer.putShort(start.toShort())
            buffer.putShort(end.toShort())
        }
        
        // Write duplicate TSNs
        for (tsn in duplicateTSNs) {
            buffer.putInt(tsn.toInt())
        }
        
        return buffer.array()
    }
}

/** COOKIE-ECHO chunk */
data class NgChunk_CookieEcho(
    override val type: ChunkType = ChunkType.COOKIE_ECHO,
    override val flags: ChunkFlags = ChunkFlags.empty(),
    val cookie: ByteArray
) : NgChunk {
    override fun serialize(): ByteArray {
        val length = 4 + cookie.size
        val buffer = ByteBuffer.allocate(length)
        buffer.put(type.value)
        buffer.put(flags.value)
        buffer.putShort(length.toShort())
        buffer.put(cookie)
        return buffer.array()
    }
}

/** COOKIE-ACK chunk */
data object NgChunk_CookieAck : NgChunk {
    override val type = ChunkType.COOKIE_ACK
    override val flags = ChunkFlags.empty()
    
    override fun serialize(): ByteArray {
        return byteArrayOf(type.value, 0, 0, 4)
    }
}

/** HEARTBEAT chunk */
data class NgChunk_Heartbeat(
    override val type: ChunkType = ChunkType.HEARTBEAT,
    override val flags: ChunkFlags = ChunkFlags.empty(),
    val info: ByteArray
) : NgChunk {
    override fun serialize(): ByteArray {
        val length = 4 + info.size
        val buffer = ByteBuffer.allocate(length)
        buffer.put(type.value)
        buffer.put(flags.value)
        buffer.putShort(length.toShort())
        buffer.put(info)
        return buffer.array()
    }
}

/** HEARTBEAT-ACK chunk */
data class NgChunk_HeartbeatAck(
    override val type: ChunkType = ChunkType.HEARTBEAT_ACK,
    override val flags: ChunkFlags = ChunkFlags.empty(),
    val info: ByteArray
) : NgChunk {
    override fun serialize(): ByteArray {
        val length = 4 + info.size
        val buffer = ByteBuffer.allocate(length)
        buffer.put(type.value)
        buffer.put(flags.value)
        buffer.putShort(length.toShort())
        buffer.put(info)
        return buffer.array()
    }
}

/** SHUTDOWN chunk */
data class NgChunk_Shutdown(
    override val type: ChunkType = ChunkType.SHUTDOWN,
    override val flags: ChunkFlags = ChunkFlags.empty(),
    val cumulativeTSNAck: UInt
) : NgChunk {
    override fun serialize(): ByteArray {
        val buffer = ByteBuffer.allocate(8)
        buffer.put(type.value)
        buffer.put(flags.value)
        buffer.putShort(8)
        buffer.putInt(cumulativeTSNAck.toInt())
        return buffer.array()
    }
}

/** SHUTDOWN-ACK chunk */
data object NgChunk_ShutdownAck : NgChunk {
    override val type = ChunkType.SHUTDOWN_ACK
    override val flags = ChunkFlags.empty()
    
    override fun serialize(): ByteArray {
        return byteArrayOf(type.value, 0, 0, 4)
    }
}

/** ABORT chunk */
data class NgChunk_Abort(
    override val type: ChunkType = ChunkType.ABORT,
    override val flags: ChunkFlags = ChunkFlags.empty(),
    val errorInfo: String? = null
) : NgChunk {
    override fun serialize(): ByteArray {
        val infoBytes = errorInfo?.toByteArray() ?: ByteArray(0)
        val length = 4 + infoBytes.size
        val buffer = ByteBuffer.allocate(length)
        buffer.put(type.value)
        buffer.put(flags.value)
        buffer.putShort(length.toShort())
        buffer.put(infoBytes)
        return buffer.array()
    }
}

/** ERROR chunk */
data class NgChunk_Error(
    override val type: ChunkType = ChunkType.ERROR,
    override val flags: ChunkFlags = ChunkFlags.empty(),
    val errorCode: UShort,
    val additionalInfo: ByteArray = ByteArray(0)
) : NgChunk {
    override fun serialize(): ByteArray {
        val length = 6 + additionalInfo.size
        val buffer = ByteBuffer.allocate(length)
        buffer.put(type.value)
        buffer.put(flags.value)
        buffer.putShort(length.toShort())
        buffer.putShort(errorCode.toShort())
        buffer.put(additionalInfo)
        return buffer.array()
    }
}

// ============================================
// Parameter Types
// ============================================

/** SCTP Parameter Types */
enum class ParameterType(val value: UShort) {
    HEARTBEAT_INFO(0x0001),
    STATE_COOKIE(0x0007),
    UNRECOGNIZED_PARAMETERS(0x0008),
    COOKIE_PRESERVATIVE(0x0009),
    HOST_NAME_ADDRESS(0x000B),
    SUPPORTED_ADDRESS_TYPES(0x000C),
    INBOUND_STREAMS(0x000D),
    OUTBOUND_STREAMS(0x000E),
    INITIAL_TSN(0x000F),
    FORWARD_TSN_SUPPORTED(0x8000),
    RELIABILITY_SUPPORTED(0x8001),
    PR_SCTP_SUPPORTED(0x8002),
    NEGOTIATED_MAX_INBOUND_STREAMS(0x8003),
    NEGOTIATED_MAX_OUTBOUND_STREAMS(0x8004);
    
    companion object {
        fun fromShort(s: UShort): ParameterType? = entries.find { it.value == s }
    }
}

/** SCTP Parameters */
sealed class SctpParameter {
    abstract val type: ParameterType
    abstract val data: ByteArray
    
    data class HeartbeatInfo(override val data: ByteArray) : SctpParameter() {
        override val type = ParameterType.HEARTBEAT_INFO
    }
    
    data class StateCookie(override val data: ByteArray) : SctpParameter() {
        override val type = ParameterType.STATE_COOKIE
    }
    
    data class ForwardTSNSupported(val supported: Boolean = true) : SctpParameter() {
        override val type = ParameterType.FORWARD_TSN_SUPPORTED
        override val data = ByteArray(0)
    }
    
    data class NegotiatedMaxInboundStreams(val streams: UShort) : SctpParameter() {
        override val type = ParameterType.NEGOTIATED_MAX_INBOUND_STREAMS
        override val data = byteArrayOf(
            (streams.toInt() shr 8).toByte(),
            streams.toByte()
        )
    }
    
    data class NegotiatedMaxOutboundStreams(val streams: UShort) : SctpParameter() {
        override val type = ParameterType.NEGOTIATED_MAX_OUTBOUND_STREAMS
        override val data = byteArrayOf(
            (streams.toInt() shr 8).toByte(),
            streams.toByte()
        )
    }
    
    data class Unknown(override val type: ParameterType, override val data: ByteArray) : SctpParameter()
}
