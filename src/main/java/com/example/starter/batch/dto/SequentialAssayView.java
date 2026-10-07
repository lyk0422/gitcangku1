package com.example.starter.batch.dto;

import com.example.starter.batch.SequentialAssayStatus;
import com.example.starter.batch.SequentialOutcome;

import java.time.Instant;
import java.util.List;

/**
 * 批次序贯检验历史视图：完整前缀、精确分数与结论在终止后冻结，不随后代批次变化。
 * ratioDisplay 仅为展示用十进制小数，不得用于阈值判定。
 */
public record SequentialAssayView(
        SequentialAssayStatus status,
        int nextSampleNumber,
        long ratioNumerator,
        long ratioDenominator,
        String ratioDisplay,
        List<Entry> results
) {

    /**
     * 单条序贯检验结果历史项，含落定后的精确分数与检验结论快照。
     */
    public record Entry(
            int sampleNumber,
            SequentialOutcome outcome,
            String operator,
            long ratioNumerator,
            long ratioDenominator,
            SequentialAssayStatus assayStatus,
            Instant createdAt
    ) {
    }
}
