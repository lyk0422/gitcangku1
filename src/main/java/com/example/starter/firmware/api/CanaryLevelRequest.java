package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 金丝雀验证级别定义：比例逐级递增，样本数与失败率上限构成推进门禁。
 *
 * @param ratio          该级别投放比例，取值1~100
 * @param minSamples     推进所需最小验证样本数，取值1~50
 * @param maxFailureRate 失败率上限，单位百分比，取值1~100
 */
public record CanaryLevelRequest(
        @Min(1) @Max(100) int ratio,
        @Min(1) @Max(50) int minSamples,
        @Min(1) @Max(100) int maxFailureRate) {
}
