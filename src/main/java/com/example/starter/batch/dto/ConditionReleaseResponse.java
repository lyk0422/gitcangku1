package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 条件放行明细：包含创建信息、到期状态（expired 按可注入时钟实时判定，不依赖后台任务）、
 * 逐条子项及其核销状态。items 按 seq 稳定排序。
 *
 * @param batchKey 所属批次业务键
 * @param conditionKey 条件放行业务键
 * @param creatorId 创建批准人标识
 * @param creatorRole 创建批准角色，核销角色必须与之不同
 * @param expiresAt 条件有效期到期时刻（UTC）
 * @param createdAt 创建时刻（UTC）
 * @param expired 当前时钟下是否已到期
 * @param batchStatus 查询时的批次状态
 * @param items 条件子项明细，按 seq 升序
 */
public record ConditionReleaseResponse(
        String batchKey,
        String conditionKey,
        String creatorId,
        String creatorRole,
        Instant expiresAt,
        Instant createdAt,
        boolean expired,
        BatchStatus batchStatus,
        List<ConditionItemResponse> items
) {
}
