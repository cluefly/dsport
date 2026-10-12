package dev.dsport.module.hook;

import android.content.Context;
import android.hardware.Sensor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.SparseArray;

import java.util.Map;

import dev.dsport.module.StepMath;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Replaces step_counter with today's count before {@code dispatchSensorEvent}
 * copies it to the listener. The count is hardware steps since local midnight
 * plus credited steps. Auto-add pauses while real steps are increasing, and for one
 * quiet minute after they stop. It also stops when real steps plus auto-add in the
 * same minute, or the same five minutes, reach the combined ceiling. Step detector
 * and pedometer_minute stay unchanged.
 */
final class SensorDispatch {
    private static final Uri CONFIG = Uri.parse("content://dev.dsport.module.config");
    private static final long CONFIG_TTL_MS = 1000L;
    private static final long REPORT_TTL_MS = 1500L;

    private static volatile long configReadAt;
    private static volatile boolean enabled;
    private static volatile long offset;
    private static volatile long credited;
    private static volatile long creditEpoch;
    private static volatile long stepsPerMinute = StepMath.DEFAULT_PER_MINUTE;
    private static volatile long stepsPerFive = StepMath.STEPS_PER_FIVE_MINUTES;
    private static volatile long stepCap = StepMath.DEFAULT_STEP_CAP;
    private static volatile long dayId = Long.MIN_VALUE;
    private static volatile long dayBase;
    private static long seenRaw = Long.MIN_VALUE;
    private static long lastRawNotedAt;
    private static final Object STATE = new Object();
    private static boolean configLogged;
    private static boolean resolveLogged;
    private static long lastCreditMinute = Long.MIN_VALUE;
    private static final int WINDOW = 8;
    private static final long[] creditMinute = new long[WINDOW];
    private static final long[] creditAmount = new long[WINDOW];
    private static int creditCount;
    private static final long[] realMinuteId = new long[WINDOW];
    private static final long[] realAmount = new long[WINDOW];
    private static int realCount;
    /** Last hardware-today sample used to measure real steps. {@code -1} until known. */
    private static long lastHardwareToday = -1L;
    /** Wall time of the last real-step increase. {@code -1} until one is seen. */
    private static long lastRealChangeAt = -1L;
    private static boolean realHold;
    private static boolean tickerOn;
    private static final long[] lastReportAt = new long[3];
    private static final float[] lastRaw = new float[] {Float.NaN, Float.NaN, Float.NaN};

    private SensorDispatch() {}

    static void onEvent(String packageName, Object queue, Object[] args) {
        if (args == null || args.length < 2 || !(args[0] instanceof Integer) || !(args[1] instanceof float[])) {
            return;
        }
        float[] values = (float[]) args[1];
        if (values.length == 0) {
            return;
        }
        Sensor sensor = resolve(queue, (Integer) args[0]);
        if (sensor == null) {
            return;
        }
        String key = StepMath.keyFor(sensor.getType(), sensor.getStringType());
        if (key == null) {
            return;
        }
        refreshConfig();
        float raw0 = values[0];
        float raw1 = values.length > 1 ? values[1] : Float.NaN;
        float mod0 = raw0;
        float mod1 = raw1;
        if (StepMath.rewritesCumulative(sensor.getType(), sensor.getStringType())) {
            long rawSteps = (long) Math.floor(raw0);
            if (rawSteps < 0L) {
                rawSteps = 0L;
            }
            ensureDay(rawSteps);
            long hardwareToday = StepMath.hardwareToday(rawSteps, dayBase);
            if (StepMath.releasesCredit(packageName)) {
                long realDelta = noteRealSteps(hardwareToday);
                if (realDelta > 0L) {
                    pushNoteReal(realDelta);
                }
                considerAdvance();
            }
            long sent = StepMath.dailySent(hardwareToday, credited, stepCap);
            mod0 = sent;
            values[0] = mod0;
            if (mod0 != raw0 && !"1".equals(System.getProperty("dsport.logged"))) {
                System.setProperty("dsport.logged", "1");
                XposedBridge.log("DSport: " + packageName + " step_counter " + raw0 + " -> " + mod0
                        + " today " + hardwareToday
                        + " credited " + credited + "/" + offset);
            }
        }
        report(packageName, key, raw0, mod0, raw1, mod1);
    }

    private static Sensor resolve(Object queue, int handle) {
        try {
            Object manager = XposedHelpers.getObjectField(queue, "mManager");
            Object map = XposedHelpers.getObjectField(manager, "mHandleToSensor");
            Object sensor = null;
            if (map instanceof Map) {
                sensor = ((Map<?, ?>) map).get(handle);
            } else if (map instanceof SparseArray) {
                sensor = ((SparseArray<?>) map).get(handle);
            }
            if (sensor instanceof Sensor) {
                return (Sensor) sensor;
            }
        } catch (Throwable error) {
            if (!resolveLogged) {
                resolveLogged = true;
                XposedBridge.log(error);
            }
        }
        return null;
    }

    private static void refreshConfig() {
        long now = SystemClock.elapsedRealtime();
        if (configReadAt != 0L && now - configReadAt < CONFIG_TTL_MS) {
            return;
        }
        Context context = currentApplication();
        if (context == null) {
            return;
        }
        try {
            Bundle bundle = context.getContentResolver().call(CONFIG, "getConfig", null, null);
            if (bundle == null) {
                configReadAt = now;
                return;
            }
            long remoteOffset = bundle.getLong("offset", 0L);
            if (remoteOffset < 0L) {
                remoteOffset = 0L;
            }
            long remoteCredited = bundle.getLong("credited", 0L);
            if (remoteCredited < 0L) {
                remoteCredited = 0L;
            }
            long minuteCap = bundle.getLong("perMinute", StepMath.DEFAULT_PER_MINUTE);
            long fiveCap = bundle.getLong("perFive", StepMath.STEPS_PER_FIVE_MINUTES);
            long cap = bundle.getLong("stepCap", StepMath.DEFAULT_STEP_CAP);
            long remoteDay = bundle.getLong("day", Long.MIN_VALUE);
            long remoteEpoch = bundle.getLong("creditEpoch", creditEpoch);
            long remoteBase = bundle.getLong("dayBase", 0L);
            if (remoteBase < 0L) {
                remoteBase = 0L;
            }
            synchronized (STATE) {
                enabled = bundle.getBoolean("enabled", false);
                offset = remoteOffset;
                if (remoteDay != dayId) {
                    dayId = remoteDay;
                    dayBase = remoteBase;
                    credited = remoteCredited;
                    creditEpoch = remoteEpoch;
                    lastCreditMinute = Long.MIN_VALUE;
                    creditCount = 0;
                    realCount = 0;
                    lastHardwareToday = -1L;
                    lastRealChangeAt = -1L;
                    realHold = false;
                    System.clearProperty("dsport.capped");
                } else if (remoteEpoch != creditEpoch || remoteBase != dayBase) {
                    dayBase = remoteBase;
                    credited = remoteCredited;
                    creditEpoch = remoteEpoch;
                    lastCreditMinute = Long.MIN_VALUE;
                    creditCount = 0;
                    realCount = 0;
                    lastHardwareToday = -1L;
                    lastRealChangeAt = -1L;
                    realHold = false;
                    System.clearProperty("dsport.capped");
                } else {
                    dayBase = remoteBase;
                    if (remoteCredited > credited) {
                        credited = remoteCredited;
                    }
                }
                if (credited < 0L) {
                    credited = 0L;
                }
                if (minuteCap > 0L) {
                    stepsPerMinute = minuteCap;
                }
                if (fiveCap > 1L) {
                    stepsPerFive = fiveCap;
                }
                if (cap > 0L) {
                    stepCap = cap;
                }
            }
            configReadAt = now;
            configLogged = false;
        } catch (Throwable error) {
            configReadAt = now;
            if (!configLogged) {
                configLogged = true;
                XposedBridge.log(error);
            }
        }
    }

    /**
     * Moves today's baseline forward at local midnight, then keeps that baseline
     * for every hooked process. Credited steps from the previous day are dropped.
     */
    private static void ensureDay(long rawSteps) {
        long nowWall = System.currentTimeMillis();
        long today = StepMath.localDay(nowWall);
        long knownDay;
        long base;
        long previous;
        synchronized (STATE) {
            knownDay = dayId;
            base = dayBase;
            previous = seenRaw;
        }
        boolean dayChanged = knownDay != today || knownDay == Long.MIN_VALUE;
        long compared = previous >= 0L ? previous : base;
        boolean rebooted = StepMath.counterRebooted(compared, rawSteps);
        boolean noteDue = previous < 0L || nowWall - lastRawNotedAt >= 15_000L;
        if (!dayChanged && !rebooted && !noteDue) {
            synchronized (STATE) {
                seenRaw = rawSteps;
            }
            return;
        }
        long[] rolled = pushRoll(rawSteps, nowWall);
        synchronized (STATE) {
            seenRaw = rawSteps;
            lastRawNotedAt = nowWall;
            if (rolled == null) {
                applyRoll(StepMath.rollDay(dayId, dayBase, credited, previous, rawSteps, today));
                return;
            }
            boolean reset = rolled[0] != dayId;
            applyRoll(new StepMath.DayRoll(rolled[0], rolled[1], rolled[2], reset));
            if (rolled.length > 3 && rolled[3] != creditEpoch) {
                creditEpoch = rolled[3];
                credited = Math.max(0L, rolled[2]);
                lastCreditMinute = Long.MIN_VALUE;
                creditCount = 0;
                System.clearProperty("dsport.capped");
            }
        }
    }

    private static void applyRoll(StepMath.DayRoll roll) {
        boolean newDay = roll.day != dayId;
        boolean baseChanged = roll.base != dayBase;
        dayId = roll.day;
        dayBase = roll.base;
        if (newDay) {
            credited = roll.credited;
            lastCreditMinute = Long.MIN_VALUE;
            creditCount = 0;
            realCount = 0;
            lastHardwareToday = -1L;
            lastRealChangeAt = -1L;
            realHold = false;
            System.clearProperty("dsport.capped");
        } else if (baseChanged) {
            realCount = 0;
            lastHardwareToday = -1L;
            lastRealChangeAt = -1L;
            realHold = false;
        }
        if (credited < 0L) {
            credited = 0L;
        }
    }

    private static long[] pushRoll(long rawSteps, long nowWall) {
        Context context = currentApplication();
        if (context == null) {
            return null;
        }
        Bundle extras = new Bundle();
        extras.putLong("raw", rawSteps);
        extras.putLong("now", nowWall);
        try {
            Bundle result = context.getContentResolver().call(CONFIG, "rollDay", null, extras);
            if (result == null) {
                return null;
            }
            return new long[] {
                    result.getLong("day", Long.MIN_VALUE),
                    result.getLong("dayBase", rawSteps),
                    result.getLong("credited", 0L),
                    result.getLong("epoch", creditEpoch)
            };
        } catch (Throwable error) {
            XposedBridge.log(error);
            return null;
        }
    }

    /**
     * Records real steps since the previous sample into the current wall minute.
     * A drop is a reboot that {@link #applyRoll} already rebased, so it is ignored here.
     */
    /** Returns the real-step increase, or 0 when this sample only sets the baseline. */
    private static long noteRealSteps(long hardwareToday) {
        synchronized (STATE) {
            if (hardwareToday < 0L) {
                hardwareToday = 0L;
            }
            if (lastHardwareToday < 0L || hardwareToday < lastHardwareToday) {
                lastHardwareToday = hardwareToday;
                return 0L;
            }
            long delta = hardwareToday - lastHardwareToday;
            lastHardwareToday = hardwareToday;
            if (delta > 0L) {
                long nowWall = System.currentTimeMillis();
                lastRealChangeAt = nowWall;
                rememberReal(nowWall / 60_000L, delta);
            }
            return delta;
        }
    }

    /** Releases auto-add on the clock. Standing still still produces no step events. */
    static void startTicker() {
        synchronized (STATE) {
            if (tickerOn) {
                return;
            }
            tickerOn = true;
        }
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(15_000L);
                } catch (InterruptedException error) {
                    return;
                }
                refreshConfig();
                considerAdvance();
            }
        }, "dsport-auto");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Releases auto steps once per wall-clock minute, except while real steps are changing.
     * The room is what remains of the per-minute and five-minute ceilings after real steps
     * in the same windows. WeChat drops a slice that reaches the five-minute ceiling,
     * so release stops one earlier.
     */
    private static void considerAdvance() {
        long before;
        synchronized (STATE) {
            before = credited;
        }
        long[] pushed = pushTick();
        if (pushed == null) {
            return;
        }
        synchronized (STATE) {
            creditEpoch = pushed[1];
            credited = Math.max(0L, pushed[0]);
            configReadAt = SystemClock.elapsedRealtime();
            long added = credited - before;
            if (added > 0L) {
                XposedBridge.log("DSport: credit " + credited + "/" + offset + " +" + added);
            }
        }
    }

    private static void pushNoteReal(long delta) {
        Context context = currentApplication();
        if (context == null || delta <= 0L) {
            return;
        }
        Bundle extras = new Bundle();
        extras.putLong("now", System.currentTimeMillis());
        extras.putLong("delta", delta);
        try {
            context.getContentResolver().call(CONFIG, "noteReal", null, extras);
        } catch (Throwable error) {
            XposedBridge.log(error);
        }
    }

    private static long[] pushTick() {
        Context context = currentApplication();
        if (context == null) {
            return null;
        }
        Bundle extras = new Bundle();
        extras.putLong("now", System.currentTimeMillis());
        try {
            Bundle result = context.getContentResolver().call(CONFIG, "tick", null, extras);
            if (result == null) {
                return null;
            }
            return new long[] {
                    result.getLong("credited", 0L),
                    result.getLong("epoch", creditEpoch)
            };
        } catch (Throwable error) {
            XposedBridge.log(error);
            return null;
        }
    }

    private static long amountIn(long[] minutes, long[] amounts, int count, long wallMinute) {
        long sum = 0L;
        for (int i = 0; i < count; i++) {
            if (minutes[i] == wallMinute) {
                sum += amounts[i];
            }
        }
        return sum;
    }

    private static long realInWindow(long wallMinute) {
        long sum = 0L;
        int kept = 0;
        for (int i = 0; i < realCount; i++) {
            if (realMinuteId[i] <= wallMinute && wallMinute - realMinuteId[i] < 5L) {
                realMinuteId[kept] = realMinuteId[i];
                realAmount[kept] = realAmount[i];
                sum += realAmount[i];
                kept++;
            }
        }
        realCount = kept;
        return sum;
    }

    private static void rememberReal(long wallMinute, long amount) {
        if (realCount > 0 && realMinuteId[realCount - 1] == wallMinute) {
            realAmount[realCount - 1] += amount;
            return;
        }
        if (realCount == WINDOW) {
            System.arraycopy(realMinuteId, 1, realMinuteId, 0, WINDOW - 1);
            System.arraycopy(realAmount, 1, realAmount, 0, WINDOW - 1);
            realCount = WINDOW - 1;
        }
        realMinuteId[realCount] = wallMinute;
        realAmount[realCount] = amount;
        realCount++;
    }

    private static long releasedInWindow(long wallMinute) {
        long sum = 0L;
        int kept = 0;
        for (int i = 0; i < creditCount; i++) {
            if (wallMinute - creditMinute[i] < 5L) {
                creditMinute[kept] = creditMinute[i];
                creditAmount[kept] = creditAmount[i];
                sum += creditAmount[i];
                kept++;
            }
        }
        creditCount = kept;
        return sum;
    }

    private static void rememberRelease(long wallMinute, long amount) {
        if (creditCount == WINDOW) {
            System.arraycopy(creditMinute, 1, creditMinute, 0, WINDOW - 1);
            System.arraycopy(creditAmount, 1, creditAmount, 0, WINDOW - 1);
            creditCount = WINDOW - 1;
        }
        creditMinute[creditCount] = wallMinute;
        creditAmount[creditCount] = amount;
        creditCount++;
    }

    /** Returns the stored credited total and the credit epoch. */
    private static long[] pushCredited(long next) {
        Context context = currentApplication();
        if (context == null) {
            return new long[] {next, creditEpoch};
        }
        Bundle extras = new Bundle();
        extras.putLong("credited", next);
        extras.putLong("epoch", creditEpoch);
        try {
            Bundle result = context.getContentResolver().call(CONFIG, "credit", null, extras);
            if (result != null) {
                return new long[] {
                        result.getLong("credited", next),
                        result.getLong("epoch", creditEpoch)
                };
            }
        } catch (Throwable error) {
            XposedBridge.log(error);
        }
        return new long[] {next, creditEpoch};
    }

    private static void report(String packageName, String key, float raw0, float mod0, float raw1,
            float mod1) {
        int slot = slotOf(key);
        long now = SystemClock.elapsedRealtime();
        if (slot >= 0 && now - lastReportAt[slot] < REPORT_TTL_MS) {
            return;
        }
        if (slot >= 0) {
            lastRaw[slot] = raw0;
            lastReportAt[slot] = now;
        }
        Context context = currentApplication();
        if (context == null) {
            return;
        }
        Bundle extras = new Bundle();
        extras.putString("sensor", key);
        extras.putString("pkg", packageName);
        extras.putFloat("raw0", raw0);
        extras.putFloat("mod0", mod0);
        extras.putFloat("raw1", raw1);
        extras.putFloat("mod1", mod1);
        extras.putBoolean("modified", enabled && !StepMath.KEY_DETECTOR.equals(key) && mod0 != raw0);
        extras.putLong("time", System.currentTimeMillis());
        try {
            context.getContentResolver().call(CONFIG, "report", null, extras);
        } catch (Throwable ignored) {
            // The page still shows this process's own listener. Status is optional.
        }
    }

    private static int slotOf(String key) {
        if (StepMath.KEY_COUNTER.equals(key)) {
            return 0;
        }
        if (StepMath.KEY_DETECTOR.equals(key)) {
            return 1;
        }
        if (StepMath.KEY_MINUTE.equals(key)) {
            return 2;
        }
        return -1;
    }

    private static Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object app = activityThread.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }
}
