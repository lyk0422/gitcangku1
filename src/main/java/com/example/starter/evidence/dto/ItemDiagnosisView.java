package com.example.starter.evidence.dto;

import com.example.starter.evidence.ItemDiagnosisCode;

/**
 * 单件证物按截至 UTC 时刻重放的逐件链诊断视图（建包失败时逐项返回，原因可区分；
 * 成功包内每件的固化诊断码固定为 OK）。
 *
 * @param evidenceKey 证物业务键
 * @param code        诊断码：OK / EVIDENCE_NOT_FOUND / NOT_IN_CASE / CHAIN_GAP /
 *                    PENDING_TRANSFER_AT_AS_OF / ACTIVE_LOAN_AT_AS_OF / SEAL_BROKEN_AT_AS_OF
 * @param message     可区分的中文诊断说明
 */
public record ItemDiagnosisView(
        String evidenceKey,
        ItemDiagnosisCode code,
        String message) {
}
