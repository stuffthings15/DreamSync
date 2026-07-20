package com.remote.DreamSync;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AudioOutputTest {
    @Test public void pcm16ClampsGainAndSamples() {
        short[] encoded = AudioOutput.pcm16(
                new float[]{-2.0f, -1.0f, 0.0f, 0.5f, 1.0f, 2.0f}, 1.0f);

        assertArrayEquals(new short[]{
                -32767, -32767, 0, 16384, 32767, 32767
        }, encoded);
    }

    @Test public void pcm16UsesRequestedQuietGain() {
        short[] encoded = AudioOutput.pcm16(new float[]{1.0f}, 0.04f);

        assertEquals(1311, encoded[0]);
    }

    @Test public void pcm16RejectsNegativeGain() {
        short[] encoded = AudioOutput.pcm16(new float[]{1.0f, -1.0f}, -0.5f);

        assertArrayEquals(new short[]{0, 0}, encoded);
    }
}
