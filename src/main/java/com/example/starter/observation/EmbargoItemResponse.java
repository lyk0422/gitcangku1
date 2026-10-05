package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 批量禁运设置中单个簇的结果条目。
 *
 * @param observationId   观测记录唯一标识
 * @param embargoUntilUtc 禁运截止时刻（UTC，ISO-8601）
 * @param scope           归一化后的披露范围标签集合（去重排序）
 * @param setAtUtc        禁运设置时刻（UTC，ISO-8601）
 */
public record EmbargoItemResponse(
        String observationId,
        Instant embargoUntilUtc,
        List<String> scope,
        Instant setAtUtc) {
}
