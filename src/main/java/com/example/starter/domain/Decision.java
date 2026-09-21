package com.example.starter.domain;

import java.time.Instant;

/**
 * 播出决定：某频道在某时刻应播出的内容。
 *
 * @param type      决定类型：PROGRAM 命中编排片段；FALLBACK 播出保底素材
 * @param assetId   应播出的素材 ID（节目素材或保底素材）
 * @param reason    保底原因；type 为 FALLBACK 时必填：
 *                  NO_PUBLISHED_SCHEDULE（无已发布编排）、GAP（编排空档）、GRANT_REVOKED（授权已撤销）
 * @param segmentId 命中的片段 ID；仅 PROGRAM 时有值
 * @param start     片段开始时间；仅 PROGRAM 时有值
 * @param end       片段结束时间；仅 PROGRAM 时有值
 */
public record Decision(DecisionType type, String assetId, String reason,
                       String segmentId, Instant start, Instant end) {

    public enum DecisionType {
        PROGRAM, FALLBACK
    }

    public static Decision program(DraftSegment segment) {
        return new Decision(DecisionType.PROGRAM, segment.assetId(), null,
                segment.segmentId(), segment.start(), segment.end());
    }

    public static Decision fallback(String fallbackAssetId, String reason) {
        return new Decision(DecisionType.FALLBACK, fallbackAssetId, reason, null, null, null);
    }
}
