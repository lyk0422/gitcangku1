package com.example.starter.certificate.dto;

/**
 * 批次合格证状态响应：binding 为 null 表示未绑定；releaseSnapshot 为 null 表示尚未放行。
 */
public record CertificateStatusResponse(
        String batchKey,
        BindingResponse binding,
        ReleaseSnapshotResponse releaseSnapshot
) {
}
