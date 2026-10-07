package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;
import com.example.starter.batch.SequentialAssayStatus;
import com.example.starter.batch.SequentialOutcome;

import java.time.Instant;

/**
 * 合成序贯检验结果响应。
 * ratioNumerator/ratioDenominator 为精确分数，阈值判定只使用精确分数；
 * ratioDisplay 仅为展示用十进制小数，不得用于阈值判定。
 */
public record SequentialResultResponse(
        String batchKey,
        String requestId,
        int sampleNumber,
        SequentialOutcome outcome,
        String operator,
        SequentialAssayStatus assayStatus,
        long ratioNumerator,
        long ratioDenominator,
        String ratioDisplay,
        BatchStatus batchStatus,
        Instant createdAt
) {
}
