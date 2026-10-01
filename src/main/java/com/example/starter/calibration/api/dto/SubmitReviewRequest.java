package com.example.starter.calibration.api.dto;

/**
 * 提交离群复核请求。复核必须引用当前测量修订版本与证书版本，
 * 版本已变更或证书失效时返回 410 并将该复核标记为 STALE。
 *
 * @param requestId           幂等请求 ID；同参重放首次结果，异参 409，失败不占键
 * @param reviewer            复核人（须不同于读数提交人）
 * @param decision            复核结论：ACCEPT / REJECT
 * @param reason              复核理由；ACCEPT 必填，REJECT 可选
 * @param measurementRevision 复核引用的测量修订版本
 * @param certificateId       复核引用的证书版本（证书 ID）
 */
public record SubmitReviewRequest(
        String requestId,
        String reviewer,
        String decision,
        String reason,
        Integer measurementRevision,
        Long certificateId) {
}