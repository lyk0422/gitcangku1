package com.example.starter.dive.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 深度点位，审核后冻结，只允许追加不允许修改。
 *
 * @param id            点位ID
 * @param sessionId     所属作业ID
 * @param seq           作业内点位序号，从1开始严格递增
 * @param recordedAtUtc 点位记录时刻（UTC），业务要求严格递增且相邻间隔不超过5分钟
 * @param depthM        深度（米），非负
 * @param requestId     上传该点位的幂等请求ID
 * @param createdAtUtc  落库时刻（UTC）
 */
public record DepthPoint(
        long id,
        long sessionId,
        int seq,
        Instant recordedAtUtc,
        BigDecimal depthM,
        String requestId,
        Instant createdAtUtc) {
}