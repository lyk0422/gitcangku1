package com.example.starter.calibration.api.dto;

/**
 * 待复核清单项：当前版本等待同行复核（尚无有效 PASS）的测量。
 *
 * @param measurementKey 测量业务键
 * @param version        当前版本号
 * @param instrumentId   仪器 ID
 * @param submittedBy    提交人
 * @param passed         当前版本是否合格
 * @param status         当前版本状态（待复核清单内恒为 PENDING）
 */
public record PendingReviewResponse(
        String measurementKey,
        int version,
        String instrumentId,
        String submittedBy,
        boolean passed,
        String status) {
}
