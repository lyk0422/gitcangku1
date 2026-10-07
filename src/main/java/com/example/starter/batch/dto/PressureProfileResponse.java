package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;
import com.example.starter.batch.TestOutcome;

import java.time.Instant;

/**
 * 压力曲线检验响应。points 为提交时冻结的节点规范序列化（offsetSeconds:pressureKpa，
 * 逗号分隔，压力按值规范化去尾零）；cumulativeDescentKpa 为各下降段正下降之和（精确值）；
 * maxDescentRateKpaPerSec 为最大逐段下降速率（展示精度 10 位小数，门禁判定不依赖该舍入值）；
 * batchStatus 为该检验落定后的批次状态。
 */
public record PressureProfileResponse(
        String batchKey,
        String requestId,
        String operator,
        int pointCount,
        String points,
        String cumulativeDescentKpa,
        String maxDescentRateKpaPerSec,
        TestOutcome outcome,
        BatchStatus batchStatus,
        Instant createdAt
) {
}
