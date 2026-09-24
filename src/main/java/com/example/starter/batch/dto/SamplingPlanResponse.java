package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 抽样检验计划概要/明细响应。weightedDefects 为累计加权缺陷数（单位：加权分），
 * registeredCount 为已登记件数；decidedAt 为判定时刻，OPEN 未终结时为 null，终结后不可改写。
 */
public record SamplingPlanResponse(
        String planKey,
        String batchKey,
        int seq,
        int sampleSize,
        int acceptNumber,
        int rejectNumber,
        String basis,
        String status,
        int weightedDefects,
        int registeredCount,
        Instant createdAt,
        Instant decidedAt
) {
}
