package dev.dsport.module;

import android.os.Bundle;

import java.util.concurrent.ConcurrentHashMap;

/** Last sample reported by a hooked process, kept in this app's process. */
public final class HookStatus {
    public static final class Sample {
        public final String sensor;
        public final String pkg;
        public final float raw0;
        public final float mod0;
        public final float raw1;
        public final float mod1;
        public final boolean modified;
        public final long time;

        public Sample(String sensor, String pkg, float raw0, float mod0, float raw1, float mod1,
                boolean modified, long time) {
            this.sensor = sensor;
            this.pkg = pkg;
            this.raw0 = raw0;
            this.mod0 = mod0;
            this.raw1 = raw1;
            this.mod1 = mod1;
            this.modified = modified;
            this.time = time;
        }
    }

    private static final ConcurrentHashMap<String, Sample> SAMPLES = new ConcurrentHashMap<>();

    private HookStatus() {}

    public static void update(Bundle extras) {
        if (extras == null) {
            return;
        }
        String sensor = extras.getString("sensor");
        if (sensor == null) {
            return;
        }
        Sample sample = new Sample(
                sensor,
                extras.getString("pkg", ""),
                extras.getFloat("raw0", Float.NaN),
                extras.getFloat("mod0", Float.NaN),
                extras.getFloat("raw1", Float.NaN),
                extras.getFloat("mod1", Float.NaN),
                extras.getBoolean("modified", false),
                extras.getLong("time", 0L));
        String pkg = sample.pkg == null ? "" : sample.pkg;
        SAMPLES.put(sensor + "\n" + pkg, sample);
    }

    /** Prefer the process that releases credit. WeChat and the step service no longer overwrite each other. */
    public static Sample get(String sensor) {
        Sample fallback = null;
        for (Sample sample : SAMPLES.values()) {
            if (!sensor.equals(sample.sensor)) {
                continue;
            }
            if (StepMath.releasesCredit(sample.pkg)) {
                return sample;
            }
            if (fallback == null) {
                fallback = sample;
            }
        }
        return fallback;
    }

    public static Bundle snapshot() {
        Bundle out = new Bundle();
        for (Sample sample : SAMPLES.values()) {
            Bundle one = new Bundle();
            one.putString("pkg", sample.pkg);
            one.putFloat("raw0", sample.raw0);
            one.putFloat("mod0", sample.mod0);
            one.putFloat("raw1", sample.raw1);
            one.putFloat("mod1", sample.mod1);
            one.putBoolean("modified", sample.modified);
            one.putLong("time", sample.time);
            String pkg = sample.pkg == null ? "" : sample.pkg;
            out.putBundle(sample.sensor + "/" + pkg, one);
        }
        return out;
    }
}
