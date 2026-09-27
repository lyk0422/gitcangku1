package com.example.starter.disclosure.dto;

import com.example.starter.disclosure.DisclosurePackageStatus;

import java.time.LocalDateTime;

/**
 * 撤销记录视图。仅已撤销包存在该记录。
 *
 * @param packageKey     包指纹
 * @param caseKey        所属案件键
 * @param packageVersion 包版本
 * @param status         包当前状态（固定 REVOKED）
 * @param revokedAt      撤销时间（Asia/Shanghai）
 * @param revokedBy      撤销操作者
 * @param revokeReason   撤销原因
 */
public record RevocationRecordView(
        String packageKey,
        String caseKey,
        int packageVersion,
        DisclosurePackageStatus status,
        LocalDateTime revokedAt,
        String revokedBy,
        String revokeReason) {
}
