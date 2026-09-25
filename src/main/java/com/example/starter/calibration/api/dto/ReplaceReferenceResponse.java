package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 替换标准器响应。
 *
 * @param batchId    提交批次 ID
 * @param recomputed 各测量重算后的新版本号
 */
public record ReplaceReferenceResponse(String batchId, List<RecomputedItem> recomputed) {
}
