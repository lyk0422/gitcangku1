package com.example.starter.batch.dto;

import com.example.starter.batch.SampleResult;

import java.time.Instant;

/**
 * 逐件样本登记响应/明细。weight 为该件加权缺陷分（QUALIFIED 与 MINOR=0、MAJOR=1、CRITICAL=3）；
 * registeredWeightedDefects 为登记该件后计划的累计加权缺陷数；planStatus 为该件落定后的计划状态快照。
 */
public record SampleRecordResponse(
        String planKey,
        int sampleIndex,
        SampleResult result,
        String description,
        int weight,
        int registeredWeightedDefects,
        String planStatus,
        Instant createdAt
) {
}
