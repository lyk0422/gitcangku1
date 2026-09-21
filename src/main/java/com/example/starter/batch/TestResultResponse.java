package com.example.starter.batch;

import java.time.Instant;

/**
 * 检验结果响应。
 *
 * @param testKey     检验幂等键
 * @param item        检验项
 * @param outcome     PASS/FAIL
 * @param inspector   检验人
 * @param testedAt    检验提交时间（UTC）
 * @param batchStatus 提交后批次状态
 */
public record TestResultResponse(
        String testKey,
        String item,
        TestOutcome outcome,
        String inspector,
        Instant testedAt,
        BatchStatus batchStatus) {
}
