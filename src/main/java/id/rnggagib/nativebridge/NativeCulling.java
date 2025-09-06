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

    // Batch with per-entity type codes and per-type thresholds; writes into outFlags
    public static native void shouldCullBatchIntoByType(
        double[] distances,
        double[] speeds,
        double[] cosAngles,
        int[] typeCodes,
        double[] typeMaxDistance,
        double[] typeSpeedThreshold,
        double[] typeCosAngleThreshold,
        boolean[] outFlags
    );

    // DirectByteBuffer variant to reduce array copies across JNI.
    // Buffers must be direct; outFlags is a byte buffer where 0=false, 1=true.
    public static native void shouldCullBatchIntoDirect(
        java.nio.ByteBuffer distancesDoubles,
        java.nio.ByteBuffer speedsDoubles,
        java.nio.ByteBuffer cosAnglesDoubles,
        java.nio.ByteBuffer outFlagsBytes,
        int count,
        double maxDistance,
        double speedThreshold,
        double cosAngleThreshold
    );
}
