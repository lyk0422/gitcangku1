package com.example.starter.batch.dto;

import com.example.starter.batch.ConditionStatus;

import java.time.Instant;
import java.util.List;

/**
 * 条件放行明细：条件记录 + 全部子项（按子项序号稳定排序）+ 未核销子项标识列表 + 到期状态。
 * expired 由可注入时钟在查询时判定：到期时刻已到且仍有未核销子项时为 true。
 */
public record ConditionalReleaseResponse(
        String batchKey,
        String conditionKey,
        String createdBy,
        String createdRole,
        Instant expiresAt,
        ConditionStatus status,
        boolean expired,
        List<ConditionItemResponse> items,
        List<String> pendingItems,
        Instant createdAt,
        Instant completedAt
) {
}
