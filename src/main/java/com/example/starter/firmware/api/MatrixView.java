package com.example.starter.firmware.api;

/**
 * 兼容矩阵版本视图（只读）。updatedAtUtc 为最近一次成功登记时刻，UTC；从未登记为 null。
 */
public record MatrixView(int version, String updatedAtUtc, long firmwareRequirements, long moduleDependencies) {
}
