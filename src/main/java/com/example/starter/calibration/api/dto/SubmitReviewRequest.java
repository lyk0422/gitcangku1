package com.example.starter.calibration.api.dto;

/**
 * 提交离群复核请求。必须引用当前测量修订版本与证书版本，否则复核落库为 STALE 并返回 410。
 *
 * @param requestId          幂等请求 ID
 * @param reviewer           复核人，须不同于读数提交人
 * @param decision           结论：ACCEPT / REJECT
 * @param reason             复核理由；ACCEPT 必填，REJECT 可空
 * @param batchRevision      复核引用的测量修订版本
 * @param certificateVersion 复核引用的证书版本
 */
public record SubmitReviewRequest(
        String requestId,
        String reviewer,
        String decision,
        String reason,
        Integer batchRevision,
        Integer certificateVersion) {
}