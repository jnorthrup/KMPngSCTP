# Active Context: KMPngSCTP v0.1.0 Foundation

## Current State

**Project Type**: Kotlin Multiplatform (KMP) - ngSCTP Protocol Implementation

A pure KMP library implementing Next-generation SCTP with structured concurrency, io_uring, and ML congestion control.

## Recently Completed

- [x] Created KMP project structure with Gradle
- [x] Implemented core SCTP protocol types (chunks, headers, associations)
- [x] Built SctpEngine with Kotlin coroutines and structured concurrency
- [x] Added io_uring/Netty transport layer for high-performance I/O
- [x] Added AF_XDP and eBPF steering infrastructure
- [x] Created comprehensive documentation
- [x] Added NgSctpStream with Channel-based message delivery
- [x] Added NgSctpAssociation with 4-way handshake (INIT/INIT-ACK/COOKIE_ECHO/COOKIE_ACK)
- [x] Implemented TLV chunk system with Spirit parser
- [x] Added ML congestion model slot (TinyONNX/TFLite placeholder)
- [x] Created protocol.md documentation

## Current Structure

| File/Directory | Purpose | Status |
|----------------|---------|--------|
| `README.md` | Project overview | ✅ Ready |
| `ngsctp/build.gradle.kts` | Root build config | ✅ Ready |
| `ngsctp/settings.gradle.kts` | Gradle settings | ✅ Ready |
| `ngsctp/src/commonMain/kotlin/dev/jnorthrup/ngsctp/NgSctpAssociation.kt` | Main association entry point | ✅ Ready |
| `ngsctp/src/commonMain/kotlin/dev/jnorthrup/ngsctp/NgSctpStream.kt` | Stream with channels | ✅ Ready |
| `ngsctp/src/commonMain/kotlin/dev/jnorthrup/ngsctp/chunks/NgChunk.kt` | TLV chunk definitions | ✅ Ready |
| `ngsctp/src/commonMain/kotlin/dev/jnorthrup/ngsctp/parser/NgSctpParser.kt` | Spirit-based parser | ✅ Ready |
| `ngsctp/src/commonMain/kotlin/dev/jnorthrup/ngsctp/ml/CongestionModel.kt` | ML congestion slot | ✅ Ready |
| `ngsctp/src/commonMain/kotlin/SctpTypes.kt` | Legacy protocol definitions | ✅ Kept for compatibility |
| `ngsctp/src/commonMain/kotlin/SctpEngine.kt` | Legacy engine | ✅ Kept for compatibility |
| `ngsctp/src/jvmMain/kotlin/IoUringSctpTransport.kt` | io_uring transport | ✅ Framework ready |
| `docs/protocol.md` | Protocol specification | ✅ Ready |

## Technical Stack

| Technology | Purpose |
|------------|---------|
| Kotlin 2.0.21 | Language |
| Kotlin Coroutines 1.10.1 | Structured concurrency |
| Kotlin Serialization | Protocol encoding |
| kotlin-spirit-parser 2.5.0 | Zero-copy TLV parsing |
| Ktor Network 3.0.0 | Socket base |
| io_uring (JVM) | High-performance async I/O |
| AF_XDP + eBPF | Packet steering |

## Key Design Patterns

### 1. Association = Coroutine Scope
```kotlin
class NgSctpAssociation : CoroutineScope by scope {
    // One association = one structured scope
    // Cancellation cascades to all streams
}
```

### 2. Stream = Channel + Child Scope
```kotlin
class NgSctpStream : CoroutineScope {
    val sendChannel: SendChannel<ByteBuffer>
    val receiveChannel: ReceiveChannel<ByteBuffer>
    // Zero-copy, cancellable, backpressure-aware
}
```

### 3. TLV Parsing with Spirit
- Unknown chunks skipped automatically
- Wireshark compatible forever
- Zero-copy ByteBuffer operations

## Session History

| Date | Changes |
|------|---------|
| Initial | Template created with base setup |
| 2026-03-10 | Converted to KMP ngSCTP project with coroutines and io_uring |
| 2026-03-10 | v0.1.0 foundation: NgSctpStream, NgSctpAssociation, TLV chunks, Spirit parser, ML slot |

## Next Steps (from user request)

1. Full `NgSctpAssociation` + handshake - **IN PROGRESS**
2. io_uring + eBPF XDP channel router
3. ML congestion model slot (ONNX inference)
4. Native Linux implementation (posix + CMT)
5. Demo app

## Quick Start

```kotlin
val assoc = NgSctpAssociation.connect(remote = InetSocketAddress(...))
val stream = assoc.openStream(priority = 1, intent = "allreduce-gradient")
stream.sendChannel.send(myTensorBytes)  // structured, cancellable
```
