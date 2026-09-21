package com.example.starter.domain;

import java.time.Instant;

/**
 * 播出片段：草稿与发布快照共用。
 *
 * @param segmentId 片段独立 ID（草稿内唯一）
 * @param assetId   素材 ID
 * @param start     播出开始时间（含），UTC 时刻，毫秒精度
 * @param end       播出结束时间（不含），UTC 时刻，毫秒精度
 */
public record DraftSegment(String segmentId, String assetId, Instant start, Instant end) {
}
