package com.example.starter.batch.dto;

import com.example.starter.batch.DefectGrade;
import com.example.starter.batch.SamplingPlanStatus;

import java.time.Instant;

/**
 * 单件样本登记结果。grade 为 null 表示合格件；
 * weightedDefects 为登记该件之后计划累计加权缺陷数；
 * planStatus 为该件落定后计划状态；decidedAt 为判定时刻，未终结时为 null。
 */
public record SampleRecordResponse(
        String planKey,
        int sampleNo,
        boolean conforming,
        DefectGrade grade,
        String description,
        int weightedDefects,
        SamplingPlanStatus planStatus,
        Instant registeredAt,
        Instant decidedAt
) {
}
