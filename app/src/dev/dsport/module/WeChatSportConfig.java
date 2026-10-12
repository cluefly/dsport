package dev.dsport.module;

/**
 * Reads the sport config WeChat stores after {@code getwxsportconfig}.
 * The MMKV blob is not a JSON file; the latest config object is embedded in it.
 */
public final class WeChatSportConfig {
    private WeChatSportConfig() {}

    public static String extractConfigJson(String blob) {
        if (blob == null) {
            return null;
        }
        String marker = "{\"stepCounterRateUs\"";
        int start = blob.lastIndexOf(marker);
        if (start < 0) {
            return null;
        }
        int depth = 0;
        for (int i = start; i < blob.length(); i++) {
            char c = blob.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return blob.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    public static long field(String json, String name, long fallback) {
        if (json == null || name == null) {
            return fallback;
        }
        String needle = "\"" + name + "\":";
        int at = json.indexOf(needle);
        if (at < 0) {
            return fallback;
        }
        int i = at + needle.length();
        int j = i;
        if (j < json.length() && json.charAt(j) == '-') {
            j++;
        }
        int digits = j;
        while (j < json.length() && json.charAt(j) >= '0' && json.charAt(j) <= '9') {
            j++;
        }
        if (j == digits) {
            return fallback;
        }
        try {
            return Long.parseLong(json.substring(i, j));
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    /** Per-minute cap is one fifth of the server five-minute cap. The server has no separate minute field. */
    public static final class Rates {
        public final long perMinute;
        public final long perFive;

        public Rates(long perMinute, long perFive) {
            this.perMinute = perMinute;
            this.perFive = perFive;
        }
    }

    public static Rates ratesFrom(String json) {
        long perFive = field(json, "stepCounterMaxStep5m", -1L);
        if (perFive < 2L) {
            return null;
        }
        long perMinute = perFive / 5L;
        if (perMinute < 1L) {
            perMinute = 1L;
        }
        return new Rates(perMinute, perFive);
    }

    /**
     * Manual ceilings cannot rise above the figures shown on the WeChat tab.
     * Returns the prompt when {@code perMinute} or {@code perFive} is greater, otherwise null.
     */
    public static String overLimit(long perMinute, long perFive, Rates ceiling) {
        if (ceiling == null || (perMinute <= ceiling.perMinute && perFive <= ceiling.perFive)) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        if (perMinute > ceiling.perMinute) {
            text.append("每分钟合计不能大于微信的 ").append(ceiling.perMinute);
        }
        if (perFive > ceiling.perFive) {
            if (text.length() > 0) {
                text.append("，");
            }
            text.append("每 5 分钟合计不能大于微信的 ").append(ceiling.perFive);
        }
        text.append("，已放弃这次输入");
        return text.toString();
    }

    /** Value kept after an input above {@code ceiling} is discarded. */
    public static long keptRate(long stored, long ceiling) {
        if (stored >= 1L && stored <= ceiling) {
            return stored;
        }
        return ceiling;
    }

    public static final class Line {
        public final String label;
        public final String value;
        public final boolean heading;
        /** 1 when the switch is on, 2 when it is off. */
        public final int tone;

        private Line(String label, String value, boolean heading, int tone) {
            this.label = label;
            this.value = value;
            this.heading = heading;
            this.tone = tone;
        }

        static Line heading(String label) {
            return new Line(label, "", true, 0);
        }

        static Line row(String label, String value) {
            return new Line(label, value, false, 0);
        }

        static Line row(String label, String value, int tone) {
            return new Line(label, value, false, tone);
        }
    }

    public static Line[] lines(String json) {
        if (json == null) {
            return new Line[0];
        }
        long rateUs = field(json, "stepCounterRateUs", -1L);
        long saveMs = field(json, "stepCounterSaveInterval", -1L);
        long saveStep = field(json, "stepCounterSaveStep", -1L);
        long max5 = field(json, "stepCounterMaxStep5m", -1L);
        long device = field(json, "deviceStepSwitch", -1L);
        long ext = field(json, "extStepApiSwitch", -1L);
        long extInterval = field(json, "interval", -1L);
        long uploadSec = field(json, "backgroundTimeInterval", -1L);
        long uploadStep = field(json, "backgroundStepCountInterval", -1L);
        long check = field(json, "checkWeSportInstall", -1L);
        Rates rates = ratesFrom(json);
        return new Line[] {
                Line.heading("采样"),
                Line.row("采样间隔", micros(rateUs)),
                Line.row("5 分钟上限", max5 < 0 ? "—" : max5 + " 步"),
                Line.row("折成每分钟", rates == null ? "—" : rates.perMinute + " 步"),
                Line.heading("保存"),
                Line.row("保存间隔", saveMs < 0 ? "—" : (saveMs / 1000) + " 秒"),
                Line.row("保存步数", saveStep < 0 ? "—" : saveStep + " 步"),
                Line.heading("上传"),
                Line.row("后台间隔", uploadSec < 0 ? "—" : uploadSec + " 秒"),
                Line.row("后台步数", uploadStep < 0 ? "—" : uploadStep + " 步"),
                Line.row("外部推送间隔", extInterval < 0 ? "—" : extInterval + " 秒"),
                Line.heading("开关"),
                Line.row("本机计步", onOff(device), toneOf(device)),
                Line.row("外部推送", onOff(ext), toneOf(ext)),
                Line.row("运动插件", onOff(check), toneOf(check))
        };
    }

    /** The coverage sentence shown under the rows. The full dump stays in describe(). */
    public static String applyNote(String json) {
        Rates rates = ratesFrom(json);
        if (rates == null) {
            return "没有可用的 5 分钟上限，不能覆盖通用页。";
        }
        long minute = StepMath.safeMinute(rates.perMinute);
        return "覆盖通用页时：每分钟合计 "
                + minute
                + " 步（服务端每分钟 "
                + rates.perMinute
                + " 减 "
                + StepMath.MINUTE_MARGIN
                + "），每 5 分钟合计 "
                + rates.perFive
                + " 步。真实步数和自动累加一起计算。合计停在 "
                + StepMath.acceptableFive(rates.perFive)
                + "，到达 "
                + rates.perFive
                + " 时微信会丢掉这一截。";
    }

    public static String describe(String json) {
        if (json == null) {
            return "没有找到服务端配置。";
        }
        long rateUs = field(json, "stepCounterRateUs", -1L);
        long saveMs = field(json, "stepCounterSaveInterval", -1L);
        long saveStep = field(json, "stepCounterSaveStep", -1L);
        long max5 = field(json, "stepCounterMaxStep5m", -1L);
        long device = field(json, "deviceStepSwitch", -1L);
        long ext = field(json, "extStepApiSwitch", -1L);
        long extInterval = field(json, "interval", -1L);
        long uploadSec = field(json, "backgroundTimeInterval", -1L);
        long uploadStep = field(json, "backgroundStepCountInterval", -1L);
        long check = field(json, "checkWeSportInstall", -1L);
        StringBuilder text = new StringBuilder();
        text.append("采样间隔 ").append(micros(rateUs)).append('\n');
        text.append("落盘 ").append(saveMs < 0 ? "—" : (saveMs / 1000) + " 秒")
                .append("，或增加 ").append(saveStep < 0 ? "—" : Long.toString(saveStep)).append(" 步\n");
        text.append("5 分钟上限 ").append(max5 < 0 ? "—" : max5 + " 步").append('\n');
        text.append("本机计步 ").append(onOff(device)).append('\n');
        text.append("外部推送 ").append(onOff(ext));
        if (extInterval >= 0) {
            text.append("，间隔 ").append(extInterval).append(" 秒");
        }
        text.append('\n');
        text.append("后台上传 ").append(uploadSec < 0 ? "—" : uploadSec + " 秒")
                .append("，或 ").append(uploadStep < 0 ? "—" : uploadStep + " 步").append('\n');
        text.append("检查运动插件 ").append(onOff(check)).append('\n');
        text.append('\n');
        Rates rates = ratesFrom(json);
        if (rates == null) {
            text.append("没有可用的 5 分钟上限，不能覆盖通用页。");
        } else {
            text.append("覆盖通用页时：每分钟合计 ")
                    .append(StepMath.safeMinute(rates.perMinute))
                    .append(" 步（服务端每分钟 ")
                    .append(rates.perMinute)
                    .append(" 减 ")
                    .append(StepMath.MINUTE_MARGIN)
                    .append("），每 5 分钟合计 ")
                    .append(rates.perFive)
                    .append(" 步。真实步数和自动累加一起计算。合计停在 ")
                    .append(StepMath.acceptableFive(rates.perFive))
                    .append("，到达 ")
                    .append(rates.perFive)
                    .append(" 时微信会丢掉这一截。");
        }
        return text.toString();
    }

    private static String micros(long us) {
        if (us < 0) {
            return "—";
        }
        if (us % 1_000_000L == 0) {
            return (us / 1_000_000L) + " 秒";
        }
        if (us % 1000L == 0) {
            return (us / 1000L) + " 毫秒";
        }
        return us + " 微秒";
    }

    private static String onOff(long value) {
        if (value < 0) {
            return "—";
        }
        return value == 0L ? "关" : "开";
    }

    /** 0 unknown, 1 on, 2 off. */
    private static int toneOf(long value) {
        if (value < 0L) {
            return 0;
        }
        return value == 0L ? 2 : 1;
    }
}
