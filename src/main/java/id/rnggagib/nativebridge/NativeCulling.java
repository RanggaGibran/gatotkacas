package id.rnggagib.nativebridge;

public final class NativeCulling {
    // Static native method bound to Rust cdylib symbol
    public static native boolean shouldCull(
            double distance,
            double speed,
            double cosAngle,
            double maxDistance,
            double speedThreshold,
            double cosAngleThreshold
    );

    // Batch version to reduce JNI overhead; arrays must be same length
    public static native boolean[] shouldCullBatch(
        double[] distances,
        double[] speeds,
        double[] cosAngles,
        double maxDistance,
        double speedThreshold,
        double cosAngleThreshold
    );

    // Batch variant writing into a preallocated boolean[] to avoid per-call allocation
    public static native void shouldCullBatchInto(
        double[] distances,
        double[] speeds,
        double[] cosAngles,
        boolean[] outFlags,
        double maxDistance,
        double speedThreshold,
        double cosAngleThreshold
    );
}
