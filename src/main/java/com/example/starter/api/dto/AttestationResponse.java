package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 见证提交/撤销结果视图。
 *
 * @param revoked            是否已撤销：true=已撤销（记录保留，不再计入有效见证）
 * @param attestationVersion 本次操作后的见证版本号
 * @param createdAt          见证首次提交时间，UTC
 * @param revokedAt          撤销时间，UTC；未撤销为 null
 */
public record AttestationResponse(
        String name,
        int version,
        String role,
        String checksum,
        boolean revoked,
        int attestationVersion,
        Instant createdAt,
        Instant revokedAt) {
}
