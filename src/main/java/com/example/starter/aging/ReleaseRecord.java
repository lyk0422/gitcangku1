package com.example.starter.aging;

import java.time.Instant;

/**
 * 质检放行记录。snapshotJson 固化放行时七天的翻面与温度记录编号，之后补录或作废不影响。
 */
public record ReleaseRecord(
        long id,
        long batchId,
        String requestId,
        Instant releasedAt,
        String snapshotJson) {
}