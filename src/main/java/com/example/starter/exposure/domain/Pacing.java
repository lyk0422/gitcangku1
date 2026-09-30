package com.example.starter.exposure.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 活动匀速投放的时间与预算计算工具。全部按 UTC 处理，小时区间左闭右开 [h:00, h+1:00)。
 */
public final class Pacing {

    private Pacing() {
    }

    /**
     * 校验 24 个小时预算：均为非负整数，且合计恰好等于日总额。
     *
     * @param hourlyBudget  长度 24 的小时预算
     * @param dailyTotalCap 日总确认额度
     * @return 24 个预算之和
     * @throws IllegalArgumentException 长度不为 24、存在负数或合计不等于日总额时抛出，
     *                                  消息明确实际合计、要求值与差额
     */
    public static int validateBudgets(List<Integer> hourlyBudget, int dailyTotalCap) {
        if (hourlyBudget == null || hourlyBudget.size() != 24) {
            throw new IllegalArgumentException("hourlyBudget must contain exactly 24 entries");
        }
        long sum = 0;
        for (Integer value : hourlyBudget) {
            if (value == null || value < 0) {
                throw new IllegalArgumentException("each hourly budget must be a non-negative integer");
            }
            sum += value;
        }
        if (sum != dailyTotalCap) {
            throw new IllegalArgumentException("sum of hourly budgets is " + sum
                    + " but dailyTotalCap is " + dailyTotalCap
                    + " (difference " + (sum - dailyTotalCap) + ")");
        }
        return (int) sum;
    }

    /** 长度 24 的全零小时预算（日总额为 0 的活动，任何小时都不接受申请）。 */
    public static List<Integer> zeroBudgets() {
        List<Integer> budgets = new ArrayList<>(24);
        for (int h = 0; h < 24; h++) {
            budgets.add(0);
        }
        return budgets;
    }

    /**
     * 返回从指定 UTC 时刻起第一个“存在正预算”的小时序号（0～23）。
     *
     * @param budget 长度 24 的小时预算
     * @param hour   起始小时（含），取值 0～23
     * @return 第一个正预算小时；当天剩余小时（含 hour）全部为 0 时返回 -1
     */
    public static int nextPositiveHour(int[] budget, int hour) {
        for (int h = hour; h < 24; h++) {
            if (budget[h] > 0) {
                return h;
            }
        }
        return -1;
    }

    /** UTC 日期 + 小时 -> 该小时起始时刻的 epoch 毫秒（UTC），区间左端点。 */
    public static long hourStartEpochMillis(LocalDate utcDate, int hour) {
        return LocalDateTime.of(utcDate, java.time.LocalTime.of(hour, 0))
                .toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    /** epoch 毫秒（UTC）-> UTC 日期。 */
    public static LocalDate utcDateOf(long epochMillis) {
        return java.time.Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate();
    }

    /** epoch 毫秒（UTC）-> UTC 小时序号（0～23）。 */
    public static int utcHourOf(long epochMillis) {
        return java.time.Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).getHour();
    }
}
