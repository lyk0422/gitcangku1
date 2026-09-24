package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * FAIL 核查引入的单条 SUSPECT 结果标记。每个 FAIL 对区间内每条已放行结果各一行；
 * 解除时只清除不再被任何未解除 FAIL 覆盖的标记，因此同一结果可同时被多个 FAIL 标记。
 *
 * @param id               标记 ID（自增）
 * @param checkId          引入标记的 FAIL 核查记录 ID
 * @param measurementId    被标记的测量记录 ID
 * @param markedAt         标记时间（UTC）
 * @param clearedByCheckId 解除标记的 PASS 核查记录 ID；null 表示仍被隔离
 * @param clearedAt        解除时间（UTC）；未解除为 null
 */
public record SuspectMarking(
        long id,
        long checkId,
        long measurementId,
        Instant markedAt,
        Long clearedByCheckId,
        Instant clearedAt) {
}
