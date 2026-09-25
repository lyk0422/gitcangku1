package com.example.starter.calibration.api.dto;

/**
 * 提交同行复核请求。复核人通过 X-Actor-Id 请求头提供，须不同于测量提交人。
 *
 * @param reviewKey  业务复核键，全局唯一（幂等键）
 * @param requestId  请求 ID：同键同参重放首次结果，同键异参 409，失败不占键
 * @param revision   被复核的测量修订版本号；与当前版本不一致时复核被记为 STALE 并返回 410
 * @param conclusion 复核结论：PASS / RETURN
 * @param comment    复核说明
 */
public record SubmitReviewRequest(
        String reviewKey,
        String requestId,
        Integer revision,
        String conclusion,
        String comment) {
}
