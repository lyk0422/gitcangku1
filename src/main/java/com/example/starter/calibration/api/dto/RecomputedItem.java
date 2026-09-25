package com.example.starter.calibration.api.dto;

/**
 * 替换标准器后单条重算结果。
 *
 * @param measurementKey 测量键
 * @param version        重算生成的新测量版本号
 */
public record RecomputedItem(String measurementKey, int version) {
}
