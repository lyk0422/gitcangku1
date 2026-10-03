package com.example.starter.service;

import com.example.starter.domain.PressureSample;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 切换条件纯函数评估：最近 15 分钟内至少 4 个有效采样，相邻间隔不超过 5 分钟，
 * 且每个读数处于 8 至 12 MPa（含边界）。输入须为窗口内按时刻升序的有效采样。
 */
public final class SwitchConditionEvaluator {

    /** 评估窗口长度：15 分钟。 */
    public static final Duration WINDOW = Duration.ofMinutes(15);
    /** 最少有效采样数：4。 */
    public static final int MIN_SAMPLES = 4;
    /** 相邻采样最大间隔：5 分钟。 */
    public static final Duration MAX_GAP = Duration.ofMinutes(5);
    /** 压力下限（含），单位 MPa。 */
    public static final BigDecimal MIN_PRESSURE = new BigDecimal("8.0");
    /** 压力上限（含），单位 MPa。 */
    public static final BigDecimal MAX_PRESSURE = new BigDecimal("12.0");

    private SwitchConditionEvaluator() {
    }

    /**
     * 评估窗口内有效采样是否满足切换条件。
     *
     * @param validSamples 窗口内未作废采样，按采样时刻升序
     * @return 不满足时的拒绝原因；满足时为空
     */
    public static Optional<String> evaluate(List<PressureSample> validSamples) {
        if (validSamples.size() < MIN_SAMPLES) {
            return Optional.of("最近15分钟内有效采样不足" + MIN_SAMPLES + "个（当前"
                    + validSamples.size() + "个）");
        }
        for (PressureSample sample : validSamples) {
            BigDecimal pressure = sample.getPressureMpa();
            if (pressure.compareTo(MIN_PRESSURE) < 0 || pressure.compareTo(MAX_PRESSURE) > 0) {
                return Optional.of("采样#" + sample.getId() + " 读数 " + pressure.stripTrailingZeros().toPlainString()
                        + " MPa 超出 8~12 MPa 范围");
            }
        }
        for (int i = 1; i < validSamples.size(); i++) {
            Duration gap = Duration.between(
                    validSamples.get(i - 1).getSampledAt(), validSamples.get(i).getSampledAt());
            if (gap.compareTo(MAX_GAP) > 0) {
                return Optional.of("相邻采样间隔 " + gap.toMinutes() + " 分钟超过 5 分钟上限");
            }
        }
        return Optional.empty();
    }
}