package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 联合放行批次记录（不可变）。放行成功后写入，后续证书撤销不回写。
 *
 * @param jointBatchKey      联合批次键，全局唯一（幂等键）
 * @param requestFingerprint 请求参数指纹（放行人 + 按字典序排序的测量键集合）
 * @param releasedBy         放行人（X-Actor-Id）
 * @param releasedAt         放行时间（UTC）
 * @param itemCount          本批测量条数（2～20）
 */
public record JointReleaseBatch(
        String jointBatchKey,
        String requestFingerprint,
        String releasedBy,
        Instant releasedAt,
        int itemCount) {
}
