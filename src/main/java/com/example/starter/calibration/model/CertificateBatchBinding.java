package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * singleBatchOnly 证书与放行批次的绑定。证书首次被放行批次引用时建立，
 * 之后其他批次再引用该证书即 422 并返回已绑定批次。
 *
 * @param certificateId 被绑定的 singleBatchOnly 证书 ID
 * @param batchId       首次引用该证书的放行批次 ID
 * @param boundAt       绑定时间（UTC）
 */
public record CertificateBatchBinding(
        long certificateId,
        String batchId,
        Instant boundAt) {
}
