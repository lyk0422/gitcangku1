package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 金丝雀验证级别声明：比例逐级严格递增，达到最小样本数且失败率不超上限方可推进到下一级。
 *
 * @param ratio          该级别投放比例，取值1~100
 * @param minSamples     最小验证样本数，取值1~50
 * @param maxFailureRate 失败率上限，单位百分之一（百分数），取值1~100
 */
public record CanaryLevelRequest(
        @Min(1) @Max(100) int ratio,
        @Min(1) @Max(50) int minSamples,
        @Min(1) @Max(100) int maxFailureRate) {
}
