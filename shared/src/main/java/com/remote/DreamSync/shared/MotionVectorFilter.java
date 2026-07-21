package com.remote.DreamSync.shared;

/** Converts accelerometer or already-linear vectors into dynamic acceleration. */
public final class MotionVectorFilter {
    private double gravityX;
    private double gravityY;
    private double gravityZ;
    private boolean initialized;

    public double observe(boolean alreadyLinear, double x, double y, double z) {
        if (alreadyLinear) return magnitude(x, y, z);
        if (!initialized) {
            gravityX = x;
            gravityY = y;
            gravityZ = z;
            initialized = true;
        }
        double alpha = 0.90;
        gravityX = alpha * gravityX + (1.0 - alpha) * x;
        gravityY = alpha * gravityY + (1.0 - alpha) * y;
        gravityZ = alpha * gravityZ + (1.0 - alpha) * z;
        return magnitude(x - gravityX, y - gravityY, z - gravityZ);
    }

    public void reset() {
        initialized = false;
        gravityX = 0.0;
        gravityY = 0.0;
        gravityZ = 0.0;
    }

    private static double magnitude(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }
}
