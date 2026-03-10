# ngSCTP - Next Generation SCTP

A modern Kotlin Multiplatform (KMP) implementation of SCTP with:

- **Kotlin structured concurrency** - Channels and coroutines for clean stream multiplexing
- **io_uring support** - Zero-copy async I/O for production performance
- **eBPF packet steering** - Kernel-level early demux and routing
- **AF_XDP path** - Kernel-bypass option for ultra-low latency

This is the "redemption" for QUIC - native multi-homing, cleartext control plane, and proper transport semantics without the UDP tax.

## Architecture

```
ngsctp/
├── src/
│   ├── commonMain/kotlin/
│   │   ├── SctpTypes.kt     # Protocol definitions (chunks, headers, states)
│   │   └── SctpEngine.kt    # Core protocol state machine with coroutines
│   └── jvmMain/kotlin/
│       └── IoUringSctpTransport.kt  # High-performance I/O layer
└── build.gradle.kts
```

## Why ngSCTP Over QUIC?

| Feature | QUIC (HTTP/3) | ngSCTP |
|---------|---------------|--------|
| Multi-homing | ❌ User-space reinvention | ✅ Native since 2000 |
| Cleartext control plane | ❌ Everything encrypted | ✅ ACKs visible |
| UDP tax | ❌ Firewall/NAT issues | ✅ SCTP-aware middleboxes |
| Connection migration | ❌ Complex handshakes | ✅ Built-in |
| Stream multiplexing | ✅ Built-in | ✅ Built-in |
| Kernel support | ❌ Userspace required | ✅ Linux kernel since 2.6 |

## Building

```bash
# Generate gradle wrapper
cd ngsctp
./gradlew wrapper

# Build JVM target
./gradlew jvmJar

# Run
java -jar build/libs/ngsctp-jvm-0.1.0-SNAPSHOT.jar
```

## Usage

```kotlin
val config = SctpConfig(
    receiverWindowSize = 4_194_304u,
    maxOutboundStreams = 10u,
    maxInboundStreams = 10u
)

val engine = SctpEngine(config)

// Client: Connect to server
val association = engine.connect(
    remoteAddress = TransportAddress("192.168.1.1", 38412u),
    localAddress = TransportAddress("192.168.1.2", 0u, isPrimary = true),
    localPort = 38412u,
    remotePort = 38412u
)

// Send message
engine.send(association, SctpMessage(
    streamId = 0u,
    streamSequenceNumber = 0u,
    payloadProtocolId = 0u,
    userData = "Hello, SCTP!".toByteArray()
))

// Receive messages
engine.messages.collect { message ->
    println("Received: ${String(message.userData)}")
}
```

## I/O Backends

1. **Standard NIO** - Basic Java NIO DatagramChannel
2. **io_uring** - Netty's io_uring support (Linux 5.1+)
3. **AF_XDP** - Kernel bypass via XDP (requires libxdp bindings)

For maximum performance, use io_uring or AF_XDP on modern Linux kernels.

## Status

**Early Development** - This is a foundation for implementing ngSCTP. Key components still needed:

- [ ] Complete wire format parsing/serialization
- [ ] Congestion control (TCP-like)
- [ ] Multi-homing path management
- [ ] Real io_uring integration with proper JNI
- [ ] eBPF program loading
- [ ] AF_XDP native bindings
