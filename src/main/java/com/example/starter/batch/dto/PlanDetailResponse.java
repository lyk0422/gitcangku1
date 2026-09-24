package com.example.starter.batch.dto;

import java.util.List;

/**
 * 抽样计划明细：计划概要 + 按样本序号排序的全部逐件登记结果。
 */
public record PlanDetailResponse(
        SamplingPlanResponse plan,
        List<SampleRecordResponse> samples
) {
}
