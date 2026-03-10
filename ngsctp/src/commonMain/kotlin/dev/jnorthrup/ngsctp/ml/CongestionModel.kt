package dev.jnorthrup.ngsctp.ml

/**
 * ML Congestion Controller Slot
 * 
 * Placeholder for ONNX/TFLite model inference inside the congestion control loop.
 * This allows ML-based congestion control using trained models.
 * 
 * Supported models:
 * - TinyONNX models (recommended for embedded)
 * - TFLite Micro models
 * 
 * The model can be trained on:
 * - TCP CUBIC features
 * - BBR features  
 * - Custom SCTP multi-path features
 * - Network topology features
 */
object CongestionModelLoader {
    
    /**
     * Load a TinyONNX or TFLite model for inference
     * 
     * @param modelPath Path to the model file
     * @return CongestionPredictor instance
     */
    fun loadModel(modelPath: String): CongestionPredictor? {
        // Placeholder - actual implementation would use:
        // - ONNX Runtime for TinyONNX models
        // - TFLite for TFLite Micro models
        
        // For now, return a default predictor
        return DefaultPredictor()
    }
    
    /**
     * Create a predictor from embedded model bytes
     */
    fun loadModel(modelBytes: ByteArray): CongestionPredictor? {
        // Placeholder for embedded model loading
        return DefaultPredictor()
    }
}

/**
 * Congestion prediction interface
 */
interface CongestionPredictor {
    /**
     * Predict optimal congestion window
     * 
     * @param features Input features from current connection state
     * @return Recommended cwnd in bytes
     */
    fun predictCwnd(features: CongestionFeatures): UInt
    
    /**
     * Predict whether to perform slow start or congestion avoidance
     * 
     * @return true for slow start, false for congestion avoidance
     */
    fun predictPhase(features: CongestionFeatures): Boolean
    
    /**
     * Get recommended ssthresh value
     */
    fun predictSsthresh(features: CongestionFeatures): UInt
}

/**
 * Congestion control input features
 */
data class CongestionFeatures(
    val rtt: Long,                    // Round-trip time in microseconds
    val rttVariance: Long,            // RTT variance
    val bytesInFlight: UInt,           // Current bytes in flight
    val cwnd: UInt,                    // Current congestion window
    val ssthresh: UInt,               // Current slow start threshold
    val packetLossRate: Float,         // Recent loss rate (0.0 - 1.0)
    val ackRate: UInt,                 // ACKs per second
    val pathCount: Int,               // Number of active paths
    val streamCount: Int,             // Number of active streams
    val priority: Int,                // Traffic priority
    val intent: String,               // Traffic intent (e.g., "allreduce-gradient")
    val timestamp: Long               // Current timestamp
)

/**
 * Default predictor - falls back to TCP CUBIC-like behavior
 */
class DefaultPredictor : CongestionPredictor {
    override fun predictCwnd(features: CongestionFeatures): UInt {
        // Simple CUBIC-like growth
        val growth = features.cwnd / 2u
        return features.cwnd + growth
    }
    
    override fun predictPhase(features: CongestionFeatures): Boolean {
        return features.cwnd < features.ssthresh
    }
    
    override fun predictSsthresh(features: CongestionFeatures): UInt {
        // On loss, reduce to 70% of cwnd
        return (features.cwnd * 7u / 10u).coerceAtLeast(4380u)
    }
}

/**
 * Prediction result with confidence
 */
data class Prediction(
    val value: UInt,
    val confidence: Float,  // 0.0 - 1.0
    val modelVersion: String
)
