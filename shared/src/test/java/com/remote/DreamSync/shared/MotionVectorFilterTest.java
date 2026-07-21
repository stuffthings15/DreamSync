package com.remote.DreamSync.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MotionVectorFilterTest {
    @Test public void linearAccelerationIsNotFilteredTwice() {
        MotionVectorFilter filter = new MotionVectorFilter();
        assertEquals(5.0, filter.observe(true, 3.0, 4.0, 0.0), 0.0001);
    }

    @Test public void stationaryGravityProducesNoMotion() {
        MotionVectorFilter filter = new MotionVectorFilter();
        assertEquals(0.0, filter.observe(false, 0.0, 0.0, 9.81), 0.0001);
        assertEquals(0.0, filter.observe(false, 0.0, 0.0, 9.81), 0.0001);
    }

    @Test public void movementAgainstGravityProducesPositiveMotion() {
        MotionVectorFilter filter = new MotionVectorFilter();
        filter.observe(false, 0.0, 0.0, 9.81);
        assertTrue(filter.observe(false, 2.0, 0.0, 9.81) > 1.0);
    }
}
