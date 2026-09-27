package com.example.starter.water;

import com.example.starter.water.dto.Dtos.ReadingItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 配水传感异常判定纯函数：规则参数校验、批次序列校验、相邻读数评估与替代读数校验。
 * 不访问数据库、不依赖时钟，便于单元测试直接验证数量、时长、比例与差额的精确口径。
 *
 * <p>时间口径：UTC 纳秒时间戳；区间左闭右开，时长 = 后读数时刻 - 前读数时刻。
 * 数量（增量/差额）单位立方米保留 3 位小数；时长单位分钟保留 6 位小数；
 * 比例（瞬时流量）单位立方米/分钟保留 9 位小数；除法均为 HALF_UP 舍入。</p>
 */
final class MeterRules {

    /** 每纳秒换算分钟。 */
    private static final BigDecimal NANOS_PER_MINUTE = new BigDecimal(60_000_000_000L);
    private static final int VOLUME_SCALE = 3;
    private static final int DURATION_SCALE = 9;
    private static final int FLOW_SCALE = 9;

    private static final Pattern FLOW_RATE_PATTERN = Pattern.compile("\\d{1,16}(\\.\\d{1,6})?");
    private static final Pattern VOLUME_PATTERN = Pattern.compile("\\d{1,16}(\\.\\d{1,3})?");

    private static final String TYPE_REGRESSION = "REGRESSION";
    private static final String TYPE_FLOW_RATE = "FLOW_RATE";
    private static final String TYPE_INTERVAL_GAP = "INTERVAL_GAP";

    private MeterRules() {
    }

    /** 读数序列中的一个点（测量时刻 UTC 纳秒 + 累计读数立方米）。 */
    record Point(long utcNanos, BigDecimal cumulative) {
    }

    /** 异常判定规则快照（版本号 + 瞬时流量上限立方米/分钟 + 最大间隔分钟）。 */
    record Rule(int version, BigDecimal maxFlowRate, int maxIntervalMinutes) {
    }

    /**
     * 一对相邻读数的评估结果。
     *
     * @param type                  REGRESSION / FLOW_RATE / INTERVAL_GAP
     * @param volumeDelta           实际增量（立方米，倒退为负）
     * @param durationMinutes       实际时长（分钟）
     * @param actualFlowRate        实际瞬时流量（立方米/分钟，倒退为负）
     * @param regressionAmount      差额：倒退量，仅 REGRESSION 非 null
     * @param flowRateExcess        差额：流量超出量，仅 FLOW_RATE 非 null
     * @param intervalExcessMinutes 差额：间隔超出分钟，仅 INTERVAL_GAP 非 null
     */
    record Finding(String type, BigDecimal volumeDelta, BigDecimal durationMinutes, BigDecimal actualFlowRate,
                   BigDecimal regressionAmount, BigDecimal flowRateExcess, BigDecimal intervalExcessMinutes) {
    }

    /** 校验并解析规则参数，非法抛 422。 */
    static Rule validateRule(int version, String maxFlowRate, Integer maxIntervalMinutes) {
        if (maxFlowRate == null || maxFlowRate.isBlank()) {
            throw ApiException.unprocessable("INVALID_RULE", "maxFlowRate 不能为空");
        }
        String rateText = maxFlowRate.trim();
        if (!FLOW_RATE_PATTERN.matcher(rateText).matches()) {
            throw ApiException.unprocessable("INVALID_RULE",
                    "maxFlowRate 必须为非负十进制字符串，最多 6 位小数: " + maxFlowRate);
        }
        BigDecimal rate = new BigDecimal(rateText);
        if (rate.signum() <= 0) {
            throw ApiException.unprocessable("INVALID_RULE", "maxFlowRate 必须大于 0: " + maxFlowRate);
        }
        if (maxIntervalMinutes == null) {
            throw ApiException.unprocessable("INVALID_RULE", "maxIntervalMinutes 不能为空");
        }
        if (maxIntervalMinutes <= 0) {
            throw ApiException.unprocessable("INVALID_RULE",
                    "maxIntervalMinutes 必须为正整数（分钟）: " + maxIntervalMinutes);
        }
        return new Rule(version, rate, maxIntervalMinutes);
    }

    /**
     * 完整校验一批读数：非空、时刻合法且批次内严格递增（相等也算不递增）、累计值非负合法；
     * 任一错误抛 422，调用方保证不产生任何写入。
     */
    static List<Point> validateBatch(List<ReadingItem> readings) {
        if (readings == null || readings.isEmpty()) {
            throw ApiException.unprocessable("INVALID_READING", "读数批次不能为空");
        }
        List<Point> points = new ArrayList<>(readings.size());
        for (int i = 0; i < readings.size(); i++) {
            ReadingItem item = readings.get(i);
            if (item == null) {
                throw ApiException.unprocessable("INVALID_READING", "第 " + (i + 1) + " 条读数为空");
            }
            long utc = parseReadingUtc(i, item.readingUtc());
            BigDecimal cumulative = parseCumulative(i, item.cumulativeVolume());
            points.add(new Point(utc, cumulative));
        }
        // 完整序列校验：批次内时刻必须严格递增，任一错误整批拒绝
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i).utcNanos() <= points.get(i - 1).utcNanos()) {
                throw ApiException.unprocessable("READING_NOT_INCREMENTAL",
                        "读数时刻必须严格递增：第 " + i + " 条时刻不晚于前一条（UTC 左闭右开，相等亦非法）");
            }
        }
        return points;
    }

    /**
     * 评估一对相邻读数（prev 时刻严格早于 curr），按 REGRESSION、FLOW_RATE、INTERVAL_GAP
     * 固定顺序返回命中的异常（一对读数可能命中多个，如倒退且间隔超上限）。
     * 间隔是否超限按纳秒精确比较，避免分钟小数舍入把临界值误判；流量用高精度时长计算避免极小间隔除零。
     */
    static List<Finding> evaluate(Point prev, Point curr, Rule rule) {
        long gapNanos = curr.utcNanos() - prev.utcNanos();
        if (gapNanos <= 0) {
            throw new IllegalArgumentException("evaluate 要求相邻读数时刻严格递增");
        }
        BigDecimal volumeDelta = curr.cumulative().subtract(prev.cumulative())
                .setScale(VOLUME_SCALE, RoundingMode.HALF_UP);
        BigDecimal durationExact = BigDecimal.valueOf(gapNanos)
                .divide(NANOS_PER_MINUTE, 18, RoundingMode.HALF_UP);
        BigDecimal durationMinutes = durationExact.setScale(DURATION_SCALE, RoundingMode.HALF_UP);
        BigDecimal actualFlowRate = volumeDelta.divide(durationExact, FLOW_SCALE, RoundingMode.HALF_UP);

        List<Finding> findings = new ArrayList<>(3);
        if (volumeDelta.signum() < 0) {
            findings.add(new Finding(TYPE_REGRESSION, volumeDelta, durationMinutes, actualFlowRate,
                    volumeDelta.negate(), null, null));
        } else if (actualFlowRate.compareTo(rule.maxFlowRate()) > 0) {
            findings.add(new Finding(TYPE_FLOW_RATE, volumeDelta, durationMinutes, actualFlowRate,
                    null, actualFlowRate.subtract(rule.maxFlowRate()).setScale(FLOW_SCALE, RoundingMode.HALF_UP),
                    null));
        }
        BigDecimal limitNanos = BigDecimal.valueOf(rule.maxIntervalMinutes()).multiply(NANOS_PER_MINUTE);
        if (BigDecimal.valueOf(gapNanos).compareTo(limitNanos) > 0) {
            BigDecimal intervalExcess = durationExact
                    .subtract(BigDecimal.valueOf(rule.maxIntervalMinutes()))
                    .setScale(DURATION_SCALE, RoundingMode.HALF_UP);
            findings.add(new Finding(TYPE_INTERVAL_GAP, volumeDelta, durationMinutes, actualFlowRate,
                    null, null, intervalExcess));
        }
        return findings;
    }

    /**
     * 校验替代读数：时刻严格夹在前序读数与异常读数之间（左闭右开口径下两刻均不含），
     * 累计值非负，且“前序读数 → 替代读数”在给定规则下不产生任何异常；否则抛 422。
     */
    static Point validateReplacement(Point previous, Point anomalous, String replacementUtc,
                                     String replacementCumulative, Rule rule) {
        long utc = parseReadingUtc(-1, replacementUtc);
        BigDecimal cumulative = parseCumulative(-1, replacementCumulative);
        if (utc <= previous.utcNanos() || utc >= anomalous.utcNanos()) {
            throw ApiException.unprocessable("INVALID_REPLACEMENT",
                    "替代读数时刻必须严格介于前序读数时刻与异常读数时刻之间");
        }
        Point replacement = new Point(utc, cumulative);
        List<Finding> findings = evaluate(previous, replacement, rule);
        if (!findings.isEmpty()) {
            throw ApiException.unprocessable("INVALID_REPLACEMENT",
                    "替代读数未消除异常：相对前序读数仍存在 " + findings.get(0).type());
        }
        return replacement;
    }

    private static long parseReadingUtc(int index, String value) {
        String where = index >= 0 ? "第 " + (index + 1) + " 条读数的 " : "替代读数的 ";
        String code = index >= 0 ? "INVALID_READING" : "INVALID_REPLACEMENT";
        if (value == null || value.isBlank()) {
            throw ApiException.unprocessable(code, where + "readingUtc 不能为空");
        }
        try {
            return WaterService.toNanos(java.time.Instant.parse(value.trim()));
        } catch (DateTimeParseException | ArithmeticException e) {
            throw ApiException.unprocessable(code, where + "readingUtc 不是合法的 UTC 时刻: " + value);
        }
    }

    private static BigDecimal parseCumulative(int index, String value) {
        String where = index >= 0 ? "第 " + (index + 1) + " 条读数的 " : "替代读数的 ";
        String code = index >= 0 ? "INVALID_READING" : "INVALID_REPLACEMENT";
        if (value == null || value.isBlank()) {
            throw ApiException.unprocessable(code, where + "cumulativeVolume 不能为空");
        }
        String trimmed = value.trim();
        if (!VOLUME_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.unprocessable(code,
                    where + "cumulativeVolume 必须为非负十进制字符串，最多 3 位小数: " + value);
        }
        return new BigDecimal(trimmed).setScale(VOLUME_SCALE, RoundingMode.HALF_UP);
    }
}
