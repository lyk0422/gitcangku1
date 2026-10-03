package com.example.starter.dive.domain;

import java.time.Instant;

/**
 * 点位上传幂等批次。
 *
 * @param id           批次ID
 * @param sessionId    所属作业ID
 * @param requestId    客户端幂等请求ID
 * @param payloadHash  请求参数的SHA-256十六进制
 * @param pointCount   本批次点位数
 * @param firstSeq     本批次首个点位序号
 * @param lastSeq      本批次末个点位序号
 * @param pointVersion 本批次落库后的点位版本号
 * @param createdAtUtc 落库时刻（UTC）
 */
public record PointUpload(
        long id,
        long sessionId,
        String requestId,
        String payloadHash,
        int pointCount,
        int firstSeq,
        int lastSeq,
        int pointVersion,
        Instant createdAtUtc) {
}