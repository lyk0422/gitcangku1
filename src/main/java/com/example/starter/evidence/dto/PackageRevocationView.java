package com.example.starter.evidence.dto;

import com.example.starter.evidence.DisclosurePackageStatus;

import java.time.LocalDateTime;

/**
 * 披露包撤销记录视图。未撤销时 revoked=false 且原因/操作人/时刻为 NULL。
 *
 * @param packageKey   披露包业务键
 * @param status       包状态
 * @param revoked      是否已撤销
 * @param revokeReason 撤销原因；NULL 表示未撤销
 * @param revokedBy    撤销操作人；NULL 表示未撤销
 * @param revokedAt    撤销时刻（UTC）；NULL 表示未撤销
 */
public record PackageRevocationView(
        String packageKey,
        DisclosurePackageStatus status,
        boolean revoked,
        String revokeReason,
        String revokedBy,
        LocalDateTime revokedAt) {
}
