package dev.dsport.module;

import android.content.Context;
import android.content.SharedPreferences;

public final class ConfigStore {
    public static final String PREF = "dsport";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_OFFSET = "offset";
    private static final String KEY_CREDITED = "credited";
    private static final String KEY_PER_MINUTE = "perMinute";
    private static final String KEY_PER_FIVE = "perFive";
    private static final String KEY_BASE = "base";
    private static final String KEY_ADDITION = "addition";
    private static final String KEY_STEP_CAP = "stepCap";
    private static final String KEY_DAY = "day";
    private static final String KEY_DAY_BASE = "dayBase";
    private static final String KEY_LAST_RAW = "lastRaw";
    private static final String KEY_CAP_UPDATED = "stepCap48000";
    /** Set after the stored per-minute ceiling moves from 300 to 290. A later edit is kept. */
    private static final String KEY_MINUTE_MARGIN = "minuteMargin10";
    /** Bumped when auto-add is cleared at midnight or by the reset button. */
    private static final String KEY_CREDIT_EPOCH = "creditEpoch";
    /** Wall minute that already received this day's auto-add. */
    private static final String KEY_AUTO_MINUTE = "autoMinute";
    /** Wall time of the last real-step increase. {@code -1} when unknown. */
    private static final String KEY_REAL_AT = "realChangeAt";
    /** Recent minutes as {@code minute:real:auto}, oldest first. */
    private static final String KEY_BUCKETS = "buckets";

    public static final class Config {
        public final boolean enabled;
        /** Absolute step target. The hook releases until {@code credited} reaches this. */
        public final long goal;
        public final long credited;
        /** Credited value when the current add-on round started. */
        public final long base;
        /** Amount typed while the switch is off. */
        public final long addition;
        public final long perMinute;
        public final long perFive;
        public final long stepCap;
        /** Local epoch day of {@code dayBase}. {@code Long.MIN_VALUE} before the first sample. */
        public final long day;
        /** Hardware step_counter at the start of {@code day}. */
        public final long dayBase;
        /** Changes when today's auto-add is cleared. The hook drops a stale total on this. */
        public final long creditEpoch;

        public Config(boolean enabled, long goal, long credited, long base, long addition,
                long perMinute, long perFive, long stepCap, long day,
                long dayBase, long creditEpoch) {
            this.enabled = enabled;
            this.goal = goal;
            this.credited = credited;
            this.base = base;
            this.addition = addition;
            this.perMinute = perMinute;
            this.perFive = perFive;
            this.stepCap = stepCap;
            this.day = day;
            this.dayBase = dayBase;
            this.creditEpoch = creditEpoch;
        }
    }

    private ConfigStore() {}

    public static Config get(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long perMinute = readPerMinute(prefs);
        long perFive = prefs.getLong(KEY_PER_FIVE, StepMath.STEPS_PER_FIVE_MINUTES);
        if (perFive < 2L) {
            perFive = StepMath.STEPS_PER_FIVE_MINUTES;
        }
        long stepCap = readStepCap(prefs);
        long day = prefs.getLong(KEY_DAY, Long.MIN_VALUE);
        long dayBase = prefs.getLong(KEY_DAY_BASE, 0L);
        long[] totals = forwardTotals(prefs.getLong(KEY_OFFSET, 0L), effectiveCredited(prefs));
        long base = prefs.getLong(KEY_BASE, 0L);
        long addition = prefs.getLong(KEY_ADDITION, 0L);
        if (base < 0L) {
            base = 0L;
        }
        if (addition < 0L) {
            addition = 0L;
        }
        if (dayBase < 0L) {
            dayBase = 0L;
        }
        return new Config(
                prefs.getBoolean(KEY_ENABLED, false),
                totals[0],
                totals[1],
                base,
                addition,
                perMinute,
                perFive,
                stepCap,
                day,
                dayBase,
                prefs.getLong(KEY_CREDIT_EPOCH, 0L));
    }

    /** Credited steps for the local day. Yesterday's total does not carry over. */
    private static long effectiveCredited(SharedPreferences prefs) {
        long credited = prefs.getLong(KEY_CREDITED, 0L);
        if (credited < 0L) {
            credited = 0L;
        }
        long storedDay = prefs.getLong(KEY_DAY, Long.MIN_VALUE);
        if (storedDay != StepMath.localDay(System.currentTimeMillis())) {
            return 0L;
        }
        return credited;
    }

    /**
     * Aligns the stored day with {@code nowMillis}. Returns day, hardware baseline, credited.
     * Midnight clears credited and keeps the counter's last sample as today's zero.
     * A reboot sets the baseline to 0, because the counter itself starts over.
     */
    public static long[] rollDay(Context context, long rawSteps, long nowMillis) {
        if (rawSteps < 0L) {
            rawSteps = 0L;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long storedDay = prefs.getLong(KEY_DAY, Long.MIN_VALUE);
        long storedBase = prefs.getLong(KEY_DAY_BASE, 0L);
        long credited = prefs.getLong(KEY_CREDITED, 0L);
        long lastRaw = prefs.getLong(KEY_LAST_RAW, Long.MIN_VALUE);
        long today = StepMath.localDay(nowMillis);
        StepMath.DayRoll roll = StepMath.rollDay(storedDay, storedBase, credited, lastRaw, rawSteps, today);
        long epoch = prefs.getLong(KEY_CREDIT_EPOCH, 0L);
        android.content.SharedPreferences.Editor editor = prefs.edit()
                .putLong(KEY_LAST_RAW, rawSteps);
        if (roll.day != storedDay || roll.base != storedBase || roll.credited != Math.max(0L, credited)) {
            editor.putLong(KEY_DAY, roll.day)
                    .putLong(KEY_DAY_BASE, roll.base)
                    .putLong(KEY_CREDITED, roll.credited);
        }
        if (roll.day != storedDay) {
            epoch += 1L;
            editor.putLong(KEY_CREDIT_EPOCH, epoch);
        }
        editor.commit();
        return new long[] {roll.day, roll.base, roll.credited, epoch};
    }

    /**
     * Clears today's auto-added steps and keeps the hardware counter as today's real steps.
     * The baseline goes to 0 so the current counter is not treated as already counted.
     * Returns the new epoch. A later credit from before this call is ignored.
     */
    public static long resetCredit(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long epoch = prefs.getLong(KEY_CREDIT_EPOCH, 0L) + 1L;
        prefs.edit()
                .putLong(KEY_CREDITED, 0L)
                .putLong(KEY_DAY_BASE, 0L)
                .putLong(KEY_CREDIT_EPOCH, epoch)
                .putLong(KEY_AUTO_MINUTE, Long.MIN_VALUE)
                .putLong(KEY_REAL_AT, -1L)
                .putString(KEY_BUCKETS, "")
                .commit();
        return epoch;
    }

    /** Records real steps so a later tick can pause auto-add and keep them inside the ceilings. */
    public static void noteReal(Context context, long nowMillis, long delta) {
        if (delta <= 0L) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long minute = nowMillis / 60_000L;
        String buckets = addBucket(prefs.getString(KEY_BUCKETS, ""), minute, delta, 0L);
        prefs.edit()
                .putLong(KEY_REAL_AT, nowMillis)
                .putString(KEY_BUCKETS, buckets)
                .commit();
    }

    /**
     * Adds at most one minute of auto steps while the user is not walking.
     * Safe to call often: a wall minute is released only once, from any process.
     * Returns the credited total after the call.
     */
    public static long tick(Context context, long nowMillis) {
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long today = StepMath.localDay(nowMillis);
        long credited = prefs.getLong(KEY_CREDITED, 0L);
        if (credited < 0L) {
            credited = 0L;
        }
        if (prefs.getLong(KEY_DAY, Long.MIN_VALUE) != today) {
            return 0L;
        }
        if (StepMath.autoPaused(prefs.getLong(KEY_REAL_AT, -1L), nowMillis)) {
            return credited;
        }
        long minute = nowMillis / 60_000L;
        if (prefs.getLong(KEY_AUTO_MINUTE, Long.MIN_VALUE) == minute) {
            return credited;
        }
        long offset = prefs.getLong(KEY_OFFSET, 0L);
        if (offset < 0L) {
            offset = 0L;
        }
        long perMinute = readPerMinute(prefs);
        long perFive = prefs.getLong(KEY_PER_FIVE, StepMath.STEPS_PER_FIVE_MINUTES);
        if (perFive < 2L) {
            perFive = StepMath.STEPS_PER_FIVE_MINUTES;
        }
        long cap = readStepCap(prefs);
        long[] window = bucketSums(prefs.getString(KEY_BUCKETS, ""), minute);
        long expected = StepMath.expectedReal(window[0], window[1], perMinute);
        long room = StepMath.releaseRoom(credited, cap);
        long release = StepMath.combinedRelease(credited, offset, expected, window[2],
                window[3], window[4], perMinute, perFive, room);
        String buckets = prefs.getString(KEY_BUCKETS, "");
        if (release > 0L) {
            long next = offset > 0L ? Math.min(offset, credited + release) : credited;
            if (next < credited) {
                next = credited;
            }
            buckets = addBucket(buckets, minute, 0L, next - credited);
            credited = next;
        }
        prefs.edit()
                .putLong(KEY_CREDITED, credited)
                .putLong(KEY_AUTO_MINUTE, minute)
                .putString(KEY_BUCKETS, buckets)
                .commit();
        return credited;
    }

    /** {@code realThis, realPrev, autoThis, realFive, autoFive}. */
    private static long[] bucketSums(String text, long minute) {
        long[] sums = new long[5];
        if (text == null || text.isEmpty()) {
            return sums;
        }
        String[] parts = text.split(",");
        for (int i = 0; i < parts.length; i++) {
            long[] row = parseBucket(parts[i]);
            if (row == null || row[0] > minute || minute - row[0] >= 5L) {
                continue;
            }
            sums[3] += row[1];
            sums[4] += row[2];
            if (row[0] == minute) {
                sums[0] += row[1];
                sums[2] += row[2];
            } else if (row[0] == minute - 1L) {
                sums[1] += row[1];
            }
        }
        return sums;
    }

    private static String addBucket(String text, long minute, long realAdd, long autoAdd) {
        StringBuilder kept = new StringBuilder();
        boolean merged = false;
        if (text != null && !text.isEmpty()) {
            String[] parts = text.split(",");
            for (int i = 0; i < parts.length; i++) {
                long[] row = parseBucket(parts[i]);
                if (row == null || minute - row[0] >= 5L || row[0] > minute) {
                    continue;
                }
                if (row[0] == minute) {
                    row[1] += realAdd;
                    row[2] += autoAdd;
                    merged = true;
                }
                if (kept.length() > 0) {
                    kept.append(',');
                }
                kept.append(row[0]).append(':').append(row[1]).append(':').append(row[2]);
            }
        }
        if (!merged && (realAdd > 0L || autoAdd > 0L)) {
            if (kept.length() > 0) {
                kept.append(',');
            }
            kept.append(minute).append(':').append(realAdd).append(':').append(autoAdd);
        }
        return kept.toString();
    }

    private static long[] parseBucket(String part) {
        if (part == null || part.isEmpty()) {
            return null;
        }
        int colon = part.indexOf(':');
        int colon2 = colon < 0 ? -1 : part.indexOf(':', colon + 1);
        if (colon <= 0 || colon2 <= colon) {
            return null;
        }
        try {
            return new long[] {
                    Long.parseLong(part.substring(0, colon)),
                    Long.parseLong(part.substring(colon + 1, colon2)),
                    Long.parseLong(part.substring(colon2 + 1))
            };
        } catch (NumberFormatException error) {
            return null;
        }
    }

    /**
     * Missing, and a stored 300 left over from the old auto-only minute cap, become
     * 290 once. A later edit, including an explicit 300, is kept.
     */
    private static long readPerMinute(SharedPreferences prefs) {
        long perMinute = prefs.getLong(KEY_PER_MINUTE, StepMath.DEFAULT_PER_MINUTE);
        if (perMinute <= 0L) {
            perMinute = StepMath.DEFAULT_PER_MINUTE;
        }
        if (!prefs.getBoolean(KEY_MINUTE_MARGIN, false)) {
            if (!prefs.contains(KEY_PER_MINUTE) || perMinute == StepMath.STEPS_PER_MINUTE) {
                perMinute = StepMath.DEFAULT_PER_MINUTE;
            }
            prefs.edit()
                    .putLong(KEY_PER_MINUTE, perMinute)
                    .putBoolean(KEY_MINUTE_MARGIN, true)
                    .commit();
        }
        return perMinute;
    }

    /** Missing and the old built-in 18000 both become 48000 once. A later edit is kept. */
    private static long readStepCap(SharedPreferences prefs) {
        long stepCap = prefs.getLong(KEY_STEP_CAP, StepMath.DEFAULT_STEP_CAP);
        if (stepCap <= 0L) {
            stepCap = StepMath.DEFAULT_STEP_CAP;
        }
        if (!prefs.getBoolean(KEY_CAP_UPDATED, false)) {
            if (!prefs.contains(KEY_STEP_CAP) || stepCap == StepMath.PREVIOUS_STEP_CAP) {
                stepCap = StepMath.DEFAULT_STEP_CAP;
            }
            prefs.edit()
                    .putLong(KEY_STEP_CAP, stepCap)
                    .putBoolean(KEY_CAP_UPDATED, true)
                    .commit();
        }
        return stepCap;
    }

    /** Goal and credited shown to the UI and the hook. Neither is negative, and the goal is not below credited. */
    private static long[] forwardTotals(long goal, long credited) {
        if (credited < 0L) {
            credited = 0L;
        }
        return new long[] {StepMath.nonDecreasingGoal(credited, goal), credited};
    }

    /**
     * Switch on: {@code goal} is the absolute target.
     * Returns the goal written, or -1 when {@code goal} is negative.
     * A goal below steps already credited is raised to that total. Credited never decreases.
     */
    public static long putAbsolute(Context context, long goal, long perMinute, long perFive,
            long stepCap) {
        if (goal < 0L) {
            return -1L;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long credited = effectiveCredited(prefs);
        long storedGoal = StepMath.nonDecreasingGoal(credited, goal);
        prefs.edit()
                .putBoolean(KEY_ENABLED, true)
                .putLong(KEY_OFFSET, storedGoal)
                .putLong(KEY_CREDITED, credited)
                .putLong(KEY_BASE, 0L)
                .putLong(KEY_ADDITION, 0L)
                .putLong(KEY_PER_MINUTE, perMinute)
                .putLong(KEY_PER_FIVE, perFive)
                .putLong(KEY_STEP_CAP, stepCap)
                .putBoolean(KEY_MINUTE_MARGIN, true)
                .commit();
        return storedGoal;
    }

    /**
     * Switch off: keep every step already credited and release {@code addition}
     * more, from {@code base}, with the same per-minute rules.
     */
    public static void putAddition(Context context, long base, long addition, long goal,
            long perMinute, long perFive, long stepCap) {
        if (base < 0L) {
            base = 0L;
        }
        if (addition < 0L) {
            addition = 0L;
        }
        if (goal < 0L) {
            goal = 0L;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long credited = effectiveCredited(prefs);
        goal = StepMath.nonDecreasingGoal(credited, goal);
        prefs.edit()
                .putBoolean(KEY_ENABLED, false)
                .putLong(KEY_OFFSET, goal)
                .putLong(KEY_CREDITED, credited)
                .putLong(KEY_BASE, base)
                .putLong(KEY_ADDITION, addition)
                .putLong(KEY_PER_MINUTE, perMinute)
                .putLong(KEY_PER_FIVE, perFive)
                .putLong(KEY_STEP_CAP, stepCap)
                .putBoolean(KEY_MINUTE_MARGIN, true)
                .commit();
    }

    /** Writes the two release caps and leaves the offset switch untouched. */
    public static void putRates(Context context, long perMinute, long perFive) {
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        prefs.edit()
                .putLong(KEY_PER_MINUTE, perMinute)
                .putLong(KEY_PER_FIVE, perFive)
                .putBoolean(KEY_MINUTE_MARGIN, true)
                .commit();
    }

    /**
     * Raises the amount already fed into per-minute deltas. Never moves backward.
     * {@code epoch} must match the stored epoch. A credit from before a reset is ignored.
     * Returns credited, then epoch.
     */
    public static long[] creditAtLeast(Context context, long credited, long epoch) {
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long storedEpoch = prefs.getLong(KEY_CREDIT_EPOCH, 0L);
        long offset = prefs.getLong(KEY_OFFSET, 0L);
        long storedDay = prefs.getLong(KEY_DAY, Long.MIN_VALUE);
        long current = prefs.getLong(KEY_CREDITED, 0L);
        if (current < 0L) {
            current = 0L;
        }
        if (storedDay != StepMath.localDay(System.currentTimeMillis())) {
            return new long[] {0L, storedEpoch};
        }
        if (epoch != storedEpoch || offset < 0L || credited < 0L) {
            return new long[] {current, storedEpoch};
        }
        long next = Math.max(current, Math.min(offset, credited));
        prefs.edit().putLong(KEY_CREDITED, next).commit();
        return new long[] {next, storedEpoch};
    }
}
