package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 批量测量提交响应。
 *
 * @param batchId      提交批次 ID
 * @param measurements 按请求顺序排列的测量明细（新建或同键重放的已有记录）
 */
public record BatchSubmitResponse(String batchId, List<MeasurementResponse> measurements) {
}
