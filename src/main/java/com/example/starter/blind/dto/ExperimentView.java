package com.example.starter.blind.dto;

/**
 * 实验视图：不含任何席位处理映射信息。
 */
public record ExperimentView(
        String experimentId,
        int blockCount,
        String status) {
}
