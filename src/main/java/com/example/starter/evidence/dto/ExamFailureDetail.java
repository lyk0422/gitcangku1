package com.example.starter.evidence.dto;

/**
 * 批量预约单条失败明细，失败原因可区分；数量类冲突给出实际值、要求上限与差额。
 *
 * @param itemIndex      失败明细在请求 items 中的下标（从 0 开始）
 * @param reason         机器可区分原因：WINDOW_INVALID/EVIDENCE_NOT_FOUND/BENCH_NOT_FOUND/
 *                       EVIDENCE_BORROWED/EVIDENCE_IN_EXAMINATION/EVIDENCE_PENDING_TRANSFER/
 *                       SEAL_ABNORMAL/EVIDENCE_OCCUPIED/DUPLICATE_EVIDENCE/
 *                       DUPLICATE_RESERVATION_KEY/BENCH_CAPACITY_EXCEEDED
 * @param reservationKey 关联预约业务键；与证物/台位无关的错误可能为 null
 * @param evidenceKey    关联证物业务键；可能为 null
 * @param benchKey       关联检验台业务键；可能为 null
 * @param actual         实际值（如最终占用数）；非数量类原因为 null
 * @param required       要求值或上限（如台位容量）；非数量类原因为 null
 * @param deficit        差额（实际值减要求上限，大于 0 表示超出量）；非数量类原因为 null
 * @param message        人类可读描述
 */
public record ExamFailureDetail(
        int itemIndex,
        String reason,
        String reservationKey,
        String evidenceKey,
        String benchKey,
        Integer actual,
        Integer required,
        Integer deficit,
        String message) {

    /**
     * 构造非数量类失败明细（实际/要求/差额均为 null）。
     */
    public static ExamFailureDetail of(int itemIndex, String reason, String reservationKey,
                                       String evidenceKey, String benchKey, String message) {
        return new ExamFailureDetail(itemIndex, reason, reservationKey, evidenceKey, benchKey,
                null, null, null, message);
    }
}
