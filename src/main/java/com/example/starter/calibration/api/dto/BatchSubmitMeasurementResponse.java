package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批量测量提交成功响应。
 *
 * @param batchId    批量提交幂等键
 * @param replayed   是否为同键重放（true 表示返回的是首次提交结果，未重复落库）
 * @param submittedBy 提交人
 * @param createdAt  首次成功提交时间（UTC）
 * @param items      本次成功提交（或重放）的测量明细
 */
public record BatchSubmitMeasurementResponse(
        String batchId,
        boolean replayed,
        String submittedBy,
        Instant createdAt,
        List<MeasurementResponse> items) {
}
