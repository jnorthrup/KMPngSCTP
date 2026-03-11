package dev.jnorthrup.ngsctp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * ngSCTP Association - The core connection entity
 * 
 * An association is a SupervisorJob scope that owns:
 * - TLV chunk parser (Spirit-based)
 * - Multi-path scheduler
 * - Stream management
 * - Congestion control
 * 
 * One association = one structured scope. 
 * Cancellation cascades perfectly to all streams.
 */
class NgSctpAssociation private constructor(
    private val scope: CoroutineScope,
    val localAddress: InetSocketAddress,
    val remoteAddress: InetSocketAddress,
    val localPort: Int,
    val remotePort: Int,
    val localVerificationTag: UInt,
    var remoteVerificationTag: UInt
) : CoroutineScope by scope {

    private val streams = ConcurrentHashMap<Int, NgSctpStream>()
    private var nextStreamId = 0
    
    /** Outbound chunk channel - streams send here */
    private val outboundChunks = Channel<NgChunk>(Channel.BUFFERED)
    
    /** Inbound chunk channel - receives from transport */
    private val inboundChunks = Channel<NgChunk>(Channel.BUFFERED)

    /** Association state */
    @Volatile
    var state: AssociationState = AssociationState.CLOSED
        private set

    /** Current transmission sequence number */
    private var initialTSN: UInt = 0u
    private var nextTSN: UInt = 0u
    private var lastAckedTSN: UInt = 0u

    /** Negotiated stream counts */
    var outboundStreamCount: UShort = 10u
    var inboundStreamCount: UShort = 10u

    init {
        // Start the transmit and receive loops
        scope.launch { transmitLoop() }
        scope.launch { receiveLoop() }
    }

    companion object {
        /**
         * Connect to a remote endpoint (client-side)
         * Performs 4-way SCTP handshake:
         * 1. INIT -> 
         * 2. <- INIT-ACK (with cookie)
         * 3. COOKIE_ECHO -> 
         * 4. <- COOKIE_ACK
         */
        suspend fun connect(
            remote: InetSocketAddress,
            local: InetSocketAddress = InetSocketAddress(0),
            outboundStreams: UShort = 10u,
            inboundStreams: UShort = 10u
        ): NgSctpAssociation = coroutineScope {
            val localTag = generateVerificationTag()
            val assocScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            
            val assoc = NgSctpAssociation(
                scope = assocScope,
                localAddress = local,
                remoteAddress = remote,
                localPort = local.port,
                remotePort = remote.port,
                localVerificationTag = localTag,
                remoteVerificationTag = 0u
            ).apply {
                this.outboundStreamCount = outboundStreams
                this.inboundStreamCount = inboundStreams
                this.initialTSN = generateTSN()
                this.nextTSN = this.initialTSN
                this.state = AssociationState.COOKIE_WAIT
            }

            // Step 1: Send INIT
            assoc.sendChunk(NgChunk_Init(
                initiateTag = localTag,
                initialTSN = assoc.initialTSN,
                numOutboundStreams = outboundStreams,
                numInboundStreams = inboundStreams,
                fixedParameters = listOf(
                    SctpParameter.ForwardTSNSupported(true),
                    SctpParameter.NegotiatedMaxInboundStreams(inboundStreams)
                )
            ))

            // Step 2: Wait for INIT-ACK with cookie
            val initAck = withTimeoutOrNull(3000) {
                assoc.inboundChunks.receive() as? NgChunk_InitAck
            } ?: throw ConnectionException("INIT-ACK timeout")

            assoc.remoteVerificationTag = initAck.initiateTag
            assoc.state = AssociationState.COOKIE_ECHOED

            // Step 3: Send COOKIE_ECHO with the received cookie
            assoc.sendChunk(NgChunk_CookieEcho(initAck.cookie))

            // Step 4: Wait for COOKIE_ACK
            withTimeoutOrNull(1000) {
                assoc.inboundChunks.receive() as? NgChunk_CookieAck
            } ?: throw ConnectionException("COOKIE_ACK timeout")

            assoc.state = AssociationState.ESTABLISHED
            assoc
        }

        /**
         * Accept an incoming connection (server-side)
         */
        suspend fun accept(init: NgChunk_Init, cookie: ByteArray): NgSctpAssociation = coroutineScope {
            val localTag = generateVerificationTag()
            val assocScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            
            NgSctpAssociation(
                scope = assocScope,
                localAddress = InetSocketAddress(0),  // Filled by transport
                remoteAddress = InetSocketAddress(0),   // Filled by transport
                localPort = 0,
                remotePort = 0,
                localVerificationTag = localTag,
                remoteVerificationTag = init.initiateTag
            ).also {
                it.outboundStreamCount = init.numInboundStreams
                it.inboundStreamCount = init.numOutboundStreams
                it.initialTSN = generateTSN()
                it.nextTSN = it.initialTSN
                it.state = AssociationState.ESTABLISHED
            }
        }

        private fun generateVerificationTag(): UInt = 
            (Math.random() * UInt.MAX_VALUE).toUInt()

        private fun generateTSN(): UInt = 
            (Math.random() * UInt.MAX_VALUE).toUInt()
    }

    /**
     * Open a new stream on this association
     */
    fun openStream(
        priority: Int = 0,
        intent: String = "default"
    ): NgSctpStream {
        check(state == AssociationState.ESTABLISHED) { 
            "Cannot open stream in state: $state" 
        }
        val streamId = nextStreamId++
        val stream = NgSctpStream(streamId, this, priority, intent)
        streams[streamId] = stream
        return stream
    }

    /**
     * Send a chunk on this association
     */
    suspend fun sendChunk(chunk: NgChunk) {
        check(isActive) { "Association is not active" }
        outboundChunks.send(chunk)
    }

    /**
     * Close the association gracefully
     */
    suspend fun close() {
        state = AssociationState.SHUTDOWN_PENDING
        sendChunk(NgChunk_Shutdown(nextTSN - 1u))
        // Wait for SHUTDOWN_ACK
        state = AssociationState.SHUTDOWN_SENT
        cancel("Association closed")
    }

    /**
     * Get association info
     */
    val info: AssociationInfo
        get() = AssociationInfo(
            localPort = localPort,
            remotePort = remotePort,
            state = state,
            streams = streams.size,
            nextTSN = nextTSN
        )

    // ============================================
    // Internal Loops
    // ============================================

    private fun transmitLoop() = scope.launch {
        for (chunk in outboundChunks) {
            // Serialize and transmit via transport layer
            serializeAndTransmit(chunk)
        }
    }

    private fun receiveLoop() = scope.launch {
        // Process incoming chunks
        for (chunk in inboundChunks) {
            when (chunk) {
                is NgChunk_Data -> deliverToStream(chunk)
                is NgChunk_Sack -> handleSack(chunk)
                is NgChunk_Heartbeat -> sendHeartbeatAck(chunk)
                is NgChunk_Abort -> handleAbort(chunk)
                is NgChunk_Error -> handleError(chunk)
                else -> { /* Handle other chunk types */ }
            }
        }
    }

    private fun deliverToStream(data: NgChunk_Data) {
        val stream = streams[data.streamId.toInt()] ?: return
        stream.receiveChannel.trySend(data.userData)
    }

    private fun handleSack(sack: NgChunk_Sack) {
        lastAckedTSN = sack.cumulativeTSNAck
        // Update congestion control state
    }

    private suspend fun sendHeartbeatAck(heartbeat: NgChunk_Heartbeat) {
        sendChunk(NgChunk_HeartbeatAck(heartbeat.info))
    }

    private fun handleAbort(abort: NgChunk_Abort) {
        cancel("Association aborted: ${abort.errorInfo}")
    }

    private fun handleError(error: NgChunk_Error) {
        // Log error
    }

    /**
     * Serialize chunk to wire format with SCTP common header
     * 
     * Wire format:
     * [12 bytes: Common Header] [Chunks...]
     */
    private suspend fun serializeAndTransmit(chunk: NgChunk) {
        // Build the packet: common header + chunk
        val chunkBytes = chunk.serialize()
        
        // Calculate total size
        val totalSize = SctpCommonHeader.SIZE + chunkBytes.size
        val buffer = ByteBuffer.allocate(totalSize)
        
        // Write common header
        val header = SctpCommonHeader(
            sourcePort = localPort.toUShort(),
            destinationPort = remotePort.toUShort(),
            verificationTag = localVerificationTag,
            checksum = 0u // CRC32c calculated below
        )
        header.serialize(buffer)
        
        // Write chunk data
        buffer.put(chunkBytes)
        
        // Calculate and insert CRC32c checksum
        buffer.flip()
        val checksum = calculateCrc32c(buffer)
        buffer.position(8) // Position at checksum field
        buffer.putInt(checksum.toInt())
        
        // TODO: Actually send via transport (io_uring in jvmMain, raw sockets in nativeMain)
        // For now, log what would be sent
        println("Would send ${chunkBytes.size} byte chunk to $remoteAddress:$remotePort")
    }
    
    /**
     * Parse inbound packet (common header + chunks)
     */
    fun parseInboundPacket(data: ByteArray) {
        val buffer = ByteBuffer.wrap(data)
        
        // Parse common header
        val header = SctpCommonHeader.parse(buffer)
        
        // Verify checksum
        val receivedChecksum = header.checksum
        // Zero checksum field for calculation
        buffer.position(8)
        buffer.putInt(0)
        buffer.position(0)
        val calculatedChecksum = calculateCrc32c(buffer)
        
        if (receivedChecksum != calculatedChecksum) {
            println("Checksum mismatch: got $receivedChecksum, expected $calculatedChecksum")
            return
        }
        
        // Verify verification tag
        if (header.verificationTag != remoteVerificationTag) {
            println("Verification tag mismatch")
            return
        }
        
        // Parse chunks
        while (buffer.hasRemaining()) {
            val chunk = NgChunk.parse(buffer)
            if (chunk != null) {
                // TODO: Send to inboundChunks channel
                println("Received chunk: ${chunk.type}")
            }
        }
    }
    
    /**
     * CRC32c (Castagnoli) checksum calculation
     * Used for SCTP-advertised receiver window credit
     */
    private fun calculateCrc32c(buffer: ByteBuffer): UInt {
        // CRC32c polynomial: 0x1EDC6F41
        // For production, use java.util.zip.CRC32C
        var crc = 0xFFFFFFFFu
        val polynomial = 0x1EDC6F41u
        
        while (buffer.hasRemaining()) {
            val byte = buffer.get().toUByte()
            crc = crc xor (byte.toUInt() shl 24)
            repeat(8) {
                if ((crc and 0x80000000u) != 0u) {
                    crc = (crc shl 1) xor polynomial
                } else {
                    crc = crc shl 1
                }
            }
        }
        
        return crc xor 0xFFFFFFFFu
    }
}

/**
 * Association state machine states
 */
enum class AssociationState {
    CLOSED,
    COOKIE_WAIT,
    COOKIE_ECHOED,
    ESTABLISHED,
    SHUTDOWN_PENDING,
    SHUTDOWN_SENT,
    SHUTDOWN_RECEIVED,
    SHUTDOWN_ACK_SENT
}

/**
 * Association information for debugging/monitoring
 */
data class AssociationInfo(
    val localPort: Int,
    val remotePort: Int,
    val state: AssociationState,
    val streams: Int,
    val nextTSN: UInt
)

/**
 * Connection exception
 */
class ConnectionException(message: String) : Exception(message)

/**
 * SCTP Parameters for INIT/INIT-ACK
 */
sealed class SctpParameter {
    data class ForwardTSNSupported(val supported: Boolean = true) : SctpParameter()
    data class NegotiatedMaxInboundStreams(val streams: UShort) : SctpParameter()
    data class NegotiatedMaxOutboundStreams(val streams: UShort) : SctpParameter()
    data class StateCookie(val cookie: ByteArray) : SctpParameter()
}
