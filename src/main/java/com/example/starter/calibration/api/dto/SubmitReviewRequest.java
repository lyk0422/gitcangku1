package com.example.starter.calibration.api.dto;

/**
 * 提交离群复核请求。复核必须引用当前测量修订版本与证书版本。
 *
 * @param reviewer           复核人，须不同于读数提交人
 * @param decision           复核结论：ACCEPT / REJECT
 * @param reason             复核理由；ACCEPT 必填，REJECT 可选
 * @param batchRevision      复核引用的测量修订版本
 * @param certificateVersion 复核引用的证书版本
 * @param requestId          幂等请求 ID；同参重放返回首次结果，异参 409，失败不占键；可为 null 表示不启用
 */
public record SubmitReviewRequest(
        String reviewer,
        String decision,
        String reason,
        Integer batchRevision,
        Integer certificateVersion,
        String requestId) {
}