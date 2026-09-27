package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 披露包撤销记录。仅已撤销（REVOKED）包存在该记录。
 *
 * @param packageKey 披露包业务键
 * @param caseKey    案件键
 * @param revokedBy  撤销操作人
 * @param reason     撤销原因
 * @param revokedAt  撤销时间（Asia/Shanghai）
 */
public record PackageRevocationView(
        String packageKey,
        String caseKey,
        String revokedBy,
        String reason,
        LocalDateTime revokedAt) {
}
