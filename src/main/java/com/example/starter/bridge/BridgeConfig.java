package com.example.starter.bridge;

/**
 * 桥梁调度配置（对应 bridge_config 表）。
 *
 * @param maxMassKg 允许通行的最大总质量，单位千克
 * @param windowMinutes 单次通行预计窗口时长，单位分钟
 */
public record BridgeConfig(long maxMassKg, long windowMinutes) {
}