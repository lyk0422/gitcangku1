package com.example.starter.calibration.api.dto;

/**
 * 提交离群复核请求。复核必须引用当前测量修订版本与证书版本，否则返回 410 并记录 STALE 复核。
 *
 * @param requestId     调用方请求 ID（幂等键）
 * @param reviewer      复核人，须不同于读数提交人
 * @param decision      复核结论：ACCEPT / REJECT
 * @param reason        复核理由；ACCEPT 必填，REJECT 可选
 * @param batchRevision 复核引用的批次测量修订版本
 * @param certificateId 复核引用的证书 ID（证书版本）
 */
public record SubmitReviewRequest(
        String requestId,
        String reviewer,
        String decision,
        String reason,
        Integer batchRevision,
        Long certificateId) {
}
