# Active Context: Kotlin Multiplatform ngSCTP

## Current State

**Project Type**: Kotlin Multiplatform (KMP) - ngSCTP Protocol Implementation

The project has been converted from a Next.js starter template to a Kotlin Multiplatform SCTP protocol implementation.

## Recently Completed

- [x] Created KMP project structure with Gradle
- [x] Implemented core SCTP protocol types (chunks, headers, associations)
- [x] Built SctpEngine with Kotlin coroutines and structured concurrency
- [x] Added io_uring/Netty transport layer for high-performance I/O
- [x] Added AF_XDP and eBPF steering infrastructure
- [x] Created comprehensive documentation

## Current Structure

| File/Directory | Purpose | Status |
|----------------|---------|--------|
| `ngsctp/build.gradle.kts` | Root build config | ✅ Ready |
| `ngsctp/src/commonMain/kotlin/SctpTypes.kt` | Protocol definitions | ✅ Core types defined |
| `ngsctp/src/commonMain/kotlin/SctpEngine.kt` | Protocol engine with coroutines | ✅ Foundation complete |
| `ngsctp/src/jvmMain/kotlin/IoUringSctpTransport.kt` | High-performance I/O | ✅ Framework ready |

## Technical Stack

| Technology | Purpose |
|------------|---------|
| Kotlin 2.0.21 | Language |
| Kotlin Coroutines | Structured concurrency |
| Netty | io_uring transport |
| Kotlin Serialization | Protocol encoding |

## Session History

| Date | Changes |
|------|---------|
| Initial | Template created with base setup |
| 2026-03-10 | Converted to KMP ngSCTP project with coroutines and io_uring |
