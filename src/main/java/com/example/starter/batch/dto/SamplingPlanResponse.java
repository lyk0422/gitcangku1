package com.example.starter.batch.dto;

import com.example.starter.batch.SamplingPlanStatus;

import java.time.Instant;

/**
 * 抽样检验计划明细。status 为 OPEN/ACCEPTED/REJECTED；
 * weightedDefects 为截至当前累计加权缺陷数（CRITICAL=3、MAJOR=1、MINOR=0）；
 * recordedCount 为已登记样本件数；decidedAt 仅终结后非空，为判定时刻（UTC）。
 */
public record SamplingPlanResponse(
        String planKey,
        String batchKey,
        int sampleSize,
        int acceptNumber,
        int rejectNumber,
        String basis,
        SamplingPlanStatus status,
        int recordedCount,
        int weightedDefects,
        Instant createdAt,
        Instant decidedAt
) {
}
