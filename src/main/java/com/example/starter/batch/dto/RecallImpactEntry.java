package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;
import com.example.starter.batch.TestOutcome;

/**
 * 召回血缘影响条目：闭包内批次的自身状态、最新召回代次与复检合格性。
 * recallVersion/recallStatus 为 null 表示该批次从未被直接召回；
 * latestRetest 为 null 表示尚无复检记录。
 */
public record RecallImpactEntry(
        String batchKey,
        BatchStatus status,
        Integer recallVersion,
        String recallStatus,
        TestOutcome latestRetest,
        boolean retestQualified
) {
}
