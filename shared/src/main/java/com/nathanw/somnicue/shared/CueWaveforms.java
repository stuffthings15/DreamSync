package com.nathanw.somnicue.shared;

import java.util.Random;

public final class CueWaveforms {
    public static final int SAMPLE_RATE = 44_100;
    public static final String VERSION = "deep-pink-pair-v1_lucid-660-880-v1";

    private CueWaveforms() {}

    public static float[] deepPinkPair(float gain, long seed) {
        int burstSamples = (int) (0.050 * SAMPLE_RATE);
        int secondStart = (int) (1.075 * SAMPLE_RATE);
        float[] output = new float[secondStart + burstSamples];
        float[] burst = pinkBurst(burstSamples, seed);
        copy(output, burst, 0, gain);
        copy(output, burst, secondStart, gain);
        return output;
    }

    public static float[] lucidChime(float gain) {
        int toneSamples = (int) (0.120 * SAMPLE_RATE);
        int gapSamples = (int) (0.100 * SAMPLE_RATE);
        float[] output = new float[toneSamples * 2 + gapSamples];
        sine(output, 0, toneSamples, 660.0, gain * 0.75f);
        sine(output, toneSamples + gapSamples, toneSamples, 880.0, gain * 0.75f);
        return output;
    }

    private static float[] pinkBurst(int sampleCount, long seed) {
        float[] values = new float[sampleCount];
        Random random = new Random(seed);
        double b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
        double max = 0.0001;
        for (int i = 0; i < sampleCount; i++) {
            double white = random.nextDouble() * 2.0 - 1.0;
            b0 = 0.99886 * b0 + white * 0.0555179;
            b1 = 0.99332 * b1 + white * 0.0750759;
            b2 = 0.96900 * b2 + white * 0.1538520;
            b3 = 0.86650 * b3 + white * 0.3104856;
            b4 = 0.55000 * b4 + white * 0.5329522;
            b5 = -0.7616 * b5 - white * 0.0168980;
            double pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362;
            b6 = white * 0.115926;
            values[i] = (float) pink;
            max = Math.max(max, Math.abs(pink));
        }
        int fadeSamples = Math.max(1, (int) (0.005 * SAMPLE_RATE));
        for (int i = 0; i < sampleCount; i++) {
            double envelope = 1.0;
            if (i < fadeSamples) envelope = i / (double) fadeSamples;
            if (i >= sampleCount - fadeSamples) {
                envelope = Math.min(envelope, (sampleCount - 1 - i) / (double) fadeSamples);
            }
            values[i] = (float) ((values[i] / max) * envelope);
        }
        return values;
    }

    private static void copy(float[] destination, float[] burst, int offset, float gain) {
        for (int i = 0; i < burst.length; i++) destination[offset + i] = burst[i] * gain;
    }

    private static void sine(float[] destination, int offset, int count, double hz, float gain) {
        int fadeSamples = Math.max(1, (int) (0.015 * SAMPLE_RATE));
        for (int i = 0; i < count; i++) {
            double envelope = 1.0;
            if (i < fadeSamples) envelope = i / (double) fadeSamples;
            if (i >= count - fadeSamples) {
                envelope = Math.min(envelope, (count - 1 - i) / (double) fadeSamples);
            }
            destination[offset + i] = (float) (
                    Math.sin(2.0 * Math.PI * hz * i / SAMPLE_RATE) * gain * envelope);
        }
    }
}
