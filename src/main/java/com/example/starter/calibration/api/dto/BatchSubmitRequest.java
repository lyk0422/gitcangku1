package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 批量测量提交请求。每批 1～50 条，先按最终引用预校验，全部通过才原子写入。
 *
 * @param batchId 提交批次 ID，用于后续整批替换标准器
 * @param items   测量列表
 */
public record BatchSubmitRequest(String batchId, List<BatchMeasurementItem> items) {
}
