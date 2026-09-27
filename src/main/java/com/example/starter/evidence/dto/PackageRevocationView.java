package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 撤销记录视图：仅未交付包可撤销，撤销原因与操作人、时间留痕。
 *
 * @param caseKey        所属案件键
 * @param packageVersion 被撤销的包版本号
 * @param revokedBy      撤销操作人
 * @param revokeReason   撤销原因（非空）
 * @param revokedAt      撤销时间（Asia/Shanghai）
 */
public record PackageRevocationView(
        String caseKey,
        int packageVersion,
        String revokedBy,
        String revokeReason,
        LocalDateTime revokedAt) {
}
