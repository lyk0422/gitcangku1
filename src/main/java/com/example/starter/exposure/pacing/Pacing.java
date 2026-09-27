package com.example.starter.exposure.pacing;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 活动匀速投放纯逻辑：UTC 小时归属、24 小时预算校验、已开始小时判定与下一可用小时扫描。
 * 不依赖数据库与时钟实例，全部为可单测的静态函数。时间一律按 UTC 处理，小时区间左闭右开。
 */
public final class Pacing {

    private Pacing() {
    }

    /** 时刻所属 UTC 小时（0～23），区间左闭右开 [hh:00, hh+1:00)。 */
    public static int utcHour(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC).getHour();
    }

    /** 时刻所属 UTC 自然日。 */
    public static LocalDate utcDate(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC).toLocalDate();
    }

    /**
     * 校验日总额与 24 小时预算：列表必须恰好 24 项、每项非负整数、合计恰好等于日总额；
     * 日总额必须为非负整数。
     *
     * @throws IllegalArgumentException 任一要求不满足，消息指明具体原因
     */
    public static void validateBudgets(int dailyTotalCap, List<Integer> hourlyBudgets) {
        if (dailyTotalCap < 0) {
            throw new IllegalArgumentException("dailyTotalCap must be zero or positive");
        }
        if (hourlyBudgets == null || hourlyBudgets.size() != 24) {
            throw new IllegalArgumentException("hourlyBudgets must contain exactly 24 hours");
        }
        long sum = 0L;
        for (int hour = 0; hour < 24; hour++) {
            Integer budget = hourlyBudgets.get(hour);
            if (budget == null || budget < 0) {
                throw new IllegalArgumentException(
                        "hourly budget for hour " + hour + " must be a non-negative integer");
            }
            sum += budget;
        }
        if (sum != dailyTotalCap) {
            throw new IllegalArgumentException(
                    "sum of 24 hourly budgets must equal dailyTotalCap, actual sum " + sum
                            + " but required " + dailyTotalCap);
        }
    }

    /**
     * 配置修改规则：已开始的小时（含当前进行中的小时）预算不可下调。
     * 仅比较小时序号（配置按 UTC 自然日重复），now 所在小时及其之前的小时一律不得变小。
     *
     * @return 第一个被违规下调的已开始小时；全部合规返回 empty
     */
    public static Optional<Integer> findReducedStartedHour(List<Integer> oldBudgets,
                                                           List<Integer> newBudgets,
                                                           Instant now) {
        int currentHour = utcHour(now);
        for (int hour = 0; hour <= currentHour; hour++) {
            if (newBudgets.get(hour) < oldBudgets.get(hour)) {
                return Optional.of(hour);
            }
        }
        return Optional.empty();
    }

    /**
     * 从当前小时的下一可用小时起扫描：当日后续小时预算大于 0 即可用；当日无可用则扫描次日。
     * 未来小时不可能存在占用（申请只能预占当前小时名额），故预算大于 0 即为可用。
     * 全日预算均为 0（日总额为 0）时返回 empty。
     */
    public static Optional<Instant> nextAvailableHourStart(List<Integer> hourlyBudgets, Instant now) {
        var offsetNow = now.atOffset(ZoneOffset.UTC);
        LocalDate today = offsetNow.toLocalDate();
        int currentHour = offsetNow.getHour();
        for (int hour = currentHour + 1; hour < 24; hour++) {
            if (hourlyBudgets.get(hour) > 0) {
                return Optional.of(today.atTime(hour, 0).toInstant(ZoneOffset.UTC));
            }
        }
        LocalDate tomorrow = today.plusDays(1);
        for (int hour = 0; hour < 24; hour++) {
            if (hourlyBudgets.get(hour) > 0) {
                return Optional.of(tomorrow.atTime(hour, 0).toInstant(ZoneOffset.UTC));
            }
        }
        return Optional.empty();
    }
}
