package dev.dsport.module;

import java.time.LocalDateTime;
import java.time.ZoneId;

public final class StepMathTest {
    public static void main(String[] args) {
        check(StepMath.applyOffset(12967f, 100, false) == 12967f, "disabled");
        check(StepMath.applyOffset(12967f, 0, true) == 12967f, "zero");
        check(StepMath.applyOffset(12967f, 100, true) == 13067f, "add");
        check(StepMath.applyOffset(10f, -50, true) == 10f, "refuse negative");
        check(StepMath.nonDecreasingGoal(3000, 1000) == 3000, "keep credited");
        check(StepMath.nonDecreasingGoal(3000, 8000) == 8000, "raise goal");
        check(StepMath.nonDecreasingGoal(3000, -5) == 3000, "refuse negative goal");
        check(StepMath.nextRelease(100, -50, 0) == 0, "no negative release");
        check(StepMath.allowedBoost(-20, 18000) == 0, "no negative boost");
        check(StepMath.applyOffset(13049f, 500, true) == 13549f, "minute");
        check(StepMath.isMinutePedometer(33171034, "other"), "type");
        check(StepMath.isMinutePedometer(1, "qti.sensor.pedometer_minute"), "name");
        check(!StepMath.rewritesCumulative(18, "android.sensor.step_detector"), "detector");
        check(StepMath.rewritesCumulative(19, "android.sensor.step_counter"), "counter");
        check(!StepMath.rewritesCumulative(33171034, "qti.sensor.pedometer_minute"), "minute raw");
        check(StepMath.releasesCredit("com.oplus.exsystemservice"), "release system");
        check(StepMath.releasesCredit("com.oplus.healthservice"), "release health");
        check(!StepMath.releasesCredit("com.tencent.mm"), "wechat apply only");
        check(StepMath.nextRelease(0, 3800, 0) == 300, "drip");
        check(StepMath.nextRelease(0, 3800, 1200) == 299, "window");
        check(StepMath.nextRelease(0, 3800, 1499) == 0, "full");
        check(StepMath.nextRelease(3600, 3800, 0) == 200, "tail");
        check(StepMath.nextRelease(3800, 3800, 0) == 0, "done");
        StepMath.AdditionGoal added = StepMath.additionGoal(5300, 0, 6000, true, 2000);
        check(added.base == 5300 && added.goal == 7300, "new round");
        added = StepMath.additionGoal(6000, 5300, 7300, false, 2000);
        check(added.base == 5300 && added.goal == 7300, "same round");
        added = StepMath.additionGoal(7300, 5300, 7300, false, 2000);
        check(added.base == 7300 && added.goal == 9300, "next round");
        added = StepMath.additionGoal(6000, 5300, 7300, false, 100);
        check(added.base == 5300 && added.goal == 6000, "no drop");
        check(StepMath.allowedBoost(6000, 48000) == 6000, "cap room");
        check(StepMath.allowedBoost(50000, 48000) == 48000, "cap tail");
        check(StepMath.allowedBoost(48000, 48000) == 48000, "cap full");
        check(StepMath.releaseRoom(0, 48000) == 48000, "release room");
        check(StepMath.releaseRoom(48000, 48000) == 0, "release none");
        check(StepMath.dailySent(17000, 2000, 48000) == 19000, "real plus added");
        check(StepMath.dailySent(20000, 60000, 48000) == 68000, "cap ignores real");
        check(StepMath.hardwareToday(50100, 50000) == 100, "today hardware");
        check(StepMath.hardwareToday(10, 50000) == 0, "hardware behind base");
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        long beforeMidnight = LocalDateTime.of(2026, 10, 9, 23, 30)
                .atZone(shanghai).toInstant().toEpochMilli();
        long midnight = LocalDateTime.of(2026, 10, 10, 0, 0)
                .atZone(shanghai).toInstant().toEpochMilli();
        long yesterday = StepMath.localDay(beforeMidnight, shanghai);
        long today = StepMath.localDay(midnight, shanghai);
        check(today == yesterday + 1, "midnight");
        StepMath.DayRoll first = StepMath.rollDay(Long.MIN_VALUE, 0, 0, Long.MIN_VALUE, 50000, yesterday);
        check(first.reset && first.base == 50000 && first.credited == 0, "first day");
        StepMath.DayRoll same = StepMath.rollDay(yesterday, 50000, 300, 50100, 50200, yesterday);
        check(!same.reset && same.base == 50000 && same.credited == 300, "same day");
        StepMath.DayRoll next = StepMath.rollDay(yesterday, 50000, 8000, 51900, 52000, today);
        check(next.reset && next.base == 51900 && next.credited == 0, "midnight keeps counter");
        check(StepMath.hardwareToday(52000, next.base) == 100, "steps after midnight");
        check(StepMath.dailySent(100, next.credited, 48000) == 100, "sent is today's real steps");
        StepMath.DayRoll reboot = StepMath.rollDay(yesterday, 50000, 400, 50100, 10, yesterday);
        check(!reboot.reset && reboot.base == 0 && reboot.credited == 400, "reboot keeps credit");
        check(StepMath.hardwareToday(10, reboot.base) == 10, "real steps since reboot");
        check(!StepMath.counterRebooted(50100, 50090), "ignore dip");
        check(StepMath.counterRebooted(50100, 10), "reboot drop");
        check(StepMath.DEFAULT_STEP_CAP == 48000, "default cap");
        check(StepMath.nextRelease(0, 3800, 0, 100, 400) == 100, "custom minute");
        check(StepMath.nextRelease(0, 3800, 300, 100, 400) == 99, "custom window");
        check(StepMath.safeMinute(300) == 290, "safe minute");
        check(StepMath.safeMinute(10) == 1, "safe floor");
        check(StepMath.DEFAULT_PER_MINUTE == 290, "default minute");
        check(StepMath.expectedReal(0, 0, 290) == 0, "idle reserve");
        check(StepMath.expectedReal(80, 100, 290) == 100, "steady reserve");
        check(StepMath.expectedReal(80, 0, 290) == 290, "first walk reserves minute");
        check(!StepMath.autoPaused(-1L, 1_000_000L), "no walk yet");
        check(StepMath.autoPaused(1_000_000L, 1_000_000L), "just stepped");
        check(StepMath.autoPaused(1_000_000L, 1_059_999L), "still quieting");
        check(!StepMath.autoPaused(1_000_000L, 1_060_000L), "quiet minute elapsed");
        check(!StepMath.autoPaused(1_060_000L, 1_000_000L), "clock backward");
        check(StepMath.REAL_QUIET_MS == 60_000L, "quiet minute");
        check(StepMath.combinedRelease(0, 3800, 0, 0, 0, 0, 290, 1500, 48000) == 290, "idle combined");
        check(StepMath.combinedRelease(0, 3800, 100, 0, 100, 0, 290, 1500, 48000) == 190, "real minute");
        check(StepMath.combinedRelease(0, 3800, 0, 0, 1400, 0, 290, 1500, 48000) == 99, "real five");
        check(StepMath.combinedRelease(0, 3800, 0, 0, 1499, 0, 290, 1500, 48000) == 0, "five full");
        check(StepMath.combinedRelease(0, 3800, 290, 0, 290, 0, 290, 1500, 48000) == 0, "minute full");
        check(StepMath.combinedRelease(0, 3800, 0, 0, 0, 0, 290, 1500, 50) == 50, "cap room");
        check(StepMath.combinedRelease(0, 3800, 50, 20, 200, 100, 290, 1500, 48000) == 220, "both windows");
        check(WeChatSportConfig.ratesFrom(null) == null, "no rates");
        String blob = "old {\"stepCounterRateUs\":1} KEY {\"stepCounterRateUs\":600000,"
                + "\"stepCounterSaveInterval\":60000,\"stepCounterSaveStep\":50,"
                + "\"stepCounterMaxStep5m\":1500,\"deviceStepSwitch\":1,\"extStepApiSwitch\":1,"
                + "\"stepExtConfig\":{\"version\":1,\"interval\":7200},"
                + "\"stepCountUploadConfig\":{\"backgroundTimeInterval\":60,"
                + "\"backgroundStepCountInterval\":500},\"checkWeSportInstall\":1}";
        String json = WeChatSportConfig.extractConfigJson(blob);
        check(json != null && json.startsWith("{\"stepCounterRateUs\":600000"), "json");
        WeChatSportConfig.Rates rates = WeChatSportConfig.ratesFrom(json);
        check(rates != null && rates.perMinute == 300 && rates.perFive == 1500, "rates");
        check(WeChatSportConfig.overLimit(290, 1500, rates) == null, "under ceiling");
        check(WeChatSportConfig.overLimit(300, 1500, rates) == null, "at ceiling");
        String minuteOver = WeChatSportConfig.overLimit(301, 1500, rates);
        check(minuteOver != null && minuteOver.contains("300") && minuteOver.contains("已放弃"), "minute over");
        String fiveOver = WeChatSportConfig.overLimit(290, 1501, rates);
        check(fiveOver != null && fiveOver.contains("1500") && !fiveOver.contains("每分钟合计不能大于"), "five over");
        String bothOver = WeChatSportConfig.overLimit(301, 1600, rates);
        check(bothOver != null && bothOver.contains("300") && bothOver.contains("1500"), "both over");
        check(WeChatSportConfig.keptRate(290, 300) == 290, "keep stored");
        check(WeChatSportConfig.keptRate(400, 300) == 300, "drop stored above");
        check(WeChatSportConfig.field(json, "stepCounterMaxStep5m", -1) == 1500, "max");
        check(WeChatSportConfig.field(json, "interval", -1) == 7200, "interval");
        check(WeChatSportConfig.describe(json).contains("每分钟合计 290"), "describe");
        check(WeChatSportConfig.describe(json).contains("服务端每分钟 300"), "describe server");
        WeChatSportConfig.Line[] lines = WeChatSportConfig.lines(json);
        boolean fiveRow = false;
        boolean deviceOn = false;
        for (WeChatSportConfig.Line line : lines) {
            if ("5 分钟上限".equals(line.label) && "1500 步".equals(line.value)) {
                fiveRow = true;
            }
            if ("本机计步".equals(line.label) && "开".equals(line.value) && line.tone == 1) {
                deviceOn = true;
            }
        }
        check(fiveRow && deviceOn, "rows");
        check(WeChatSportConfig.applyNote(json).contains("每分钟合计 290"), "note");
        check(WeChatSportConfig.applyNote(json).contains("1500"), "note five");
        check("13049".equals(StepMath.formatCount(13049f)), "format");
        check(StepMath.KEY_COUNTER.equals(StepMath.keyFor(19, "android.sensor.step_counter")), "key");
        System.out.println("StepMathTest ok");
    }

    private static void check(boolean condition, String name) {
        if (!condition) {
            throw new AssertionError(name);
        }
    }
}
