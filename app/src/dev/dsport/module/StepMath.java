package dev.dsport.module;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Step-count adjustment shared by the UI preview and the Vector hook.
 * Only step_counter is rewritten, and the value sent out is today's count.
 * Step detector events stay untouched.
 */
public final class StepMath {
    public static final int TYPE_STEP_COUNTER = 19;
    public static final int TYPE_STEP_DETECTOR = 18;
    public static final int TYPE_PEDOMETER_MINUTE = 33171034;
    public static final String STRING_PEDOMETER_MINUTE = "qti.sensor.pedometer_minute";

    public static final String KEY_COUNTER = "step_counter";
    public static final String KEY_DETECTOR = "step_detector";
    public static final String KEY_MINUTE = "pedometer_minute";
    /** WeChat's step_counter budget: 300 steps in one minute, 1500 in five minutes. */
    public static final long STEPS_PER_MINUTE = 300L;
    public static final long STEPS_PER_FIVE_MINUTES = 1500L;
    /**
     * Combined real + auto steps stay this far under WeChat's per-minute figure.
     * WeChat has no separate minute field; its minute figure is the five-minute cap divided by 5.
     */
    public static final long MINUTE_MARGIN = 10L;
    /** Stored per-minute ceiling. 300 from the server becomes 290. */
    public static final long DEFAULT_PER_MINUTE = STEPS_PER_MINUTE - MINUTE_MARGIN;
    /** Daily sent step_counter at which DSport stops adding steps. The value is editable. */
    public static final long DEFAULT_STEP_CAP = 48_000L;
    /** Previous default, migrated once so existing installs pick up 48000. */
    public static final long PREVIOUS_STEP_CAP = 18_000L;
    /**
     * WeChat drops a slice that reaches the five-minute budget ({@code >=}).
     * The releaser stops one step earlier so that slice is still accepted.
     */
    public static final long ACCEPTABLE_FIVE_MINUTES = STEPS_PER_FIVE_MINUTES - 1L;

    private StepMath() {}

    public static boolean isMinutePedometer(int type, String stringType) {
        return type == TYPE_PEDOMETER_MINUTE || STRING_PEDOMETER_MINUTE.equals(stringType);
    }

    public static boolean rewritesCumulative(int type, String stringType) {
        return type == TYPE_STEP_COUNTER;
    }

    /**
     * Only the system step process moves the credited total forward.
     * Other hooked processes, including WeChat, add that same total and do not
     * release another minute of their own.
     */
    public static boolean releasesCredit(String packageName) {
        return "com.oplus.exsystemservice".equals(packageName)
                || "com.oplus.healthservice".equals(packageName);
    }

    public static String keyFor(int type, String stringType) {
        if (type == TYPE_STEP_COUNTER) {
            return KEY_COUNTER;
        }
        if (type == TYPE_STEP_DETECTOR) {
            return KEY_DETECTOR;
        }
        if (isMinutePedometer(type, stringType)) {
            return KEY_MINUTE;
        }
        return null;
    }

    /** Local calendar day. A new day starts at 00:00 in {@code zone}. */
    public static long localDay(long epochMillis, ZoneId zone) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), zone).toEpochDay();
    }

    public static long localDay(long epochMillis) {
        return localDay(epochMillis, ZoneId.systemDefault());
    }

    /**
     * The hardware counter keeps climbing until the phone reboots.
     * {@code base} is that counter at the start of the local day, so today's real
     * steps are {@code raw - base}. A reboot is the only time the counter falls.
     * After a reboot, {@code base} is 0 and today's real steps are the new counter.
     */
    public static final class DayRoll {
        public final long day;
        public final long base;
        public final long credited;
        public final boolean reset;

        public DayRoll(long day, long base, long credited, boolean reset) {
            this.day = day;
            this.base = base;
            this.credited = credited;
            this.reset = reset;
        }
    }

    /**
     * The counter rebooted when it fell far enough that this is not sensor noise.
     * {@code previous} is the last raw sample, or {@code Long.MIN_VALUE} when unknown.
     */
    public static boolean counterRebooted(long previous, long rawSteps) {
        if (previous < 0L || rawSteps >= previous) {
            return false;
        }
        long drop = previous - rawSteps;
        return drop >= 1000L || drop * 2L >= previous;
    }

    /**
     * @param lastRaw last hardware sample, {@code Long.MIN_VALUE} if this process has never seen one
     */
    public static DayRoll rollDay(long storedDay, long storedBase, long credited, long lastRaw,
            long rawSteps, long today) {
        if (rawSteps < 0L) {
            rawSteps = 0L;
        }
        if (credited < 0L) {
            credited = 0L;
        }
        if (storedBase < 0L) {
            storedBase = 0L;
        }
        boolean rebooted = counterRebooted(lastRaw, rawSteps)
                || (storedDay == today && counterRebooted(storedBase, rawSteps));
        if (storedDay != today) {
            if (rebooted || lastRaw < 0L) {
                // Reboot, or no sample from before today. A fresh counter starts at 0.
                // Without history, do not treat the whole since-boot total as today.
                long base = rebooted ? 0L : rawSteps;
                return new DayRoll(today, base, 0L, true);
            }
            // The counter did not reboot at midnight. Keep yesterday's last sample
            // as today's zero, so steps taken after that still count.
            return new DayRoll(today, lastRaw, 0L, true);
        }
        if (rebooted) {
            return new DayRoll(today, 0L, credited, false);
        }
        return new DayRoll(today, storedBase, credited, false);
    }

    /** Hardware steps since today's baseline. Never negative. */
    public static long hardwareToday(long rawSteps, long base) {
        if (rawSteps < 0L) {
            rawSteps = 0L;
        }
        if (base < 0L) {
            base = 0L;
        }
        if (rawSteps < base) {
            return 0L;
        }
        return rawSteps - base;
    }

    /**
     * Auto-added steps allowed today. The cap does not include real steps.
     * A non-positive cap means no cap.
     */
    public static long cappedCredit(long credited, long cap) {
        if (credited <= 0L) {
            return 0L;
        }
        if (cap <= 0L) {
            return credited;
        }
        return Math.min(credited, cap);
    }

    /** Today's real steps plus auto-added steps. Only the auto-added part is capped. */
    public static long dailySent(long hardwareToday, long credited, long cap) {
        if (hardwareToday < 0L) {
            hardwareToday = 0L;
        }
        return hardwareToday + cappedCredit(credited, cap);
    }

    /** WeChat drops a slice that reaches the five-minute cap, so release stops one step earlier. */
    public static long acceptableFive(long perFive) {
        return perFive <= 0L ? 0L : perFive - 1L;
    }

    /**
     * After the last real-step increase, auto-add stays off for this long.
     * A short gap between step batches is still a walk. One quiet minute lets WeChat
     * sample the real steps alone before auto-add starts again.
     */
    public static final long REAL_QUIET_MS = 60_000L;

    /**
     * Auto-add pauses while real steps are changing, and for {@link #REAL_QUIET_MS} after
     * the last increase. Unknown or a clock step backward does not pause.
     */
    public static boolean autoPaused(long lastRealChangeMillis, long nowMillis) {
        if (lastRealChangeMillis < 0L || nowMillis < lastRealChangeMillis) {
            return false;
        }
        return nowMillis - lastRealChangeMillis < REAL_QUIET_MS;
    }

    /** Per-minute ceiling written from a WeChat minute figure: that figure minus {@link #MINUTE_MARGIN}. */
    public static long safeMinute(long wechatMinute) {
        if (wechatMinute <= MINUTE_MARGIN) {
            return 1L;
        }
        return wechatMinute - MINUTE_MARGIN;
    }

    /**
     * Real steps reserved in the current minute before any auto-add.
     * The reserve is the larger of this minute and the previous minute.
     * A walk that starts with a quiet previous minute reserves the whole minute,
     * so the first dense minute does not also take a full auto-add.
     */
    public static long expectedReal(long realThis, long realPrev, long perMinute) {
        long thisMinute = Math.max(0L, realThis);
        long previous = Math.max(0L, realPrev);
        long expected = Math.max(thisMinute, previous);
        if (thisMinute > 0L && previous == 0L && perMinute > 0L) {
            expected = Math.max(expected, perMinute);
        }
        return expected;
    }

    /**
     * Auto steps that still fit this minute and this five-minute window.
     * {@code realMinute}, {@code autoMinute}, {@code realFive} and {@code autoFive}
     * are steps already counted in those windows. The five-minute cap is the
     * server value; release stops one step before it.
     */
    public static long combinedRelease(long credited, long goal, long realMinute, long autoMinute,
            long realFive, long autoFive, long perMinute, long perFive, long capRoom) {
        if (goal <= 0L || credited < 0L || credited >= goal || perMinute <= 0L || capRoom <= 0L) {
            return 0L;
        }
        long remain = goal - credited;
        long minuteRoom = perMinute - Math.max(0L, realMinute) - Math.max(0L, autoMinute);
        long fiveRoom = acceptableFive(perFive) - Math.max(0L, realFive) - Math.max(0L, autoFive);
        if (remain <= 0L || minuteRoom <= 0L || fiveRoom <= 0L) {
            return 0L;
        }
        long release = Math.min(remain, Math.min(minuteRoom, fiveRoom));
        return Math.min(release, capRoom);
    }

    /**
     * Steps to add to the step_counter total this minute.
     * {@code releasedInFiveMinutes} is what this process already added in the current
     * five wall-clock minutes. The result never exceeds one minute or the five-minute budget.
     */
    /** Absolute goal after the enable switch is turned off and a new amount is entered. */
    public static final class AdditionGoal {
        public final long base;
        public final long goal;

        public AdditionGoal(long base, long goal) {
            this.base = base;
            this.goal = goal;
        }
    }

    /**
     * A new round starts when the switch was on, or the previous goal is already reached.
     * The goal never moves below steps already credited.
     */
    public static AdditionGoal additionGoal(long credited, long storedBase, long storedGoal,
            boolean wasEnabled, long typedAddition) {
        long addition = Math.max(0L, typedAddition);
        boolean newRound = wasEnabled || storedGoal <= credited;
        long base = newRound ? Math.max(0L, credited) : Math.max(0L, storedBase);
        long goal = base + addition;
        if (goal < credited) {
            goal = credited;
        }
        return new AdditionGoal(base, goal);
    }

    public static long nextRelease(long credited, long offset, long releasedInFiveMinutes) {
        return nextRelease(credited, offset, releasedInFiveMinutes,
                STEPS_PER_MINUTE, STEPS_PER_FIVE_MINUTES);
    }

    /**
     * Stored target after a save. A negative request is refused by callers;
     * a request below steps already credited stays at the credited total.
     */
    public static long nonDecreasingGoal(long credited, long requested) {
        long done = Math.max(0L, credited);
        if (requested < 0L) {
            return done;
        }
        return Math.max(requested, done);
    }

    public static long nextRelease(long credited, long offset, long releasedInFiveMinutes,
            long perMinute, long perFive) {
        if (offset <= 0L || credited < 0L || credited >= offset || perMinute <= 0L) {
            return 0L;
        }
        long remain = offset - credited;
        long windowRoom = acceptableFive(perFive) - Math.max(0L, releasedInFiveMinutes);
        if (remain <= 0L || windowRoom <= 0L) {
            return 0L;
        }
        return Math.min(perMinute, Math.min(remain, windowRoom));
    }

    /** Auto-added steps that may be applied. Real steps are not part of {@code cap}. */
    public static long allowedBoost(long credited, long cap) {
        return cappedCredit(credited, cap);
    }

    /** How many more auto-added steps can still be released under the daily cap. */
    public static long releaseRoom(long credited, long cap) {
        if (cap <= 0L) {
            return Long.MAX_VALUE;
        }
        long done = Math.max(0L, credited);
        long room = cap - done;
        return room > 0L ? room : 0L;
    }

    public static float applyOffset(float raw, long offset, boolean enabled) {
        if (!enabled || offset <= 0L) {
            return raw;
        }
        double value = (double) raw + (double) offset;
        if (value < 0d) {
            value = 0d;
        }
        if (value > Float.MAX_VALUE) {
            value = Float.MAX_VALUE;
        }
        return (float) value;
    }

    public static String formatCount(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return "—";
        }
        int nearest = Math.round(value);
        if (Math.abs(value - nearest) < 0.05f) {
            return Integer.toString(nearest);
        }
        return Float.toString(value);
    }
}
