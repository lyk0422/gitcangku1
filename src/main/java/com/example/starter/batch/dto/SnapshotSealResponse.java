package com.example.starter.batch.dto;

/**
 * 放行快照中的单个封箱版本明细，固化后不可改写。
 */
public record SnapshotSealResponse(
        long labelNo,
        int quantity,
        int version,
        int seq
) {
}
