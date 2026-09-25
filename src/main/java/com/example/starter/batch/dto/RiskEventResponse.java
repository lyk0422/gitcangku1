package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 批次风险记录。riskType 为风险类型：
 * RELEASED_MAJOR_EXCURSION——已放行批次新增 MAJOR 偏差并转为待处置；
 * REJECTED_BY_EXCURSION——MAJOR 偏差裁决 REJECT，批次及其后代按召回口径拦截。
 * detail 为风险明细；历史放行与既有记录不删除，风险只增不改。
 */
public record RiskEventResponse(
        long id,
        String batchKey,
        String riskType,
        String detail,
        String actorId,
        Instant createdAt
) {
}
