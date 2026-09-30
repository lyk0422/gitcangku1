package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 离群记录明细。封存字段全部在检测时固化，后续修订不改变其内容。
 *
 * @param outlierKey    离群业务键
 * @param batchKey      所属批次键
 * @param revisionNo    检测时封存的测量修订版本号
 * @param measurementKey 离群读数的测量键
 * @param targetValue   封存目标值
 * @param tolerance     封存允许偏差
 * @param lowerLimit    封存允许下限（含端点）
 * @param upperLimit    封存允许上限（含端点）
 * @param reading       封存原始读数
 * @param computedValue 封存未舍入计算值
 * @param deviation     有符号差额=计算值-目标值（实际值-要求值）
 * @param excess        超出量=|差额|-允许偏差（差额，离群必为正）
 * @param submittedBy   封存读数提交人（不得复核）
 * @param status        裁决状态：PENDING（含待仲裁）/ ACCEPTED / REJECTED
 * @param detectedAt    检测时间（UTC）
 * @param decidedAt     裁决形成时间（UTC）；未形成有效裁决为 null
 * @param activeAccepts 当前有效 ACCEPT 复核数（要求值 2）
 * @param activeRejects 当前有效 REJECT 复核数（要求值 2）
 * @param staleReasons  最近一次复核被标记 STALE 的原因码（如 MEASUREMENT_REVISION_CHANGED）；无触发时为空列表
 * @param reviews       全部复核历史（按提交顺序，含 STALE）
 */
public record OutlierResponse(
        String outlierKey,
        String batchKey,
        int revisionNo,
        String measurementKey,
        String targetValue,
        String tolerance,
        String lowerLimit,
        String upperLimit,
        String reading,
        String computedValue,
        String deviation,
        String excess,
        String submittedBy,
        String status,
        Instant detectedAt,
        Instant decidedAt,
        int activeAccepts,
        int activeRejects,
        List<String> staleReasons,
        List<ReviewResponse> reviews) {
}
