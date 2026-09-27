package com.example.starter.calibration.api.dto;

/**
 * 离群复核请求。复核必须引用当前测量修订版本与证书版本，否则 410 并留痕 STALE。
 *
 * @param requestId     幂等键：同参重放返回首次结果，异参 409，失败不占键
 * @param reviewer      复核人（必须不同于读数提交人）
 * @param decision      复核结论：ACCEPT / REJECT
 * @param reason        复核理由（必填）
 * @param batchRevision 复核引用的批次修订版本号
 * @param certificateId 复核引用的证书版本（证书 ID）
 */
public record ReviewRequest(
        String requestId,
        String reviewer,
        String decision,
        String reason,
        Integer batchRevision,
        Long certificateId) {
}
