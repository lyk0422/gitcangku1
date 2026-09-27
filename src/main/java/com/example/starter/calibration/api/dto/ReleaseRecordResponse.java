package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 放行历史响应。放行时固化合成不确定度、阈值取值与版本、计算口径；
 * 后续阈值修订不改写本快照。
 *
 * @param batchId             放行批次 ID
 * @param releasedBy          放行人
 * @param releasedAt          放行时间（UTC）
 * @param combinedUncertainty 放行时合成不确定度（RSS，HALF_UP 4 位小数）
 * @param thresholdValue      放行时仪器阈值取值
 * @param thresholdVersion    放行时仪器阈值版本号
 * @param formula             计算口径标识
 */
public record ReleaseRecordResponse(String batchId, String releasedBy, Instant releasedAt,
                                    String combinedUncertainty, String thresholdValue,
                                    int thresholdVersion, String formula) {
}
