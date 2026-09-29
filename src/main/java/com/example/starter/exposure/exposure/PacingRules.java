package com.example.starter.exposure.exposure;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.function.IntUnaryOperator;

/**
 * 活动匀速投放的纯规则集合（无 IO、无时钟依赖），便于单元测试。
 * 时间一律按 UTC，小时采用左闭右开区间 [h, h+1)。
 */
public final class PacingRules {

    /** 每个 UTC 自然日的小时数。 */
    public static final int HOURS_PER_DAY = 24;

    private PacingRules() {
    }

    /**
     * 校验 24 小时预算：长度恰为 24、每项非负，且合计等于日总额。
     *
     * @return 校验通过返回 null；否则返回可区分的原因描述（含实际值、要求值、差额）
     */
    public static String validateBudgets(int dailyTotalCap, int[] hourlyBudgets) {
        if (hourlyBudgets == null || hourlyBudgets.length != HOURS_PER_DAY) {
            return "hourly budgets must contain exactly 24 entries: actual="
                    + (hourlyBudgets == null ? 0 : hourlyBudgets.length)
                    + ", required=" + HOURS_PER_DAY;
        }
        int sum = 0;
        for (int h = 0; h < HOURS_PER_DAY; h++) {
            if (hourlyBudgets[h] < 0) {
                return "hourly budget must be non-negative: hour=" + h
                        + ", actual=" + hourlyBudgets[h] + ", required>=0";
            }
            sum += hourlyBudgets[h];
        }
        if (sum != dailyTotalCap) {
            return "hourly budgets sum must equal dailyTotalCap: actual=" + sum
                    + ", required=" + dailyTotalCap
                    + ", difference=" + (dailyTotalCap - sum);
        }
        return null;
    }

    /**
     * 指定 UTC 日的某小时在给定时刻是否已开始：小时起点 &lt;= nowUtc 即算开始。
     * 因此当前正在进行的小时也视为已开始，其预算不可下调。
     */
    public static boolean isStartedHour(LocalDate utcDate, int hour, long nowUtc) {
        return hourStartMillis(utcDate, hour) <= nowUtc;
    }

    /**
     * 从当前小时的下一小时起，查找当日首个预算为正且账面仍有剩余的小时。
     *
     * @param budgets       长度 24 的小时预算
     * @param usedSupplier  按小时返回当前已占用名额
     * @param currentHour   当前 UTC 小时（0～23）
     * @return 下一可用小时下标；当日没有返回 -1
     */
    public static int nextAvailableHour(int[] budgets, IntUnaryOperator usedSupplier, int currentHour) {
        for (int h = currentHour + 1; h < HOURS_PER_DAY; h++) {
            if (budgets[h] > 0 && usedSupplier.applyAsInt(h) < budgets[h]) {
                return h;
            }
        }
        return -1;
    }

    /** 指定 UTC 日第 hour 个自然小时的起点（hour 可为 24 表示次日零点），epoch 毫秒 UTC。 */
    public static long hourStartMillis(LocalDate utcDate, int hour) {
        return utcDate.atStartOfDay().plusHours(hour).toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
