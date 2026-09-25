package com.example.starter.calibration.api.dto;

/**
 * 同行复核提交请求。
 *
 * @param measurementKey 被复核测量的业务键
 * @param reviewKey      复核业务键，全局唯一（幂等键）
 * @param version        复核人依据的测量当前版本号；与库内当前版本不一致时返回 410
 * @param conclusion     复核结论：PASS / RETURN
 * @param comment        复核说明
 */
public record SubmitReviewRequest(
        String measurementKey,
        String reviewKey,
        Integer version,
        String conclusion,
        String comment) {
}
