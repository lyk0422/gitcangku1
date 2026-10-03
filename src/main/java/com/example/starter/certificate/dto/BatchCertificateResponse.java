package com.example.starter.certificate.dto;

/**
 * 批次合格证明细响应：当前绑定与放行快照；未绑定/未放行时对应字段为 null。
 */
public record BatchCertificateResponse(
        String batchKey,
        String batchStatus,
        CertificateBindingInfo binding,
        ReleaseSnapshotInfo releaseSnapshot
) {
}