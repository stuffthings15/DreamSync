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

    @Test public void wavPcm16WritesCanonicalMonoHeaderAndPayload() {
        byte[] wav = AudioOutput.wavPcm16(new short[]{0, 32767, -32767});

        assertEquals(50, wav.length);
        assertEquals("RIFF", ascii(wav, 0, 4));
        assertEquals(42, littleEndianInt(wav, 4));
        assertEquals("WAVE", ascii(wav, 8, 4));
        assertEquals("fmt ", ascii(wav, 12, 4));
        assertEquals(1, littleEndianShort(wav, 20));
        assertEquals(1, littleEndianShort(wav, 22));
        assertEquals(44_100, littleEndianInt(wav, 24));
        assertEquals(16, littleEndianShort(wav, 34));
        assertEquals("data", ascii(wav, 36, 4));
        assertEquals(6, littleEndianInt(wav, 40));
        assertEquals(0, littleEndianShort(wav, 44));
        assertEquals(32767, littleEndianShort(wav, 46));
        assertEquals(32769, littleEndianShort(wav, 48));
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < length; i++) value.append((char) bytes[offset + i]);
        return value.toString();
    }

    private static int littleEndianShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return littleEndianShort(bytes, offset)
                | (littleEndianShort(bytes, offset + 2) << 16);
    }
}
