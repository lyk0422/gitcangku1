package com.example.starter.water;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 田块入渗余额核算引擎（纯函数，不涉及时钟与数据库，时间全部由入参给定）。
 *
 * <p>规则：事件按（灌溉时刻，申请键字典序）排序；每个事件先把余额恢复为
 * min(C, 前余额 + 距上一时刻的整小时数 × R)，再扣减该事件的批准量；余额不得为负。
 * 初始余额为容量 C，起点时刻为田块启用时刻；间隔不足 1 小时的部分向下取整不计恢复。
 * 容量恢复只作用于入渗余额，不返还窗口额度。</p>
 */
public final class InfiltrationEngine {

    /** 一小时的纳秒数。 */
    public static final long HOUR_NANOS = 3_600_000_000_000L;

    private InfiltrationEngine() {
    }

    /** 入渗事件：某申请在灌溉时刻（UTC 纳秒，整小时）扣减的批准量，单位立方米。 */
    public record Event(String allocationKey, long irrigationNanos, BigDecimal deduct) {
    }

    /** 核算步：事件及其扣减后的余额，单位立方米。 */
    public record Step(String allocationKey, long irrigationNanos, BigDecimal deduct, BigDecimal balanceAfter) {
    }

    /** 核算结果；deficit 非 null 表示在该步余额不足（扣后为负），steps 截止于该步。 */
    public record Outcome(List<Step> steps, Step deficit) {

        /** 指定申请键最后一次扣减后的余额；不存在返回 null。 */
        public BigDecimal balanceOf(String allocationKey) {
            BigDecimal found = null;
            for (Step step : steps) {
                if (step.allocationKey().equals(allocationKey)) {
                    found = step.balanceAfter();
                }
            }
            return found;
        }

        /** 全部事件扣完后的余额；无事件时为容量 C。 */
        public BigDecimal finalBalance(long capacity) {
            return steps.isEmpty() ? BigDecimal.valueOf(capacity)
                    : steps.get(steps.size() - 1).balanceAfter();
        }
    }

    /**
     * 依最终时序核算入渗余额。
     *
     * @param capacity        容量 C，单位立方米，正整数
     * @param recoveryPerHour 每小时恢复 R，单位立方米，正整数
     * @param enabledNanos    田块启用时刻，UTC 纳秒；所有事件不早于此（提交时已校验）
     * @param events          当前已批准申请集合（按需含候选），灌溉时刻须为整小时
     */
    public static Outcome evaluate(long capacity, long recoveryPerHour, long enabledNanos,
                                   List<Event> events) {
        List<Event> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparingLong(Event::irrigationNanos).thenComparing(Event::allocationKey));
        BigDecimal cap = BigDecimal.valueOf(capacity);
        BigDecimal rate = BigDecimal.valueOf(recoveryPerHour);
        BigDecimal balance = cap;
        long previous = enabledNanos;
        List<Step> steps = new ArrayList<>();
        Step deficit = null;
        for (Event event : sorted) {
            long elapsedHours = Math.floorDiv(event.irrigationNanos() - previous, HOUR_NANOS);
            balance = balance.add(rate.multiply(BigDecimal.valueOf(elapsedHours)));
            if (balance.compareTo(cap) > 0) {
                balance = cap;
            }
            balance = balance.subtract(event.deduct());
            Step step = new Step(event.allocationKey(), event.irrigationNanos(), event.deduct(), balance);
            steps.add(step);
            previous = event.irrigationNanos();
            if (balance.signum() < 0) {
                deficit = step;
                break;
            }
        }
        return new Outcome(steps, deficit);
    }
}
